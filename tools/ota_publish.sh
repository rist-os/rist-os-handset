#!/bin/bash
# Publish a signed OTA package to an S3-compatible bucket and prove it is servable before devices see it.
set -uo pipefail

EXPIRES_DAYS="${RIST_OTA_EXPIRES_DAYS:-14}"
case "$EXPIRES_DAYS" in
  ''|*[!0-9]*) echo "RIST_OTA_EXPIRES_DAYS='$EXPIRES_DAYS' is not a number" >&2; exit 2 ;;
esac
HERE="$(cd "$(dirname "$0")" && pwd)"

# no capture or redirection: minisign owns the terminal so it can prompt for the passphrase
sign_manifest() {
  local manifest="$1"; shift
  local keys=()
  [ -n "${RIST_OTA_SECRET_KEY:-}" ] && keys+=(-s "$RIST_OTA_SECRET_KEY")
  [ -n "${RIST_OTA_PUBLIC_KEY:-}" ] && keys+=(-p "$RIST_OTA_PUBLIC_KEY")
  python3 "$HERE/ota_sign.py" "$manifest" ${keys[@]+"${keys[@]}"} "$@" || {
    echo >&2
    echo "SIGNING FAILED. Nothing has been uploaded." >&2
    echo "A manifest without a valid .minisig is refused by every device (OtaSignature), so" >&2
    echo "publishing one would take every handset off updates while looking like a clean release." >&2
    return 1
  }
  [ -s "$manifest.minisig" ] || {
    echo "ota_sign.py exited 0 but wrote no signature at $manifest.minisig. Nothing uploaded." >&2
    return 1
  }
}

if [ "${1:-}" = "--promote" ]; then
  DEVICE="${2:-}"; FROM="${3:-beta}"; TO="${4:-stable}"
  [ -n "$DEVICE" ] || { echo "usage: $0 --promote <device> [from] [to]" >&2; exit 2; }
  : "${RIST_OTA_BUCKET:?set RIST_OTA_BUCKET}"
  EP=()
  [ -n "${RIST_OTA_ENDPOINT:-}" ] && EP=(--endpoint-url "$RIST_OTA_ENDPOINT")
  command -v aws >/dev/null || { echo "aws CLI not found" >&2; exit 2; }

  PTMP="$(mktemp -d "${TMPDIR:-/tmp}/ota-promote.XXXXXX")" \
    || { echo "cannot create a temp directory" >&2; exit 2; }
  trap 'rm -rf "$PTMP"' EXIT
  PM="$PTMP/manifest.json"

  echo "==> promoting $DEVICE: $FROM -> $TO"
  # "channel" is inside the signed bytes: promotion must rewrite it and RE-SIGN, not byte-copy
  aws ${EP[@]+"${EP[@]}"} s3 cp "$RIST_OTA_BUCKET/v1/ota/$DEVICE/$FROM" "$PM" --only-show-errors || {
    echo "cannot read v1/ota/$DEVICE/$FROM from the bucket; $TO is unchanged" >&2; exit 1; }

  python3 - "$PM" "$TO" <<'PY' || { echo "could not rewrite the channel; $TO is unchanged" >&2; exit 1; }
import json, sys
path, to = sys.argv[1], sys.argv[2]
with open(path) as fh:
    m = json.load(fh)
was = m.get("channel")
m["channel"] = to
with open(path, "w") as fh:
    json.dump(m, fh, indent=2, sort_keys=True)
    fh.write("\n")
