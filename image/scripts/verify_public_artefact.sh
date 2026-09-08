#!/bin/bash
# Decide whether a factory-image artefact is fit to publish.
#
#     image/scripts/verify_public_artefact.sh <dir-or-factory.zip> [options]
#
#       --target-files <zip>             the *-target_files.zip this release was signed from
#       --expect-otacert <sha256|path>   passed through to tools/check_partial_ota.py
#       --min-security-patch <YYYY-MM>   passed through to tools/check_partial_ota.py
#       --ledger <path>                  release ledger for tools/check_release_gate.py
#       --first-release                  there has never been a release
#       --ack-spl-change <YYYY-MM>       acknowledge crossing into that SPL month (permanent)
#       --selftest                       build fixtures and prove every check can fire and pass
#
# Exit: 0 pass, 1 findings, 2 a check did not run or misconfigured.
# Must stay bash 3.2 compatible (macOS): no mapfile, associative arrays, find -printf, sort -z, grep -P, stat -c.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF="$HERE/$(basename "$0")"

FAIL=0
SKIPPED=""
fail() { printf 'FAIL  %s\n' "$1"; FAIL=1; }
pass() { printf 'ok    %s\n' "$1"; }
note() { printf '      %s\n' "$1"; }
skip() { printf 'SKIP  %s\n' "$1"; SKIPPED="${SKIPPED}$1
"; }

if command -v sha256sum >/dev/null 2>&1; then SHA="sha256sum"
elif command -v shasum >/dev/null 2>&1; then SHA="shasum -a 256"
else SHA=""
fi

run_case() {
  # run_case <name> <expected-rc> <expected-substring> <args...>
  local name="$1"; local want_rc="$2"; local want_txt="$3"; shift 3
  local out rc
  out="$(RIST_VPA_IN_SELFTEST=1 bash "$FIXSELF" "$@" 2>&1)"
  rc=$?
  if [ "$rc" != "$want_rc" ]; then
    printf 'SELFTEST FAIL  %s: exit %s, expected %s\n' "$name" "$rc" "$want_rc"
    printf '%s\n' "$out" | sed 's/^/               | /'
    ST_FAIL=1; return
  fi
  if ! printf '%s\n' "$out" | grep -q -- "$want_txt"; then
    printf 'SELFTEST FAIL  %s: exit %s was right, but the output never said "%s"\n' \
           "$name" "$rc" "$want_txt"
    printf '%s\n' "$out" | sed 's/^/               | /'
    ST_FAIL=1; return
  fi
  printf 'SELFTEST ok    %-46s exit %s, said "%s"\n' "$name" "$rc" "$want_txt"
}

# $1 = root. Prints the artefact dir.
mk_release() {
  local root="$1" bn="2026082802" art tf
  art="$root/releases/$bn/release-stallion-$bn-publish"
  mkdir -p "$art" "$root/tfsrc/SYSTEM" "$root/tfsrc/PRODUCT/etc"
  printf 'ro.build.version.incremental=rist.%s\n' "$bn" > "$root/tfsrc/SYSTEM/build.prop"
  printf '127.0.0.1 localhost\n' > "$root/tfsrc/PRODUCT/etc/hosts"
  mk_tf_debrand_clean "$root" || return 1
  ( cd "$root/tfsrc" && zip -qr "$root/releases/$bn/stallion-target_files.zip" . ) || return 1
  printf 'PK-not-really\n' > "$art/stallion-factory-$bn.zip"
  printf 'device=stallion\nversion-bootloader=x\n' > "$art/REQUIRED_STOCK.txt"
  printf '#!/bin/bash\n# fixture\n' > "$art/flash_rist.sh"
  mk_sums "$art" || return 1
  printf '%s\n' "$art"
}

mk_sums() {
  local d="$1"
  ( cd "$d" && find . -maxdepth 1 -type f ! -name SHA256SUMS -print | LC_ALL=C sort \
      | while IFS= read -r f; do $SHA "$f" || exit 1; done ) > "$d/SHA256SUMS"
}

mk_tf_debrand_clean() {
  local root="$1" repo stage
  repo="$HERE/../.."
  mkdir -p "$root/tfsrc/SYSTEM/framework" "$root/tfsrc/PRODUCT/media" \
           "$root/tfsrc/RECOVERY/RAMDISK/system/bin" || return 1
  stage="$root/.fwstage"
  rm -rf "$stage"; mkdir -p "$stage/assets/images" || return 1
  cp "$repo/aosp/bootlogo/android-logo-mask.png"  "$stage/assets/images/" || return 1
  cp "$repo/aosp/bootlogo/android-logo-shine.png" "$stage/assets/images/" || return 1
  rm -f "$root/tfsrc/SYSTEM/framework/framework-res.apk"
  ( cd "$stage" && zip -qrX "$root/tfsrc/SYSTEM/framework/framework-res.apk" assets ) || return 1
  cp "$repo/aosp/bootanimation/bootanimation.zip" \
     "$root/tfsrc/PRODUCT/media/bootanimation.zip" || return 1
  cp "$repo/aosp/bootanimation/bootanimation.zip" \
     "$root/tfsrc/PRODUCT/media/bootanimation-dark.zip" || return 1
  printf 'ELF-stub RistOS Recovery ... RistOS Fastboot ...\n' \
     > "$root/tfsrc/RECOVERY/RAMDISK/system/bin/recovery" || return 1
  mk_tf_organicmaps "$root" stored || return 1
}

#   $1 = root, $2 = stored|deflated
mk_tf_organicmaps() {
  local root="$1" mode="$2"
  mkdir -p "$root/tfsrc/PRODUCT/app/OrganicMaps" "$root/tfsrc/META" || return 1
  printf 'name="OrganicMaps.apk" certificate="build/make/target/product/security/testkey" private_key="build/make/target/product/security/testkey.pk8" partition="product"\n' \
    > "$root/tfsrc/META/apkcerts.txt" || return 1
  python3 - "$root/tfsrc/PRODUCT/app/OrganicMaps/OrganicMaps.apk" "$mode" <<'PYOM' || return 1
import sys, zipfile
out, mode = sys.argv[1], sys.argv[2]
PAGE = 16384
ct = zipfile.ZIP_DEFLATED if mode == "deflated" else zipfile.ZIP_STORED
z = zipfile.ZipFile(out, "w")
z.writestr("AndroidManifest.xml", "fixture manifest")
z.writestr("classes.dex", "dex" * 100)
for abi in ("arm64-v8a", "armeabi-v7a"):
    name = "lib/%s/liborganicmaps.so" % abi
    zi = zipfile.ZipInfo(name)
    zi.compress_type = ct
    if ct == zipfile.ZIP_STORED:
        off = z.fp.tell()
        pad = (-(off + 30 + len(name.encode()))) % PAGE
        zi.extra = b"\x00" * pad
    z.writestr(zi, b"\x7fELF" + b"\0" * 4096)
z.close()
PYOM
}

tf_with_deflated_om() {
  local root="$1" bn="2026082802"
  mk_tf_organicmaps "$root" deflated || return 1
  rm -f "$root/releases/$bn/stallion-target_files.zip"
  ( cd "$root/tfsrc" && zip -qr "$root/releases/$bn/stallion-target_files.zip" . )
}

tf_with_gos_branding() {
  local root="$1" bn="2026082802"
  printf 'ELF-stub GrapheneOS Recovery ... GrapheneOS Fastboot ...\n' \
    > "$root/tfsrc/RECOVERY/RAMDISK/system/bin/recovery"
  rm -f "$root/releases/$bn/stallion-target_files.zip"
  ( cd "$root/tfsrc" && zip -qr "$root/releases/$bn/stallion-target_files.zip" . )
}

tf_with_gos_boot_logo() {
  # Two `local` lines: `local` expands all its arguments before assigning, so $root would be unset under set -u.
  local root="$1" bn="2026082802"
  local stage="$root/.fwstage"
  printf 'GrapheneOS hexagon and wordmark\n' > "$stage/assets/images/android-logo-mask.png"
  rm -f "$root/tfsrc/SYSTEM/framework/framework-res.apk"
  ( cd "$stage" && zip -qrX "$root/tfsrc/SYSTEM/framework/framework-res.apk" assets ) || return 1
  rm -f "$root/releases/$bn/stallion-target_files.zip"
  ( cd "$root/tfsrc" && zip -qr "$root/releases/$bn/stallion-target_files.zip" . )
}

tf_with_adb_key() {
  local root="$1" bn="2026082802"
  mkdir -p "$root/tfsrc/PRODUCT/etc/security"
  printf 'ssh-rsa AAAAB3NzaC1yc2EAAAADAQAB builder@workstation\n' \
    > "$root/tfsrc/PRODUCT/etc/security/adb_keys"
  rm -f "$root/releases/$bn/stallion-target_files.zip"
  ( cd "$root/tfsrc" && zip -qr "$root/releases/$bn/stallion-target_files.zip" . )
}

selftest() {
  [ -n "${RIST_VPA_IN_SELFTEST:-}" ] && { echo "refusing to nest --selftest" >&2; exit 2; }
  [ -n "$SHA" ] || { echo "selftest needs sha256sum or shasum" >&2; exit 2; }
  command -v zip >/dev/null || { echo "selftest needs zip" >&2; exit 2; }
  local T; T="$(mktemp -d)" || exit 2
  trap 'rm -rf "$T"' EXIT
  ST_FAIL=0

  mkdir -p "$T/repo/image/scripts"
  cp "$SELF" "$HERE/check_no_blobs.sh" "$T/repo/image/scripts/" || exit 2
  mkdir -p "$T/repo/tools" "$T/repo/aosp/bootlogo" "$T/repo/aosp/bootanimation" || exit 2
  cp "$HERE/../../tools/check_debrand.py" "$T/repo/tools/" 2>/dev/null || true
  cp "$HERE/../../tools/check_partial_ota.py" "$T/repo/tools/" 2>/dev/null || true
  cp "$HERE/../../tools/check_organicmaps_apk.py" "$T/repo/tools/" 2>/dev/null || true

  # Stub gate: exit code comes from $RIST_FIXTURE_RG_RC.
  cat > "$T/repo/tools/check_release_gate.py" <<'PYRG'
#!/usr/bin/env python3
# FIXTURE STUB -- written by verify_public_artefact.sh --selftest. Not the real gate.
import os, sys
a = sys.argv[1:]
print("stub check_release_gate.py argv: %s" % " ".join(a))
if "--variant" not in a or a[a.index("--variant") + 1] != "public":
    print("STUB FAIL: the caller did not state --variant public")
    sys.exit(3)
if "--target-files" not in a:
    print("STUB FAIL: the caller passed no --target-files")
    sys.exit(3)
tf = a[a.index("--target-files") + 1]
if not os.path.isfile(tf):
    print("STUB FAIL: --target-files %s is not a file" % tf)
    sys.exit(3)
rc = int(os.environ.get("RIST_FIXTURE_RG_RC", "0"))
print("STUB: exiting %d" % rc)
sys.exit(rc)
PYRG
  RIST_FIXTURE_RG_RC=0; export RIST_FIXTURE_RG_RC
  RIST_REPO_FOR_SELFTEST="$T/repo"; export RIST_REPO_FOR_SELFTEST
  cp "$HERE/../../aosp/bootlogo/android-logo-mask.png" \
     "$HERE/../../aosp/bootlogo/android-logo-shine.png" "$T/repo/aosp/bootlogo/" 2>/dev/null || true
  cp "$HERE/../../aosp/bootanimation/bootanimation.zip" \
     "$T/repo/aosp/bootanimation/" 2>/dev/null || true
  {
    echo "# fixture inventory"
    echo "# COMPLETE: yes"
    echo "product/priv-app/EuiccGoogle/EuiccGoogle.apk|$(printf 'x' | $SHA | cut -d' ' -f1)"
  } > "$T/repo/image/proprietary-files.txt"
  FIXSELF="$T/repo/image/scripts/verify_public_artefact.sh"

  local A; A="$(mk_release "$T/clean")" || { echo "fixture build failed" >&2; exit 2; }
  run_case "clean release passes" 0 "VERIFY PASS" "$A"

  local B; B="$(mk_release "$T/dirty")" || { echo "fixture build failed" >&2; exit 2; }
  tf_with_adb_key "$T/dirty" || exit 2
  run_case "adb_keys INSIDE the image fires" 1 "pre-authorised adb key is INSIDE" "$B"

  local C; C="$(mk_release "$T/loose")" || exit 2
  mkdir -p "$C/product/etc/security"
  printf 'ssh-rsa AAAA builder@workstation\n' > "$C/product/etc/security/adb_keys"
  mk_sums "$C"
  run_case "adb_keys loose in the artefact fires" 1 "adb_keys file is sitting loose" "$C"

  local D; D="$(mk_release "$T/noproof")" || exit 2
  rm -f "$T/noproof/releases/2026082802/stallion-target_files.zip"
  run_case "no target_files: refuses, does not pass" 2 "cannot tell whether this artefact" "$D"

  local E; E="$(mk_release "$T/fw")" || exit 2
  printf 'x\n' > "$E/bootloader-stallion-cloudripper-1.0.img"
  mk_sums "$E"
  run_case "Google firmware fires" 1 "Google firmware images present" "$E"

  local F; F="$(mk_release "$T/tampered")" || exit 2
  printf 'tampered after the checksums were written\n' > "$F/stallion-factory-2026082802.zip"
  run_case "tampered payload fires (SHA256SUMS)" 1 "SHA256SUMS does not verify" "$F"

  local G; G="$(mk_release "$T/uncovered")" || exit 2
  printf 'added after the checksums were written\n' > "$G/EXTRA-NOTES.txt"
  run_case "file absent from SHA256SUMS fires" 1 "not covered by SHA256SUMS" "$G"

  local H; H="$(mk_release "$T/nosums")" || exit 2
  rm -f "$H/SHA256SUMS"
  run_case "missing SHA256SUMS fires" 1 "no SHA256SUMS" "$H"

  local I; I="$(mk_release "$T/ota")" || exit 2
  printf 'payload\n' > "$I/stallion-ota_update-2026082802.zip"
  mk_sums "$I"
  run_case "OTA package fires" 1 "OTA package" "$I"

  local Q; Q="$(mk_release "$T/goodota")" || exit 2
  local FP
  FP="$(python3 - "$Q" <<'PY' 2>/dev/null
import os, sys
sys.path.insert(0, os.path.join(os.environ['RIST_REPO_FOR_SELFTEST'], 'tools'))
import check_partial_ota as C
art = sys.argv[1]
C.make_ota(os.path.join(art, 'stallion-ota_update-2026082802.zip'), C.CLEAN_SET, partial=True)
open(os.path.join(art, 'vbmeta.img'), 'wb').write(C.make_vbmeta(
    ['boot', 'dtbo', 'init_boot', 'pvmfw', 'vendor_boot', 'vendor_kernel_boot'],
    ['product', 'system', 'system_dlkm', 'system_ext', 'vendor', 'vendor_dlkm']))
print(C.cert_fingerprint(C.FIXTURE_CERT.encode()))
PY
)"
  if [ -z "$FP" ]; then
    printf 'SELFTEST FAIL  %-46s could not build the good-OTA fixture (python3?)\n' \
           "a correct partial OTA passes"
    ST_FAIL=1
  else
    mk_sums "$Q"
    run_case "a correct partial OTA in the artefact passes" 0 "cleared tools/check_partial_ota.py" \
             "$Q" --expect-otacert "$FP" \
             --target-files "$T/goodota/releases/2026082802/stallion-target_files.zip"
  fi

  local J; J="$(mk_release "$T/keys")" || exit 2
  printf 'binary\n' > "$J/platform.pk8"
  mk_sums "$J"
  run_case "private key material fires" 1 "private key material" "$J"

  local O; O="$(mk_release "$T/reltools")" || exit 2
  cp "$T/reltools/releases/2026082802/stallion-target_files.zip" "$O/stallion-target_files.zip"
  mk_sums "$O"
  run_case "target_files in the artefact fires" 1 "release-machine artefacts" "$O"

  local P2; P2="$(mk_release "$T/otatools")" || exit 2
  cp "$T/otatools/releases/2026082802/stallion-target_files.zip" "$P2/stallion-otatools.zip"
  mk_sums "$P2"
  run_case "otatools in the artefact fires" 1 "release-machine artefacts" "$P2"

  mkdir -p "$T/empty"
  run_case "empty artefact refused, not cleared" 2 "listed ZERO" "$T/empty"

  local L; L="$(mk_release "$T/gosrec")" || exit 2
  tf_with_gos_branding "$T/gosrec" || exit 2
  run_case "GrapheneOS recovery title fires" 1 "still carries GrapheneOS branding" "$L"

  local M; M="$(mk_release "$T/goslogo")" || exit 2
  tf_with_gos_boot_logo "$T/goslogo" || exit 2
  run_case "GrapheneOS boot logo in framework-res fires" 1 "still carries GrapheneOS branding" "$M"

  local N; N="$(mk_release "$T/nochecker")" || exit 2
  mv "$T/repo/tools/check_debrand.py" "$T/repo/tools/check_debrand.py.off" 2>/dev/null || true
  run_case "no debrand checker: SKIPs, does not certify" 2 "VERIFY PARTIAL" "$N"
  mv "$T/repo/tools/check_debrand.py.off" "$T/repo/tools/check_debrand.py" 2>/dev/null || true

  local R1; R1="$(mk_release "$T/rgfail")" || exit 2
  RIST_FIXTURE_RG_RC=1; export RIST_FIXTURE_RG_RC
  run_case "release gate FINDING fails the artefact" 1 "release gate reported a finding" "$R1"

  local R2; R2="$(mk_release "$T/rgunk")" || exit 2
  RIST_FIXTURE_RG_RC=2; export RIST_FIXTURE_RG_RC
  run_case "release gate UNCHECKED does not certify" 2 "reached no verdict" "$R2"
  RIST_FIXTURE_RG_RC=0; export RIST_FIXTURE_RG_RC

  local R3; R3="$(mk_release "$T/rgargs")" || exit 2
  run_case "--ledger / --ack-spl-change reach the gate" 0 "ack-spl-change 2026-09" \
           "$R3" --target-files "$T/rgargs/releases/2026082802/stallion-target_files.zip" \
           --ledger "$T/rgargs/ledger.json" --ack-spl-change 2026-09

  local R4; R4="$(mk_release "$T/rgabsent")" || exit 2
  mv "$T/repo/tools/check_release_gate.py" "$T/repo/tools/check_release_gate.py.off" || exit 2
  run_case "no release gate checker: SKIPs, does not certify" 2 "no checker at" "$R4"
  mv "$T/repo/tools/check_release_gate.py.off" "$T/repo/tools/check_release_gate.py" || exit 2

  local S1; S1="$(mk_release "$T/omdeflate")" || exit 2
  tf_with_deflated_om "$T/omdeflate" || exit 2
  run_case "Organic Maps JNI libs Deflated fires" 1 "failed its invariants" "$S1"

  local S2; S2="$(mk_release "$T/omabsent")" || exit 2
  mv "$T/repo/tools/check_organicmaps_apk.py" "$T/repo/tools/check_organicmaps_apk.py.off" || exit 2
  run_case "no Organic Maps checker: SKIPs, does not certify" 2 "Organic Maps APK: no checker at" "$S2"
  mv "$T/repo/tools/check_organicmaps_apk.py.off" "$T/repo/tools/check_organicmaps_apk.py" || exit 2

  local K; K="$(mk_release "$T/noinv")" || exit 2
  printf '# COMPLETE: no\n' > "$T/repo/image/proprietary-files.txt"
  run_case "ungenerated inventory refuses, does not pass" 2 "not marked COMPLETE" "$K"

  echo ""
  if [ "$ST_FAIL" -eq 0 ]; then
    echo "SELFTEST PASS -- every check above fired on a fixture carrying its defect, and the"
    echo "                clean release passed. 24 cases."
    echo "                The OTA gate has its own:    python3 tools/check_partial_ota.py --selftest"
    echo "                So does the release gate:    python3 tools/check_release_gate.py --selftest"
    exit 0
  fi
  echo "SELFTEST FAILED -- the checks in this file are NOT proved. Do not rely on them." >&2
  exit 1
}

