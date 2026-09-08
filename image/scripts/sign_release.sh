#!/bin/bash
set -o pipefail
BN="${BN:?set BN to the build number, e.g. 2026090701}"
source "$(dirname "$0")/common.sh"
source build/envsetup.sh
lunch "$DEVICE-cur-user" || { echo "FATAL: lunch failed for $DEVICE-cur-user (env not sourced, or wrong device)" >&2; exit 1; }
echo "=== [1/3] building target-files-package + otatools-package $(date -u) ==="
m -j6 target-files-package otatools-package
rc=$?
if [ $rc -ne 0 ]; then echo "===SIGN_DONE rc=$rc (m target/otatools failed) at $(date -u)==="; exit $rc; fi
echo "=== [2/3] staging into releases/$BN $(date -u) ==="
mkdir -p releases/$BN
TF="$(rist_newest "out/target/product/$DEVICE/obj/PACKAGING/target_files_intermediates/*-target_files*.zip" \
                  "out/dist/*target_files*.zip")" \
  || { echo "===SIGN_DONE rc=90 (no target_files in either location -- see above) at $(date -u)==="; exit 90; }
echo "target_files = $TF"
cp "$TF" releases/$BN/$DEVICE-target_files.zip || { echo "===SIGN_DONE rc=90 (could not stage target_files) at $(date -u)==="; exit 90; }
OT="$(rist_newest "out/otatools.zip")" \
  || { echo "===SIGN_DONE rc=91 (no otatools -- see above) at $(date -u)==="; exit 91; }
cp "$OT" releases/$BN/$DEVICE-otatools.zip || { echo "===SIGN_DONE rc=91 (could not stage otatools) at $(date -u)==="; exit 91; }

RIST_REPO="$(cd "$(dirname "$0")/../.." && pwd)"
DEBRAND_CHECK="$RIST_REPO/tools/check_debrand.py"
if [ ! -f "$DEBRAND_CHECK" ] || ! command -v python3 >/dev/null 2>&1; then
  echo "debranding gate could not run: need python3 and $DEBRAND_CHECK" >&2
  echo "===SIGN_DONE rc=99 (debranding gate could not run) at $(date -u)==="; exit 99
fi
python3 "$DEBRAND_CHECK" "releases/$BN/$DEVICE-target_files.zip"
DBRC=$?
if [ "$DBRC" -eq 1 ]; then
  echo "The image still carries GrapheneOS branding (FAIL lines above). Not signing it." >&2
  echo "===SIGN_DONE rc=98 (GrapheneOS branding present in the image) at $(date -u)==="; exit 98
elif [ "$DBRC" -ne 0 ]; then
  echo "The debranding gate reached no verdict (UNCHECKED lines above). That is not a pass." >&2
  echo "===SIGN_DONE rc=99 (debranding gate inconclusive) at $(date -u)==="; exit 99
fi
echo "GATE PASS: no GrapheneOS branding in $DEVICE-target_files.zip."

ORGANICMAPS_CHECK="$RIST_REPO/tools/check_organicmaps_apk.py"
if [ ! -f "$ORGANICMAPS_CHECK" ]; then
  echo "$ORGANICMAPS_CHECK is missing from this checkout." >&2
  echo "===SIGN_DONE rc=99 (Organic Maps gate could not run -- checker absent) at $(date -u)==="; exit 99
fi
python3 "$ORGANICMAPS_CHECK" "releases/$BN/$DEVICE-target_files.zip"
OMRC=$?
if [ "$OMRC" -eq 1 ]; then
  echo "The bundled Organic Maps APK failed its invariants (FAIL lines above). Not signing it." >&2
  echo "===SIGN_DONE rc=94 (Organic Maps APK invariants violated) at $(date -u)==="; exit 94
elif [ "$OMRC" -ne 0 ]; then
  echo "The Organic Maps gate reached no verdict (CANNOT TELL above). That is not a pass." >&2
  echo "===SIGN_DONE rc=99 (Organic Maps gate inconclusive) at $(date -u)==="; exit 99
fi
echo "GATE PASS: Organic Maps APK signer and JNI invariants hold."

RELEASE_GATE_CHECK="$RIST_REPO/tools/check_release_gate.py"
if [ ! -f "$RELEASE_GATE_CHECK" ]; then
  echo "$RELEASE_GATE_CHECK is missing from this checkout." >&2
  echo "===SIGN_DONE rc=99 (release gate could not run -- checker absent) at $(date -u)==="; exit 99
fi
RGARGS=(--variant private --target-files "releases/$BN/$DEVICE-target_files.zip")
if [ -n "${RIST_RELEASE_LEDGER:-}" ]; then
  RGARGS=("${RGARGS[@]}" --ledger "$RIST_RELEASE_LEDGER")
fi
if [ -n "${RIST_FIRST_RELEASE:-}" ]; then
  RGARGS=("${RGARGS[@]}" --first-release)
fi
if [ -n "${RIST_ACK_SPL_CHANGE:-}" ]; then
  RGARGS=("${RGARGS[@]}" --ack-spl-change "$RIST_ACK_SPL_CHANGE")
fi
if [ -f "$RIST_REPO/aosp/adb_keys" ]; then
  RGARGS=("${RGARGS[@]}" --expect-key "$RIST_REPO/aosp/adb_keys")
fi
python3 "$RELEASE_GATE_CHECK" "${RGARGS[@]}"
RGRC=$?
if [ "$RGRC" -eq 1 ]; then
  echo "The release gate reported a finding (FAIL lines above). Not signing this build." >&2
  echo "If it is the SPL month: read the banner before doing anything else. Crossing one is" >&2
  echo "PERMANENT for every handset that boots the image. Re-run with RIST_ACK_SPL_CHANGE set" >&2
  echo "only if that is a decision you are making on purpose." >&2
  echo "===SIGN_DONE rc=95 (release gate finding) at $(date -u)==="; exit 95
elif [ "$RGRC" -ne 0 ]; then
  echo "The release gate reached no verdict (UNCHECKED lines above). That is not a pass." >&2
  echo "===SIGN_DONE rc=99 (release gate inconclusive) at $(date -u)==="; exit 99
fi
echo "GATE PASS: adb key, OTA certificates and SPL month all cleared for $BN."

ls -la releases/$BN/
echo "=== [3/3] signing release with OUR keys $(date -u) ==="
rist_export_signing_password
script/generate-release.sh "$DEVICE" "$BN"
rc=$?
echo "===SIGN_DONE rc=$rc at $(date -u)==="

if [ "$rc" -ne 0 ]; then
  echo "" >&2
  echo "SIGNING FAILED (rc=$rc). Nothing below is a signed release. The .zip files listed are the" >&2
  echo "target_files/otatools INPUTS staged in step 2, plus anything left by an earlier run." >&2
fi
echo "=== release output ==="
ls -la releases/$BN/release-$DEVICE-$BN/ 2>/dev/null | head -50
echo "=== staged inputs / any artifacts present (NOT proof this run produced them) ==="
find releases/$BN -maxdepth 2 \( -name "*factory*" -o -name "*ota*" -o -name "*.zip" \) 2>/dev/null
exit "$rc"