print("    channel %s -> %s, build %s" % (was, to, m.get("build")))
PY

  sign_manifest "$PM" --refresh "$EXPIRES_DAYS" || exit 1

  aws ${EP[@]+"${EP[@]}"} s3 cp "$PM" "$RIST_OTA_BUCKET/v1/ota/$DEVICE/$TO" \
      --content-type application/json --only-show-errors || {
    echo "manifest upload failed; $TO is unchanged" >&2; exit 1; }

  # sidecar second, always
  aws ${EP[@]+"${EP[@]}"} s3 cp "$PM.minisig" "$RIST_OTA_BUCKET/v1/ota/$DEVICE/$TO.minisig" \
      --content-type text/plain --only-show-errors || {
    echo >&2
    echo "SIDECAR UPLOAD FAILED, AND THE MANIFEST IS ALREADY PUBLISHED at" >&2
    echo "  v1/ota/$DEVICE/$TO" >&2
    echo "Every device on $TO will refuse that manifest until the signature lands. Re-run this" >&2
    echo "promotion; the rewritten manifest lives in a temp dir this script is about to delete," >&2
    echo "so re-running is the answer, not a hand upload." >&2
    exit 1; }

  echo "    v1/ota/$DEVICE/$TO now carries the build from $FROM, re-signed for $TO"
  exit 0
fi

ZIP="${1:-}"
CHANNEL="${2:-beta}"
[ -n "$ZIP" ] && [ -f "$ZIP" ] || { echo "usage: $0 <signed-ota.zip> [channel]   (or --promote)" >&2; exit 2; }
: "${RIST_OTA_BUCKET:?set RIST_OTA_BUCKET, e.g. s3://your-ota-bucket}"
: "${RIST_OTA_PUBLIC_BASE:?set RIST_OTA_PUBLIC_BASE, the URL devices fetch from}"
NAME="$(basename "$ZIP")"
BASE="${RIST_OTA_PUBLIC_BASE%/}"

# ${EP[@]+"${EP[@]}"} below: bash 3.2 aborts on expanding an EMPTY array under set -u
EP=()
[ -n "${RIST_OTA_ENDPOINT:-}" ] && EP=(--endpoint-url "$RIST_OTA_ENDPOINT")

command -v aws >/dev/null || { echo "aws CLI not found; needed for the multipart upload" >&2; exit 2; }

# a temp DIRECTORY: GNU mktemp -t requires trailing X's in the template and would yield ""
MTMP="$(mktemp -d "${TMPDIR:-/tmp}/ota-publish.XXXXXX")" \
  || { echo "cannot create a temp directory" >&2; exit 2; }
trap 'rm -rf "$MTMP"' EXIT
MANIFEST="$MTMP/manifest.json"

echo "==> generating the manifest from the package"
python3 "$HERE/ota_manifest.py" "$ZIP" --url "$BASE/$NAME" --channel "$CHANNEL" \
  --expires-days "$EXPIRES_DAYS" > "$MANIFEST" || exit 1

read_field() {
  local v
  v="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))[sys.argv[2]])" "$MANIFEST" "$1")"
  local rc=$?
  if [ "$rc" -ne 0 ] || [ -z "$v" ]; then
    echo "could not read '$1' from the generated manifest (python exit $rc)." >&2
    echo "Nothing has been uploaded. The manifest is the input to every step below, so this is" >&2
    echo "a local fault, not a bucket or network one." >&2
    exit 1
  fi
  printf '%s' "$v"
}
OFFSET="$(read_field payload_offset)" || exit 1
BUILD="$(read_field build)"           || exit 1
DEVICE="$(read_field device)"         || exit 1
case "$OFFSET" in
  ''|*[!0-9]*) echo "payload_offset='$OFFSET' is not a number; refusing to build a byte range from it" >&2; exit 1 ;;
esac
echo "    device=$DEVICE build=$BUILD channel=$CHANNEL payload_offset=$OFFSET"

echo "==> signing the manifest"
sign_manifest "$MANIFEST" || exit 1

echo "==> uploading the package (this is the slow part)"
# package first, verified, then the manifest: devices act on the manifest
aws ${EP[@]+"${EP[@]}"} s3 cp "$ZIP" "$RIST_OTA_BUCKET/$NAME" --only-show-errors || {
  echo "package upload FAILED; manifest NOT published, so no device will be pointed at it" >&2
  exit 1
}