TARGET=""
TF_ARG=""
OTACERT_ARG=""
MINSPL_ARG=""
LEDGER_ARG=""
ACK_ARG=""
FIRST_REL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --selftest)     selftest ;;
    --target-files) TF_ARG="${2:-}"; shift 2 || true
                    [ -n "$TF_ARG" ] || { echo "--target-files needs a path" >&2; exit 2; } ;;
    --expect-otacert) OTACERT_ARG="${2:-}"; shift 2 || true
                    [ -n "$OTACERT_ARG" ] || { echo "--expect-otacert needs a value" >&2; exit 2; } ;;
    --min-security-patch) MINSPL_ARG="${2:-}"; shift 2 || true
                    [ -n "$MINSPL_ARG" ] || { echo "--min-security-patch needs a value" >&2; exit 2; } ;;
    --ledger)       LEDGER_ARG="${2:-}"; shift 2 || true
                    [ -n "$LEDGER_ARG" ] || { echo "--ledger needs a path" >&2; exit 2; } ;;
    --ack-spl-change) ACK_ARG="${2:-}"; shift 2 || true
                    [ -n "$ACK_ARG" ] || { echo "--ack-spl-change needs YYYY-MM" >&2; exit 2; } ;;
    --first-release) FIRST_REL=1; shift ;;
    -h|--help)      echo "usage: verify_public_artefact.sh (see the script header)"; exit 0 ;;
    -*)             echo "unknown option: $1" >&2; exit 2 ;;
    *)              [ -z "$TARGET" ] || { echo "one artefact at a time: $1" >&2; exit 2; }
                    TARGET="$1"; shift ;;
  esac
