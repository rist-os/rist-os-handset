#!/bin/bash
# Public-image validation build: build, gate, stage, sign.
set -o pipefail
BN="${BN:?set BN to the build number, e.g. 2026090701}"
source "$(dirname "$0")/common.sh"
export RIST_PUBLIC_BUILD=true

: "${EXPECT_VC:?set EXPECT_VC to the app versionCode this image must ship}"

source build/envsetup.sh
lunch "$DEVICE-cur-user" || { echo "===VALIDATE_DONE rc=$? (lunch failed for $DEVICE-cur-user)==="; exit 1; }

echo "=== [1/5] build target-files + otatools $(date -u) ==="
m -j8 target-files-package otatools-package
rc=$?; if [ $rc -ne 0 ]; then echo "===VALIDATE_DONE rc=$rc (m failed)==="; exit $rc; fi
echo "=== [2/5] GATES $(date -u) ==="
P=out/target/product/stallion
APK=$P/system/priv-app/RistAssistant/RistAssistant.apk
# rc=93: the gate could not run; 94-97: the gate ran and the image failed it.
VC="$(rist_apk_versioncode "$APK" out/host/linux-x86/bin/aapt2)" \
  || { echo "===VALIDATE_DONE rc=93 (could not read the versionCode -- see above)==="; exit 93; }
: "${EXPECT_VC:?set EXPECT_VC to the versionCode this image is supposed to ship}"
echo "  RistAssistant vc=$VC (expect $EXPECT_VC)"
[ "$VC" = "$EXPECT_VC" ] || { echo "===VALIDATE_DONE rc=95 (vc=$VC, expected $EXPECT_VC)==="; exit 95; }
# The guard may land in any classes*.dex (multidex), so search every one.
DEXLIST="$(unzip -l "$APK")"
DRC=$?
[ "$DRC" -eq 0 ] || { echo "===VALIDATE_DONE rc=93 (cannot list $APK, unzip exit $DRC -- see above)==="; exit 93; }
DEXES="$(printf '%s\n' "$DEXLIST" | grep -oE 'classes[0-9]*\.dex' | sort -u)"
NDEX="$(printf '%s\n' "$DEXES" | grep -c '.')"
[ "$NDEX" -gt 0 ] || { echo "===VALIDATE_DONE rc=93 (no classes*.dex in $APK -- this is not a readable APK)==="; exit 93; }
# Extract to a file: command substitution drops NUL bytes.
DEXTMP="$(mktemp -d)" || { echo "===VALIDATE_DONE rc=93 (cannot create temp dir)==="; exit 93; }
trap 'rm -rf "$DEXTMP"' EXIT
HITS=0
for d in $DEXES; do
  unzip -p "$APK" "$d" > "$DEXTMP/dex"
  URC=$?
  [ "$URC" -eq 0 ] || { echo "===VALIDATE_DONE rc=93 (cannot extract $d from $APK, unzip exit $URC)==="; exit 93; }
  [ -s "$DEXTMP/dex" ] || { echo "===VALIDATE_DONE rc=93 ($d extracted to zero bytes)==="; exit 93; }
  n=$(LC_ALL=C strings "$DEXTMP/dex" | grep -c "push endpoint not configured yet")
  HITS=$((HITS+n))
done
[ "$HITS" -ge 1 ] || { echo "===VALIDATE_DONE rc=94 (crash fix MISSING in all $NDEX dex file(s) -- all of which WERE read)==="; exit 94; }
echo "  GATE: PushService blank-endpoint guard present ($HITS occurrence(s) across $NDEX dex file(s) read)."
RIST_REPO="$(cd "$(dirname "$0")/../.." && pwd)"
ADB_CHECK="$RIST_REPO/tools/check_adb_keys.py"
if [ ! -f "$ADB_CHECK" ] || ! command -v python3 >/dev/null 2>&1; then
  echo "  the adb-key gate could not run (need python3 and $ADB_CHECK)." >&2
  echo "  Nothing has been established about whether this public image carries a key." >&2
  echo "===VALIDATE_DONE rc=99 (adb_keys gate could not run)==="; exit 99
fi
python3 "$ADB_CHECK" "$P" --public
AKRC=$?
if [ "$AKRC" -eq 1 ]; then
  echo "===VALIDATE_DONE rc=96 (adb_keys PRESENT in public build -- see above)==="; exit 96