echo "==> verifying the object over its public URL"
LEN=""
# a just-uploaded object can answer 200 with the whole body until the edge is warm; retry
for attempt in 1 2 3 4 5 6; do
  LEN=$(curl -fsSL -o /dev/null -w '%{size_download}' -r "$OFFSET-$((OFFSET+65535))" "$BASE/$NAME" 2>/dev/null)
  [ "$LEN" = "65536" ] && break
  if [ "$attempt" -lt 6 ]; then
    echo "    range not served yet (got ${LEN:-0}, wanted 65536) -- object still cold, retrying in $((attempt*5))s [$attempt/6]" >&2
    sleep $((attempt*5))
  fi
done
if [ "$LEN" != "65536" ]; then
  echo "    RANGE REQUEST FAILED (got ${LEN:-0} bytes, wanted 65536) after 6 attempts over ~75s." >&2
  echo "    The bucket is not serving byte ranges, or the object is not public." >&2
  echo "    Manifest NOT published. update_engine streams the payload with Range; without it," >&2
  echo "    every device would try to pull the whole $(du -h "$ZIP" | cut -f1) and still fail." >&2
  echo "    Check by hand before re-uploading -- the object may be fine and only the check early:" >&2
  echo "      curl -sI -r 0-65535 $BASE/$NAME | head -3      # want 206 + content-range" >&2
  exit 1
fi

curl -fsSL -r "$OFFSET-$((OFFSET+65535))" "$BASE/$NAME" -o "$MANIFEST.remote" 2>/dev/null
dd if="$ZIP" bs=1 skip="$OFFSET" count=65536 of="$MANIFEST.local" 2>/dev/null
if ! cmp -s "$MANIFEST.remote" "$MANIFEST.local"; then
  echo "    SERVED BYTES DIFFER FROM THE PACKAGE at offset $OFFSET. Manifest NOT published." >&2
  exit 1
fi
echo "    ok: 64 KiB at offset $OFFSET is byte-identical over HTTP"

echo "==> publishing the manifest"
# the key is the path the device asks for (no .json), so Content-Type must be explicit
aws ${EP[@]+"${EP[@]}"} s3 cp "$MANIFEST" "$RIST_OTA_BUCKET/v1/ota/$DEVICE/$CHANNEL" \
  --content-type application/json --only-show-errors || {
  echo "manifest upload failed; the package is uploaded but no device will see it yet" >&2
  exit 1
}

echo "==> publishing the signature"
# signature LAST: a manifest without its sidecar is refused and re-polled; the reverse order leaves a stale signature
aws ${EP[@]+"${EP[@]}"} s3 cp "$MANIFEST.minisig" "$RIST_OTA_BUCKET/v1/ota/$DEVICE/$CHANNEL.minisig" \
  --content-type text/plain --only-show-errors || {
  echo >&2
  echo "SIDECAR UPLOAD FAILED, AND THE MANIFEST IS ALREADY PUBLISHED at" >&2
  echo "  $BASE/v1/ota/$DEVICE/$CHANNEL" >&2
  echo "EVERY DEVICE ON $CHANNEL WILL REFUSE THAT MANIFEST until the signature lands beside it" >&2
  echo "(OtaSignature.Fault.NO_SIGNATURE). This is not a partial success: the channel is down for" >&2
  echo "updates until you re-run this publish. The package is uploaded and verified, so the re-run" >&2
  echo "is cheap in everything but time." >&2
  exit 1
}

echo
echo "published $DEVICE build $BUILD"
echo "  package:   $BASE/$NAME"
echo "  manifest:  $BASE/v1/ota/$DEVICE/$CHANNEL"
echo "  signature: $BASE/v1/ota/$DEVICE/$CHANNEL.minisig"
echo
echo "Devices are told about a build only by the manifest, so re-running this is safe: the package"
echo "is re-uploaded and re-verified before the manifest is replaced, and the manifest is re-signed"
echo "before either of them moves."
if [ "$CHANNEL" != "stable" ]; then
  echo
  echo "Only $CHANNEL devices see this. When you are satisfied:"
  echo "    $0 --promote $DEVICE $CHANNEL stable"
fi