done
[ -n "$TARGET" ] || { echo "usage: verify_public_artefact.sh (see the script header)" >&2; exit 2; }
[ -e "$TARGET" ] || { echo "no such path: $TARGET" >&2; exit 2; }
[ -z "$TF_ARG" ] || [ -f "$TF_ARG" ] || { echo "--target-files $TF_ARG is not a file" >&2; exit 2; }

TMP="$(mktemp -d)" || { echo "cannot make a temp dir" >&2; exit 2; }
trap 'rm -rf "$TMP"' EXIT

echo "=== verify_public_artefact: $TARGET"

listing_rc=0
if [ -d "$TARGET" ]; then
  ( cd "$TARGET" && find . -type f ) > "$TMP/raw"
  listing_rc=$?
  sed 's|^\./||' < "$TMP/raw" > "$TMP/listing"
  listing_what="files under $TARGET"
elif [ "${TARGET##*.}" = "zip" ]; then
  command -v unzip >/dev/null || { echo "unzip not found" >&2; exit 2; }
  unzip -Z1 "$TARGET" > "$TMP/listing"
  listing_rc=$?
  listing_what="entries in $(basename "$TARGET")"
else
  echo "expected a directory or a .zip, got: $TARGET" >&2; exit 2
fi
if [ "$listing_rc" -ne 0 ]; then
  echo "FAIL  could not list the artefact (exit $listing_rc). The error above is the reason." >&2
  echo "      Nothing was examined, so nothing is cleared." >&2
  exit 2