elif [ "$AKRC" -ne 0 ]; then
  echo "  The adb-key gate reached no verdict (UNCHECKED above). That is not a pass." >&2
  echo "===VALIDATE_DONE rc=99 (adb_keys gate inconclusive)==="; exit 99
fi
IS=$P/system/etc/rist/rist-provision-do.sh
[ -s "$IS" ] || { echo "  no Device-Owner provisioning script at $IS (or it is empty)." >&2; \
                  echo "===VALIDATE_DONE rc=93 (provisioning script absent -- init gate could not run)==="; exit 93; }
for pat in device_provisioned set-device-owner; do
  grep -q "$pat" "$IS" || { echo "===VALIDATE_DONE rc=97 (init missing $pat)==="; exit 97; }
done
echo "  GATE PASS: vc$VC public (no adb_keys) + DO-provision init present."
echo "=== [3/5] stage $(date -u) ==="
mkdir -p releases/$BN
TF="$(rist_newest "$P/obj/PACKAGING/target_files_intermediates/*-target_files*.zip")" \
  || { echo "===VALIDATE_DONE rc=90 (no target_files package -- see above)==="; exit 90; }
echo "  target_files = $TF"
cp "$TF" releases/$BN/$DEVICE-target_files.zip || { echo "===VALIDATE_DONE rc=90 (could not stage target_files)==="; exit 90; }
OT="$(rist_newest "out/soong/.intermediates/build/make/tools/otatools_package/otatools-package/linux_glibc_x86_64/gen/otatools.zip")" \
  || { echo "===VALIDATE_DONE rc=91 (no otatools package -- see above)==="; exit 91; }
cp "$OT" releases/$BN/$DEVICE-otatools.zip || { echo "===VALIDATE_DONE rc=91 (could not stage otatools)==="; exit 91; }

PATCH_CHECK="$RIST_REPO/tools/check_patches.py"
if [ ! -f "$PATCH_CHECK" ] || ! command -v python3 >/dev/null 2>&1; then
  echo "  the patch gate could not run (need python3 and $PATCH_CHECK)." >&2
  echo "  Refusing to sign an image whose patches were never verified." >&2
  echo "===VALIDATE_DONE rc=92 (patch gate could not run)==="; exit 92
fi
python3 "$PATCH_CHECK" "releases/$BN/$DEVICE-target_files.zip"
PRC=$?
case "$PRC" in
  0) echo "  patch gate: all patches confirmed present in the artefact" ;;
  1) echo "===VALIDATE_DONE rc=92 (a patch is MISSING from the image -- see above)==="; exit 92 ;;
  *)
     echo "===VALIDATE_DONE rc=92 (patch gate inconclusive, exit $PRC -- see above)==="; exit 92 ;;
esac

RIST_REPO="$(cd "$(dirname "$0")/../.." && pwd)"
DEBRAND_CHECK="$RIST_REPO/tools/check_debrand.py"
if [ ! -f "$DEBRAND_CHECK" ]; then
  echo "  $DEBRAND_CHECK is missing from this checkout." >&2
  echo "===VALIDATE_DONE rc=99 (debranding gate could not run -- checker absent)==="; exit 99
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "  python3 is not on PATH, so the debranding gate could not run." >&2
  echo "===VALIDATE_DONE rc=99 (debranding gate could not run -- no python3)==="; exit 99
fi
python3 "$DEBRAND_CHECK" "releases/$BN/$DEVICE-target_files.zip"
DBRC=$?
if [ "$DBRC" -eq 1 ]; then
  echo "" >&2
  echo "  The image still carries GrapheneOS branding (see the FAIL lines above). It is NOT" >&2
  echo "  publishable. Fix the cause, rebuild, and re-run -- do not sign this." >&2
  echo "===VALIDATE_DONE rc=98 (GrapheneOS branding present in the image)==="; exit 98
elif [ "$DBRC" -ne 0 ]; then
  echo "" >&2
  echo "  The debranding gate could not reach a verdict (see the UNCHECKED lines above)." >&2
  echo "  That is not a pass. Nothing has been established about this image's branding." >&2
  echo "===VALIDATE_DONE rc=99 (debranding gate inconclusive)==="; exit 99
fi
echo "  GATE PASS: no GrapheneOS branding in $DEVICE-target_files.zip."

ORGANICMAPS_CHECK="$RIST_REPO/tools/check_organicmaps_apk.py"
if [ ! -f "$ORGANICMAPS_CHECK" ]; then
  echo "  $ORGANICMAPS_CHECK is missing from this checkout." >&2
  echo "===VALIDATE_DONE rc=99 (Organic Maps gate could not run -- checker absent)==="; exit 99
fi
python3 "$ORGANICMAPS_CHECK" "releases/$BN/$DEVICE-target_files.zip"
OMRC=$?
if [ "$OMRC" -eq 1 ]; then
  echo "  The bundled Organic Maps APK failed its invariants (FAIL lines above)." >&2
  echo "===VALIDATE_DONE rc=95 (Organic Maps APK invariants violated)==="; exit 95
elif [ "$OMRC" -ne 0 ]; then
  echo "  The Organic Maps gate reached no verdict (CANNOT TELL above). That is not a pass." >&2
  echo "===VALIDATE_DONE rc=99 (Organic Maps gate inconclusive)==="; exit 99
fi
echo "  GATE PASS: Organic Maps APK signer and JNI invariants hold."

RELEASE_GATE_CHECK="$RIST_REPO/tools/check_release_gate.py"
if [ ! -f "$RELEASE_GATE_CHECK" ]; then
  echo "  $RELEASE_GATE_CHECK is missing from this checkout." >&2
  echo "===VALIDATE_DONE rc=99 (release gate could not run -- checker absent)==="; exit 99
fi
# --pre-signing: runs before step 4 signs, so test-keys is expected here; publication callers must not pass it.
RGARGS=(--variant public --pre-signing --target-files "releases/$BN/$DEVICE-target_files.zip")
[ -n "${RIST_RELEASE_LEDGER:-}" ]  && RGARGS=("${RGARGS[@]}" --ledger "$RIST_RELEASE_LEDGER")
[ -n "${RIST_FIRST_RELEASE:-}" ]   && RGARGS=("${RGARGS[@]}" --first-release)
[ -n "${RIST_ACK_SPL_CHANGE:-}" ]  && RGARGS=("${RGARGS[@]}" --ack-spl-change "$RIST_ACK_SPL_CHANGE")
[ -n "${RIST_ACK_PREVIOUS_LOST:-}" ] && RGARGS=("${RGARGS[@]}" --ack-previous-lost "$RIST_ACK_PREVIOUS_LOST")
[ -n "${RIST_PREVIOUS_TARGET_FILES:-}" ] && RGARGS=("${RGARGS[@]}" --previous-target-files "$RIST_PREVIOUS_TARGET_FILES")
[ -n "${RIST_PREVIOUS_TARGET_FILES:-}" ] && [ "${RIST_REHASH_PREVIOUS:-1}" = 1 ] && RGARGS=("${RGARGS[@]}" --rehash-previous)
python3 "$RELEASE_GATE_CHECK" "${RGARGS[@]}"
RGRC=$?
if [ "$RGRC" -eq 1 ]; then
  echo "  Release gate finding. If it is the SPL month, read the banner: crossing one is" >&2
  echo "  PERMANENT for every handset that boots this image." >&2
  echo "===VALIDATE_DONE rc=88 (release gate finding)==="; exit 88
elif [ "$RGRC" -ne 0 ]; then
  echo "  The release gate reached no verdict (UNCHECKED above). That is not a pass." >&2
  echo "===VALIDATE_DONE rc=99 (release gate inconclusive)==="; exit 99
fi
echo "  GATE PASS: adb key, OTA certificates and SPL month all cleared for $BN."

echo "=== [4/5] sign $(date -u) ==="
rist_export_signing_password
script/generate-release.sh "$DEVICE" "$BN"
rc=$?
echo "===VALIDATE_DONE rc=$rc at $(date -u)==="

if [ "$rc" -ne 0 ]; then
  echo "" >&2
  echo "SIGNING FAILED (rc=$rc). Any artefacts listed below are from an EARLIER run and are NOT" >&2
  echo "this build's output -- do not pull them back and do not flash them." >&2
fi

# rc=89: signing succeeded; the OTA package beside the factory image failed its gate.
if [ "$rc" -eq 0 ]; then
  echo "=== [5/5] OTA gate $(date -u) ==="
  PARTIAL_GATE="$RIST_REPO/tools/check_partial_ota.py"
  OTAZIP="$(rist_newest "releases/$BN/release-$DEVICE-$BN/*ota_update*.zip")" || OTAZIP=""
  VBMETA="releases/$BN/release-$DEVICE-$BN/$DEVICE-$BN/vbmeta.img"
  [ -f "$VBMETA" ] || VBMETA=""
  if [ -z "$OTAZIP" ]; then
    echo "  no *ota_update*.zip in releases/$BN/release-$DEVICE-$BN -- nothing to gate."
    echo "  If you expected one, generate-release.sh did not emit it and the OTA channel has no"
    echo "  package for this build."
  elif [ ! -f "$PARTIAL_GATE" ] || ! command -v python3 >/dev/null 2>&1; then
    echo "  the OTA gate could not run (need python3 and $PARTIAL_GATE)." >&2
    echo "  That is not a pass. $(basename "$OTAZIP") has not been examined." >&2
      echo "=== ARTIFACTS TO PULL BACK (printed before this exit) ==="
      ls -la releases/$BN/$DEVICE-target_files.zip releases/$BN/$DEVICE-otatools.zip 2>/dev/null
      ls -la releases/$BN/release-$DEVICE-$BN/*factory*.zip 2>/dev/null
    echo "===VALIDATE_DONE rc=89 (OTA gate could not run)==="; exit 89
  else
    OGARGS=()
    [ -n "$VBMETA" ] && OGARGS+=(--vbmeta "$VBMETA")
    OGARGS+=(--target-files "releases/$BN/$DEVICE-target_files.zip")
    [ -n "${RIST_OTA_EXPECT_OTACERT:-}" ] && OGARGS+=(--expect-otacert "$RIST_OTA_EXPECT_OTACERT")
    [ -n "${RIST_MIN_SPL:-}" ] && OGARGS+=(--min-security-patch "$RIST_MIN_SPL")
# ${OGARGS[@]+"${OGARGS[@]}"}: bash 3.2 aborts on an empty array under set -u.
    python3 "$PARTIAL_GATE" "$OTAZIP" ${OGARGS[@]+"${OGARGS[@]}"}
    OGRC=$?
    if [ "$OGRC" -eq 1 ]; then
      echo "" >&2
      echo "  The OTA package is NOT publishable (see the FAIL lines above). The FACTORY image" >&2
      echo "  signed above is fine and nothing needs rebuilding -- regenerate the OTA with" >&2
      echo "  --partial (tools/check_partial_ota.py --help shows the ota_from_target_files invocation)," >&2
      echo "  and otatools is already staged at releases/$BN/$DEVICE-otatools.zip." >&2
      echo "=== ARTIFACTS TO PULL BACK (printed before this exit) ==="
      ls -la releases/$BN/$DEVICE-target_files.zip releases/$BN/$DEVICE-otatools.zip 2>/dev/null
      ls -la releases/$BN/release-$DEVICE-$BN/*factory*.zip 2>/dev/null
      echo "===VALIDATE_DONE rc=89 (OTA package failed the partial-OTA gate)==="; exit 89
    elif [ "$OGRC" -ne 0 ]; then
      echo "" >&2
      echo "  The OTA gate could not reach a verdict (see the UNCHECKED lines above). That is" >&2
      echo "  not a pass -- supply what each line asks for and re-run it. The gate is" >&2
      echo "  standalone, so this does not need another build:" >&2
      echo "      python3 tools/check_partial_ota.py $OTAZIP --vbmeta $VBMETA ..." >&2
      echo "=== ARTIFACTS TO PULL BACK (printed before this exit) ==="
      ls -la releases/$BN/$DEVICE-target_files.zip releases/$BN/$DEVICE-otatools.zip 2>/dev/null
      ls -la releases/$BN/release-$DEVICE-$BN/*factory*.zip 2>/dev/null
      echo "===VALIDATE_DONE rc=89 (OTA gate inconclusive)==="; exit 89
    fi
    echo "  GATE PASS: $(basename "$OTAZIP") carries no Google firmware and its vbmeta is"
    echo "             consistent with the partitions it ships."
  fi
fi

echo "=== ARTIFACTS TO PULL BACK (all three) $(date -u) ==="
ls -la releases/$BN/$DEVICE-target_files.zip releases/$BN/$DEVICE-otatools.zip 2>/dev/null
ls -la releases/$BN/release-$DEVICE-$BN/*factory*.zip 2>/dev/null
exit "$rc"
