#!/bin/bash
# Build, gate, stage and sign a non-public image.
set -o pipefail
BN="${BN:?set BN to the build number, e.g. 2026090701}"
source "$(dirname "$0")/common.sh"

: "${EXPECT_VC:?set EXPECT_VC to the app versionCode this image must ship}"

source build/envsetup.sh
lunch "$DEVICE-cur-user" || { echo "FATAL: lunch failed for $DEVICE-cur-user (env not sourced, or wrong device)" >&2; exit 1; }
echo "=== [0/4] force-clean stale RistAssistant $(date -u) ==="
rm -rf out/soong/.intermediates/vendor/rist/pixel-phone/app/RistAssistant/android_common/RistAssistant*.apk \
       out/target/product/$DEVICE/system/priv-app/RistAssistant/RistAssistant.apk \
       out/target/product/$DEVICE/obj/APPS/RistAssistant_intermediates
echo "=== [1/4] build target-files + otatools $(date -u) ==="
m -j6 target-files-package otatools-package
rc=$?; if [ $rc -ne 0 ]; then echo "===GOLDEN2_DONE rc=$rc (m failed)==="; exit $rc; fi
echo "=== [2/4] GATES $(date -u) ==="
P=out/target/product/stallion
APK=$P/system/priv-app/RistAssistant/RistAssistant.apk
# rc=93 means the gate could not run; 94-97 mean it ran and the image failed it.
VC="$(rist_apk_versioncode "$APK" out/host/linux-x86/bin/aapt2)" \
  || { echo "===GOLDEN2_DONE rc=93 (could not read the versionCode -- see above)==="; exit 93; }
echo "  RistAssistant vc=$VC (expect $EXPECT_VC)"
[ "$VC" = "$EXPECT_VC" ] || { echo "===GOLDEN2_DONE rc=95 (vc=$VC, expected $EXPECT_VC)==="; exit 95; }

MAN="$(unzip -p "$APK" AndroidManifest.xml | tr -d '\000')"
MRC=$?
[ "$MRC" -eq 0 ] || { echo "===GOLDEN2_DONE rc=93 (could not extract AndroidManifest.xml, unzip exit $MRC)==="; exit 93; }
[ -n "$MAN" ] || { echo "===GOLDEN2_DONE rc=93 (AndroidManifest.xml extracted but empty)==="; exit 93; }
printf '%s' "$MAN" | grep -q "com.android.settings" || { echo "===GOLDEN2_DONE rc=95 (queries missing)==="; exit 95; }

SRC_AK="vendor/rist/pixel-phone/aosp/adb_keys"
RIST_REPO="$(cd "$(dirname "$0")/../.." && pwd)"
ADB_CHECK="$RIST_REPO/tools/check_adb_keys.py"
if [ ! -f "$ADB_CHECK" ] || ! command -v python3 >/dev/null 2>&1; then
  echo "  the adb-key gate could not run (need python3 and $ADB_CHECK)." >&2
  echo "  That is not a pass: nothing has been established about whose key is in this image." >&2
  echo "===GOLDEN2_DONE rc=93 (adb_keys gate could not run)==="; exit 93
fi
python3 "$ADB_CHECK" "$P" --private --expect-key "$SRC_AK"
AKRC=$?
if [ "$AKRC" -eq 1 ]; then
  echo "" >&2
  echo "  This is the PRIVATE build (RIST_PUBLIC_BUILD unset), which is DEFINED by baking the" >&2
  echo "  key in $SRC_AK into product/etc/security/adb_keys. The FAIL line above says which of" >&2
  echo "  the three failures happened. Do not sign this." >&2
  echo "===GOLDEN2_DONE rc=96 (adb_keys: wrong or missing -- see above)==="; exit 96
elif [ "$AKRC" -ne 0 ]; then
  echo "" >&2
  echo "  The adb-key gate reached no verdict (UNCHECKED above). $SRC_AK is gitignored, so on" >&2
  echo "  any machine but the one it was created on it is missing by default -- put your adb" >&2
  echo "  public key (~/.android/adbkey.pub) there, or build with RIST_PUBLIC_BUILD=true and use" >&2
  echo "  sign_public.sh instead. An unanswered question is not a pass." >&2
  echo "===GOLDEN2_DONE rc=93 (adb_keys gate inconclusive)==="; exit 93
fi
echo "  GATE: adb_keys carries the exact key from $SRC_AK."

IS=$P/system/etc/rist/rist-provision-do.sh
[ -s "$IS" ] || { echo "  no Device-Owner provisioning script at $IS (or it is empty)." >&2; \
                  echo "===GOLDEN2_DONE rc=93 (provisioning script absent -- init gate could not run)==="; exit 93; }
for pat in device_provisioned SYSTEM_APPLICATION_OVERLAY set-device-owner adb_enabled "am force-stop"; do
  grep -q "$pat" "$IS" || { echo "===GOLDEN2_DONE rc=97 (init missing $pat)==="; exit 97; }
done
echo "  GATE PASS: vc$VC + queries + adb_keys + init(device_provisioned,appops,DO)."
echo "=== [3/4] stage $(date -u) ==="
mkdir -p releases/$BN
TF="$(rist_newest "$P/obj/PACKAGING/target_files_intermediates/*-target_files*.zip")" \
  || { echo "===GOLDEN2_DONE rc=90 (no target_files package -- see above)==="; exit 90; }
echo "  target_files = $TF"
cp "$TF" releases/$BN/$DEVICE-target_files.zip || { echo "===GOLDEN2_DONE rc=90 (could not stage target_files)==="; exit 90; }

python3 "$ADB_CHECK" "releases/$BN/$DEVICE-target_files.zip" --private --expect-key "$SRC_AK"
AKRC2=$?
if [ "$AKRC2" -eq 1 ]; then
  echo "  The staged target_files does not carry the key the build tree did (see above)." >&2
  echo "===GOLDEN2_DONE rc=96 (adb_keys wrong/missing in the staged target_files)==="; exit 96
elif [ "$AKRC2" -ne 0 ]; then
  echo "  The adb-key gate could not read the staged target_files (see above). Not a pass." >&2
  echo "===GOLDEN2_DONE rc=99 (adb_keys gate inconclusive on the staged target_files)==="; exit 99
fi
echo "  GATE: the staged target_files carries that key too."

OT="$(rist_newest "out/soong/.intermediates/build/make/tools/otatools_package/otatools-package/linux_glibc_x86_64/gen/otatools.zip")" \
  || { echo "===GOLDEN2_DONE rc=91 (no otatools package -- see above)==="; exit 91; }
cp "$OT" releases/$BN/$DEVICE-otatools.zip || { echo "===GOLDEN2_DONE rc=91 (could not stage otatools)==="; exit 91; }
echo "=== [4/4] sign $(date -u) ==="
rist_export_signing_password
script/generate-release.sh "$DEVICE" "$BN"
rc=$?
echo "===GOLDEN2_DONE rc=$rc at $(date -u)==="

if [ "$rc" -ne 0 ]; then
  echo "" >&2
  echo "SIGNING FAILED (rc=$rc). Any factory zip listed below is from an EARLIER run. It is not" >&2
  echo "this build's output; do not ship it." >&2
fi
ls -la releases/$BN/release-$DEVICE-$BN/*factory*.zip 2>/dev/null
exit "$rc"