fi
N_LISTED="$(grep -c . "$TMP/listing")"
if [ "$N_LISTED" -eq 0 ]; then
  echo "FAIL  the artefact listed ZERO $listing_what." >&2
  echo "      An empty listing is a broken read, never a clean artefact -- a release directory" >&2
  echo "      or factory zip always contains files. Refusing to certify something unexamined." >&2
  exit 2
fi
note "artefact: $N_LISTED $listing_what"

echo ""
echo "--- check_no_blobs.sh --artefact (adb key, Google's proprietary files) ---"
if [ -n "$TF_ARG" ]; then
  RIST_TARGET_FILES="$TF_ARG" bash "$HERE/check_no_blobs.sh" --artefact "$TARGET"
else
  bash "$HERE/check_no_blobs.sh" --artefact "$TARGET"
fi
blob_rc=$?
echo "--- end check_no_blobs.sh (exit $blob_rc) ---"
echo ""
case "$blob_rc" in
  0) : ;;
  1) FAIL=1 ;;
  *) echo "VERIFY UNCHECKED -- check_no_blobs.sh could not decide (exit $blob_rc)." >&2
     echo "      Its message above is the reason and it is the thing to fix. Nothing here" >&2
     echo "      overrides it: an artefact nobody could look inside is not a clean artefact." >&2
     exit 2 ;;
