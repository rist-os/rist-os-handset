#!/bin/bash
# Upload a signed release directory to the public download bucket: exactly the files SHA256SUMS
# lists, then SHA256SUMS, then its signature. Nothing else in the directory leaves this machine.
#
#     tools/upload_release.sh [--dry-run] <publish-dir> <build-number>
#
# RIST_DL_BUCKET     s3://<public download bucket>   (the one dl.ristos.org serves)
# RIST_DL_ENDPOINT   optional --endpoint-url (R2)
# Credentials come from the aws CLI's own configuration (AWS_PROFILE). Do not `set -a` an env file
# into this shell: that exports the keys to every child process.
#
# A staging directory collects strays -- target_files, otatools, RELEASE-RECORDs, helper scripts --
# and `aws s3 sync .` publishes all of them. This script cannot: it uploads by name, from the signed
# list, and refuses a list that names anything that must never be public.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DRY=0
[ "${1:-}" = "--dry-run" ] && { DRY=1; shift; }
DIR="${1:-}"; BN="${2:-}"
[ -n "$DIR" ] && [ -d "$DIR" ] && [ -n "$BN" ] \
  || { echo "usage: $0 [--dry-run] <publish-dir> <build-number>" >&2; exit 2; }
case "$BN" in ''|*[!0-9A-Za-z._-]*) echo "build number '$BN' has characters a bucket prefix should not" >&2; exit 2 ;; esac
DIR="$(cd "$DIR" && pwd)"
: "${RIST_DL_BUCKET:?set RIST_DL_BUCKET, e.g. s3://your-download-bucket}"
DEST="${RIST_DL_BUCKET%/}/$BN"

die() { echo "upload_release: $*" >&2; exit 1; }

# The key every handset and INSTALL.md pins; RIST_RELEASE_PUBKEY overrides it for a fork.
pinned_pubkey() {
  if [ -n "${RIST_RELEASE_PUBKEY:-}" ]; then printf '%s' "$RIST_RELEASE_PUBKEY"; return; fi
  sed -n 's/^[[:space:]]*const val PUBLIC_KEY = "\([A-Za-z0-9+\/=]*\)".*/\1/p' \
    "$HERE/../app/src/main/java/watch/rist/assistant/OtaSignature.kt" 2>/dev/null | head -1
}

cd "$DIR" || die "cannot enter $DIR"
[ -f SHA256SUMS ] || die "no SHA256SUMS in $DIR"
[ -f SHA256SUMS.minisig ] || die "no SHA256SUMS.minisig in $DIR. Sign it first; an unsigned release is not published."
command -v minisign >/dev/null 2>&1 || die "minisign not found"
KEY="$(pinned_pubkey)"
[ -n "$KEY" ] || die "no pinned release key (OtaSignature.PUBLIC_KEY not found; set RIST_RELEASE_PUBKEY)"
minisign -V -q -P "$KEY" -m SHA256SUMS -x SHA256SUMS.minisig >/dev/null 2>&1 \
  || die "SHA256SUMS.minisig does not verify against the pinned release key. Nothing uploaded."
echo "ok    SHA256SUMS is signed by the pinned release key"

if command -v sha256sum >/dev/null 2>&1; then CHECK="sha256sum -c"
elif command -v shasum >/dev/null 2>&1; then CHECK="shasum -a 256 -c"
else die "neither sha256sum nor shasum found"; fi
$CHECK SHA256SUMS >/dev/null 2>&1 || die "files do not match SHA256SUMS (run: $CHECK SHA256SUMS). Nothing uploaded."
echo "ok    every listed file matches SHA256SUMS"

FILES=()
while IFS= read -r f; do
  [ -n "$f" ] || continue
  case "$f" in
    */*|.*) die "SHA256SUMS names '$f': only plain top-level files are published" ;;
    *target_files*|*otatools*)
      die "SHA256SUMS names '$f'. target_files and otatools are NEVER published: they hold every
       proprietary file of the image. Keep them on the key volumes and the private bucket only." ;;
    *.pk8|*.key|*.pem|*.p12|*.jks|*keystore*|*.env|*adbkey*|RELEASE-RECORD*|*.sh)
      die "SHA256SUMS names '$f', which must never be on a public download. Remove it, re-hash, re-sign." ;;
  esac
  [ -f "$f" ] && [ ! -L "$f" ] || die "'$f' is listed but is not a plain file here"
  FILES+=("$f")
done < <(awk '{ f=$2; sub(/^\*/, "", f); sub(/^\.\//, "", f); print f }' SHA256SUMS)
[ "${#FILES[@]}" -gt 0 ] || die "SHA256SUMS lists no files"

for f in * .[!.]*; do
  [ -e "$f" ] || continue
  case "$f" in SHA256SUMS|SHA256SUMS.minisig) continue ;; esac
  listed=0
  for g in "${FILES[@]}"; do [ "$g" = "$f" ] && { listed=1; break; }; done
  [ "$listed" -eq 1 ] || echo "skip  $f  (not in SHA256SUMS; stays on this machine)"
done

EP=()
[ -n "${RIST_DL_ENDPOINT:-}" ] && EP=(--endpoint-url "$RIST_DL_ENDPOINT")
put() {
  if [ "$DRY" -eq 1 ]; then
    echo "would upload  $1  ->  $DEST/$1"
  else
    echo "upload  $1"
    aws ${EP[@]+"${EP[@]}"} s3 cp "$1" "$DEST/$1" --only-show-errors || die "upload of $1 failed"
  fi
}

[ "$DRY" -eq 1 ] || command -v aws >/dev/null 2>&1 || die "aws CLI not found"
for f in "${FILES[@]}"; do put "$f"; done
# The checksums and their signature last: until they land, the release is not discoverable as complete.
put SHA256SUMS
put SHA256SUMS.minisig
echo
if [ "$DRY" -eq 1 ]; then echo "dry run: nothing uploaded"
else echo "uploaded ${#FILES[@]} listed file(s) plus SHA256SUMS and its signature to $DEST/"; fi