esac

if [ -d "$TARGET" ]; then
  if [ -z "$SHA" ]; then
    skip "SHA256SUMS verification: neither sha256sum nor shasum is on PATH"
  elif [ ! -f "$TARGET/SHA256SUMS" ]; then
    fail "no SHA256SUMS in $TARGET"
    note "deblob_release.sh writes it. Without it a downloader cannot tell a truncated"
    note "transfer from the image we published. Do not upload this."
  else
    N_SUMS="$(grep -c . "$TARGET/SHA256SUMS")"
    if [ "$N_SUMS" -eq 0 ]; then
      fail "SHA256SUMS is empty -- and an empty one looks exactly like a good one"
    else
      if ( cd "$TARGET" && $SHA -c SHA256SUMS ) > "$TMP/sumcheck" 2>&1; then
        pass "SHA256SUMS verifies ($N_SUMS files re-hashed here)"
      else
        fail "SHA256SUMS does not verify:"
        grep -v ': OK$' "$TMP/sumcheck" | head -20 | while IFS= read -r l; do note "$l"; done
        note "Either the transfer damaged this copy or the artefact was edited after signing."
      fi
      ( cd "$TARGET" && find . -maxdepth 1 -type f ! -name SHA256SUMS -print ) \
        | LC_ALL=C sort > "$TMP/onDisk"
      sed -n 's/^[0-9a-f]\{64\}  //p' "$TARGET/SHA256SUMS" | LC_ALL=C sort > "$TMP/summed"
      UNCOVERED="$(comm -23 "$TMP/onDisk" "$TMP/summed")"
      if [ -n "$UNCOVERED" ]; then
        fail "files in the artefact are not covered by SHA256SUMS:"
        printf '%s\n' "$UNCOVERED" | while IFS= read -r f; do note "$f"; done
        note "An uncovered file is one a downloader cannot verify at all, and it is exactly"
        note "where something would be added after the fact."
      else
        pass "SHA256SUMS covers every file in the artefact"
      fi
    fi
  fi
else
  skip "SHA256SUMS verification: the artefact is a bare zip, so its checksum file is a sibling"
fi

OTA="$(grep -E '(^|/)[^/]*ota[^/]*\.zip$' "$TMP/listing" || true)"
if [ -n "$OTA" ]; then
  PARTIAL_GATE="$(cd "$HERE/../.." && pwd)/tools/check_partial_ota.py"
  VBM=""
  if [ -d "$TARGET" ]; then
    VBM="$(find "$TARGET" -maxdepth 3 -name vbmeta.img -type f 2>/dev/null | head -1)"
  fi
  printf '%s\n' "$OTA" | while IFS= read -r f; do note "OTA package in the artefact: $f"; done
  if [ ! -f "$PARTIAL_GATE" ] || ! command -v python3 >/dev/null 2>&1; then
    fail "an OTA package is in this artefact and the OTA gate could not run:"
    note "needs python3 and $PARTIAL_GATE"
    note "An unexamined OTA package is not a publishable one. Remove it from the upload or"
    note "install what the gate needs."
  else
    ota_bad=0
    printf '%s\n' "$OTA" > "$TMP/otalist"
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      if [ -d "$TARGET" ]; then p="$TARGET/$f"; else p="$TARGET"; fi
      echo ""
      echo "--- check_partial_ota.py $(basename "$p") ---"
      # Array, not ${TF_ARG:+--target-files "$TF_ARG"}: quotes inside a ${:+} expansion are literal.
      # ${PA[@]+"${PA[@]}"} because bash 3.2 aborts on an empty array under set -u.
      PA=()
      [ -n "$VBM" ] && PA+=(--vbmeta "$VBM")
      [ -n "$TF_ARG" ] && PA+=(--target-files "$TF_ARG")
      [ -n "$OTACERT_ARG" ] && PA+=(--expect-otacert "$OTACERT_ARG")
      [ -n "$MINSPL_ARG" ] && PA+=(--min-security-patch "$MINSPL_ARG")
      python3 "$PARTIAL_GATE" "$p" ${PA[@]+"${PA[@]}"}
      prc=$?
      echo "--- end check_partial_ota.py (exit $prc) ---"
      [ "$prc" -eq 0 ] || ota_bad=1
    done < "$TMP/otalist"
    echo ""
    if [ "$ota_bad" -ne 0 ]; then
      fail "an OTA package in this artefact did not clear the OTA gate (see above)."
      note "Regenerate it with --partial, or supply what the UNCHECKED lines ask for."
      note "tools/check_partial_ota.py --help shows the ota_from_target_files invocation."
    else
      pass "the OTA package in this artefact cleared tools/check_partial_ota.py"
    fi
  fi
else
  pass "no OTA package in the artefact ($N_LISTED $listing_what)"
fi

RELTOOLS="$(grep -E '(^|/)[^/]*(target_files|otatools)[^/]*\.zip$' "$TMP/listing" || true)"
if [ -n "$RELTOOLS" ]; then
  fail "release-machine artefacts are in this artefact:"
  printf '%s\n' "$RELTOOLS" | while IFS= read -r f; do note "$f"; done
  note "target_files carries every Google-proprietary file in the build as an individual,"
  note "individually-listed path -- publishing it is worse than publishing the image it came"
  note "from. otatools is the release tooling and is not part of the download either. Keep both"
  note "on the release machine; do not upload them."
else
  pass "no target_files / otatools package in the artefact"
fi

# avb_pkmd.bin is deliberately not matched: it is our AVB public key and has to stay.
KEYS="$(grep -E '(\.pk8|\.jks|\.keystore|\.p12|_rsa|\.priv)$|(^|/)(platform|releasekey|avb|media|networkstack|shared|testkey|sdk_sandbox|bluetooth)\.pem$' "$TMP/listing" || true)"
if [ -n "$KEYS" ]; then
  fail "private key material in the artefact:"
  printf '%s\n' "$KEYS" | while IFS= read -r f; do note "$f"; done
  note "The platform key signs system code on every device locked to it, and there is no"
  note "revocation: shipping it once ends the device line."
else
  pass "no private key material in the artefact"
fi

echo ""
echo "--- tools/check_debrand.py (GrapheneOS branding inside the image) ---"
DB="$HERE/../../tools/check_debrand.py"
DBTF="$TF_ARG"
if [ -z "$DBTF" ]; then
  DBDIR="$(dirname "$TARGET")"
  DBN="$(find "$DBDIR" -maxdepth 1 -type f -name '*target_files*.zip' 2>/dev/null | grep -c .)"
  if [ "$DBN" -eq 1 ]; then
    DBTF="$(find "$DBDIR" -maxdepth 1 -type f -name '*target_files*.zip' 2>/dev/null)"
  fi
fi
if [ ! -f "$DB" ]; then
  skip "GrapheneOS branding: no checker at $DB"
elif ! command -v python3 >/dev/null 2>&1; then
  skip "GrapheneOS branding: python3 is not on PATH, so nothing was inspected"
elif [ -z "$DBTF" ]; then
  skip "GrapheneOS branding: no target_files package (pass --target-files <zip>)"
else
  python3 "$DB" "$DBTF"
  DBRC=$?
  if [ "$DBRC" -eq 0 ]; then
    pass "no GrapheneOS branding in $(basename "$DBTF")"
  elif [ "$DBRC" -eq 1 ]; then
    fail "the image still carries GrapheneOS branding (see above)"
    note "A free download called RistOS must not present GrapheneOS as its vendor."
    note "See TRADEMARKS.md."
  else
    skip "GrapheneOS branding: check_debrand.py reached no verdict (exit $DBRC, see above)"
  fi
fi

echo ""
echo "--- tools/check_release_gate.py (adb key, OTA certs, SPL month / rollback index) ---"
RG="$HERE/../../tools/check_release_gate.py"
if [ ! -f "$RG" ]; then
  skip "release gate: no checker at $RG"
elif ! command -v python3 >/dev/null 2>&1; then
  skip "release gate: python3 is not on PATH, so nothing was inspected"
elif [ -z "$DBTF" ]; then
  skip "release gate: no target_files package (pass --target-files <zip>)"
else
  # Array for the same reason as check 3's.
  RGA=("$RG")
  [ -d "$TARGET" ] && RGA=("${RGA[@]}" "$TARGET")
  RGA=("${RGA[@]}" --variant public --target-files "$DBTF")
  [ -n "$LEDGER_ARG" ] && RGA=("${RGA[@]}" --ledger "$LEDGER_ARG")
  [ -n "$FIRST_REL" ] && RGA=("${RGA[@]}" --first-release)
  [ -n "$ACK_ARG" ] && RGA=("${RGA[@]}" --ack-spl-change "$ACK_ARG")
  python3 "${RGA[@]}"
  RGRC=$?
  if [ "$RGRC" -eq 0 ]; then
    pass "release gate: adb key, OTA certificates and SPL month all cleared"
  elif [ "$RGRC" -eq 1 ]; then
    fail "the release gate reported a finding (see above)"
    note "If it is the SPL month: crossing one is PERMANENT for every handset that boots this"
    note "image. Read the banner, then re-run with --ack-spl-change <YYYY-MM> only if that is"
    note "a decision you are making on purpose."
  else
    skip "release gate: check_release_gate.py reached no verdict (exit $RGRC, see above)"
  fi
fi

echo ""
echo "--- tools/check_organicmaps_apk.py (bundled Organic Maps: signer, stored+aligned JNI) ---"
OM="$HERE/../../tools/check_organicmaps_apk.py"
if [ ! -f "$OM" ]; then
  skip "Organic Maps APK: no checker at $OM"
elif ! command -v python3 >/dev/null 2>&1; then
  skip "Organic Maps APK: python3 is not on PATH, so nothing was inspected"
elif [ -z "$DBTF" ]; then
  skip "Organic Maps APK: no target_files package (pass --target-files <zip>)"
else
  python3 "$OM" "$DBTF"
  OMRC=$?
  if [ "$OMRC" -eq 0 ]; then
    pass "Organic Maps APK: signer is not a platform-class key, JNI libs stored and aligned"
  elif [ "$OMRC" -eq 1 ]; then
    fail "the bundled Organic Maps APK failed its invariants (see above)"
    note "A platform-class signature on a third-party APK gives it the OS's own identity, and"
    note "Deflated JNI libs mean the MAPS button throws UnsatisfiedLinkError on the handset."
    note "See aosp/prebuilts/apps/OrganicMaps/Android.bp."
  else
    skip "Organic Maps APK: check_organicmaps_apk.py reached no verdict (exit $OMRC, see above)"
  fi
fi

echo ""
if [ "$FAIL" -ne 0 ]; then
  echo "VERIFY FAIL -- do not publish $TARGET"
  exit 1
fi
if [ -n "$SKIPPED" ]; then
  echo "VERIFY PARTIAL -- nothing found, but these checks did not run:"
  printf '%s' "$SKIPPED" | while IFS= read -r s; do [ -n "$s" ] && echo "      $s"; done
  echo "      This is not a certification, and it is NOT a pass: this exits 2 so that"
  echo "      \`verify_public_artefact.sh && upload\` cannot upload an unverified artefact."
  exit 2
fi
echo "VERIFY PASS -- $TARGET is fit to publish"
echo "      no pre-authorised adb key, no Google firmware, SHA256SUMS verifies and covers"
echo "      everything, no OTA package, no key material, no GrapheneOS branding, the release"
echo "      gate cleared (adb key, OTA certificates, SPL month) and the bundled Organic Maps"
echo "      APK holds its signer and JNI invariants."
exit 0
