#!/bin/bash
# usage: bash image/scripts/deblob_release.sh releases/<BN>/release-<device>-<BN> [outdir]
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

SRC="${1:-}"
[ -n "$SRC" ] || { echo "usage: $0 <release-dir> [outdir]" >&2; exit 2; }
[ -d "$SRC" ] || { echo "not a directory: $SRC" >&2; exit 2; }
SRC="$(cd "$SRC" && pwd)"
OUT="${2:-$SRC-publish}"

if [ -e "$SRC/punch-manifest.txt" ]; then
  echo "$SRC contains punch-manifest.txt, so it has already been punched." >&2
  echo "" >&2
  echo "De-blobbing runs BEFORE the punch, not after: build -> sign -> deblob -> punch ->" >&2
  echo "verify -> sign the checksums. punch_blobs.sh writes" >&2
  echo "SHA256SUMS and SHA256SUMS.refilled at the two moments each of them is true, and running" >&2
  echo "this script over its output would overwrite one and invalidate the other." >&2
  echo "" >&2
  echo "Start again from the signed release directory." >&2
  exit 2
fi

for t in unzip zip; do
  command -v "$t" >/dev/null || { echo "$t not found (needed to repack the factory zip)" >&2; exit 2; }
done
command -v python3 >/dev/null || {
  echo "python3 not found." >&2
  echo "It is needed to read the AVB descriptors out of vbmeta.img, which is where" >&2
  echo "PARTITIONS.txt's vbmeta-covers list comes from. Without it this script can only" >&2
  echo "produce a release that flash_rist.sh refuses." >&2
  exit 2
}

FACTORY="$(find "$SRC" -maxdepth 1 -type f -name '*factory*.zip' | head -1)"
[ -n "$FACTORY" ] || { echo "no *factory*.zip in $SRC" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/x"
unzip -q "$FACTORY" -d "$TMP/x" || { echo "could not unpack $FACTORY" >&2; exit 2; }

INNER="$(find "$TMP/x" -maxdepth 2 -name 'bootloader-*.img' | head -1)"
[ -n "$INNER" ] || {
  echo "no bootloader-*.img anywhere in $FACTORY." >&2
  echo "Either this release was already de-blobbed, or the zip layout changed. Refusing to" >&2
  echo "guess -- inspect it by hand before publishing anything." >&2
  exit 2
}
DIR="$(dirname "$INNER")"

BL="$(find "$DIR" -maxdepth 1 -name 'bootloader-*.img' | head -1)"
RD="$(find "$DIR" -maxdepth 1 -name 'radio-*.img' | head -1)"

DEVICE_GUESS="$(basename "$DIR" | cut -d- -f1)"
bl_ver=""; rd_ver=""
[ -n "$BL" ] && bl_ver="$(basename "$BL" .img | sed "s/^bootloader-${DEVICE_GUESS}-//")"
[ -n "$RD" ] && rd_ver="$(basename "$RD" .img | sed "s/^radio-${DEVICE_GUESS}-//")"

EC_FILES="$(find "$DIR" -maxdepth 1 -name '*.ec.bin' | sort)"

ANDROID_INFO=""
if [ -f "$DIR/android-info.txt" ]; then
  ANDROID_INFO="$(tr -d '\r' < "$DIR/android-info.txt" | grep -E '^require' || true)"
fi
if [ -z "$ANDROID_INFO" ] && [ -f "$DIR/android-info.zip" ]; then
  ANDROID_INFO="$(unzip -p "$DIR/android-info.zip" android-info.txt 2>/dev/null | tr -d '\r' | grep -E '^require' || true)"
fi
if [ -z "$ANDROID_INFO" ]; then
  IMGZIP="$(find "$DIR" -maxdepth 1 -name 'image-*.zip' | head -1)"
  [ -n "$IMGZIP" ] && ANDROID_INFO="$(unzip -p "$IMGZIP" android-info.txt 2>/dev/null | tr -d '\r' | grep -E '^require' || true)"
fi

ai_bl="$(printf '%s\n' "$ANDROID_INFO" | sed -n 's/^require version-bootloader=//p' | head -1)"
ai_bb="$(printf '%s\n' "$ANDROID_INFO" | sed -n 's/^require version-baseband=//p'   | head -1)"
[ -n "$ai_bl" ] && bl_ver="$ai_bl"
[ -n "$ai_bb" ] && rd_ver="$ai_bb"

# Offsets follow avbtool.py's FORMAT_STRINGs: AvbVBMetaHeader 256, AvbHashDescriptor 72+60,
# AvbHashtreeDescriptor 120+60, AvbChainPartitionDescriptor 32+60, AvbPropertyDescriptor 32; big-endian, unpadded.
AVB_PARSE="$TMP/avb_descriptors.py"
cat > "$AVB_PARSE" <<'PYEOF'
import struct, sys

data = open(sys.argv[1], 'rb').read()
if len(data) < 256 or data[:4] != b'AVB0':
    sys.stderr.write('not an AVB vbmeta image: %s\n' % sys.argv[1])
    sys.exit(3)

HDR = '!4s2L2QL2Q2Q2Q2Q2QQLL47sx80x'
assert struct.calcsize(HDR) == 256
(magic, vmaj, vmin, auth_sz, aux_sz, algo,
 hash_off, hash_sz, sig_off, sig_sz, pk_off, pk_sz,
 pkm_off, pkm_sz, desc_off, desc_sz,
 rollback, flags, ril, rel) = struct.unpack(HDR, data[:256])

start = 256 + auth_sz + desc_off
blob = data[start:start + desc_sz]
if len(blob) != desc_sz:
    sys.stderr.write('truncated descriptor block\n')
    sys.exit(3)

off = 0
count = 0
while off < len(blob):
    if off + 16 > len(blob):
        sys.stderr.write('truncated descriptor header\n')
        sys.exit(3)
    tag, nbf = struct.unpack('!QQ', blob[off:off + 16])
    body = blob[off + 16:off + 16 + nbf]
    if len(body) != nbf:
        sys.stderr.write('truncated descriptor body\n')
        sys.exit(3)
    count += 1
    if tag == 0:
        kl, vl = struct.unpack('!QQ', body[:16])
        key = body[16:16 + kl].decode('utf-8', 'replace')
        val = body[16 + kl + 1:16 + kl + 1 + vl].decode('utf-8', 'replace')
        print('PROP %s=%s' % (key, val))
    elif tag == 1:
        nl = struct.unpack('!L', body[88:92])[0]
        print('HASHTREE %s' % body[164:164 + nl].decode('utf-8', 'replace'))
    elif tag == 2:
        nl = struct.unpack('!L', body[40:44])[0]
        print('HASH %s' % body[116:116 + nl].decode('utf-8', 'replace'))
    elif tag == 4:
        nl = struct.unpack('!L', body[4:8])[0]
        print('CHAIN %s' % body[76:76 + nl].decode('utf-8', 'replace'))
    off += 16 + nbf

if count == 0:
    sys.stderr.write('vbmeta carries ZERO descriptors\n')
    sys.exit(3)
sys.stderr.write('vbmeta: %d descriptors\n' % count)
PYEOF

VBMETA_IMG="$DIR/vbmeta.img"
if [ ! -f "$VBMETA_IMG" ]; then
  _vbz="$(find "$DIR" -maxdepth 1 -type f -name 'image-*.zip' | head -1)"
  if [ -n "$_vbz" ] && unzip -p "$_vbz" vbmeta.img > "$TMP/vbmeta.img" 2>/dev/null \
     && [ -s "$TMP/vbmeta.img" ]; then
    VBMETA_IMG="$TMP/vbmeta.img"
    echo "vbmeta.img: read from nested $(basename "$_vbz")" >&2
  fi
fi
if [ ! -f "$VBMETA_IMG" ]; then
  echo "no vbmeta.img in $DIR, and none inside any nested image-*.zip there." >&2
  echo "" >&2
  echo "Without it there is no way to know which partitions our signature pins, and so no way to" >&2
  echo "write a PARTITIONS.txt that is true. flash_rist.sh refuses a release with no" >&2
  echo "PARTITIONS.txt, and a guessed one is the brick-after-relock case it exists to prevent." >&2
  exit 2
fi

DESC="$TMP/avb.txt"
if ! python3 "$AVB_PARSE" "$VBMETA_IMG" > "$DESC"; then
  echo "" >&2
  echo "could not read the AVB descriptors out of $VBMETA_IMG (the error above is the reason)." >&2
  echo "Nothing is written. A PARTITIONS.txt whose vbmeta-covers could not be read would turn" >&2
  echo "flash_rist.sh's coverage check into a NOTE, and a NOTE is the check not running." >&2
  exit 2
fi

rist_fp=""
for k in vendor product boot dtbo init_boot vendor_boot; do
  rist_fp="$(sed -n "s|^PROP com\.android\.build\.${k}\.fingerprint=||p" "$DESC" | head -1)"
  [ -n "$rist_fp" ] && break
done

vb_build=""
[ -n "$rist_fp" ] && vb_build="$(printf '%s' "$rist_fp" | cut -d/ -f4)"

PIN_FILE="$HERE/../proprietary-files.txt"
pin_build=""
if [ -f "$PIN_FILE" ]; then
  pin_build="$(sed -n 's/^# REQUIRED_STOCK_BUILD_ID:[[:space:]]*//p' "$PIN_FILE" | head -1)"
fi

if [ -n "$pin_build" ] && [ -n "$vb_build" ] && [ "$pin_build" != "$vb_build" ]; then
  echo "" >&2
  echo "REFUSING: the required stock build ID is stated twice and the two disagree." >&2
  echo "" >&2
  echo "  $PIN_FILE says:   $pin_build" >&2
  echo "  $VBMETA_IMG says: $vb_build" >&2
  echo "      (from $rist_fp)" >&2
  echo "" >&2
  echo "The first is the Google build this repo says a device must already be running; the second" >&2
  echo "is the build this image was actually compiled against. They are the same coupling seen" >&2
  echo "from two ends, and when they differ one of them is wrong. Writing either one into" >&2
  echo "REQUIRED_STOCK.txt hands a user a check that passes on the wrong firmware." >&2
  exit 2
fi

build_id="$pin_build"
[ -n "$build_id" ] || build_id="$vb_build"
if [ -z "$build_id" ]; then
  echo "" >&2
  echo "REFUSING: could not determine the stock build ID." >&2
  echo "" >&2
  echo "Looked in:" >&2
  echo "  $PIN_FILE   (a '# REQUIRED_STOCK_BUILD_ID:' line)" >&2
  echo "  $VBMETA_IMG (a com.android.build.<partition>.fingerprint property)" >&2
  echo "" >&2
  echo "flash_rist.sh:250-270 refuses a REQUIRED_STOCK.txt with no build-id=, and it is right to." >&2
  echo "The bootloader and baseband pins alone do not identify the vendor partition our system" >&2
  echo "image is compiled against: Google ships several monthly builds on one firmware pair." >&2
  exit 2
fi

# Hash descriptor = whole physical partition (bootloader); hashtree = logical partition inside super (fastbootd).
sed -n 's/^HASH //p'     "$DESC" | LC_ALL=C sort -u > "$TMP/hash_parts"
sed -n 's/^HASHTREE //p' "$DESC" | LC_ALL=C sort -u > "$TMP/tree_parts"
sed -n 's/^CHAIN //p'    "$DESC" | LC_ALL=C sort -u > "$TMP/chain_parts"

: > "$TMP/parts"
while IFS= read -r p; do
  [ -n "$p" ] && printf '%s bootloader\n' "$p" >> "$TMP/parts"
done < "$TMP/hash_parts"
while IFS= read -r p; do
  [ -n "$p" ] && printf '%s fastbootd\n' "$p" >> "$TMP/parts"
done < "$TMP/tree_parts"

if [ ! -s "$TMP/parts" ]; then
  echo "" >&2
  echo "REFUSING: $VBMETA_IMG carries no hash or hashtree descriptor, so it pins no partition." >&2
  echo "A vbmeta that covers nothing is not a signed OS; something is wrong with the build." >&2
  exit 2
fi

IMG_ZIP="$(find "$DIR" -maxdepth 1 -type f -name 'image-*.zip' | head -1)"
: > "$TMP/unresolved"
: > "$TMP/from_imgzip"

while read -r p mode; do
  [ -n "$p" ] || continue
  case "$p" in
    bootloader|radio|modem|super|abl|bl1|bl2|bl31|gcf|gsa|gsa_bl1|ldfw|pbl|tzsw|gsc|ec|*.ec)
      echo "" >&2
      echo "REFUSING: our vbmeta pins '$p', which is Google's firmware or the whole super" >&2
      echo "container. flash_rist.sh will never write it, so a release that needs it written" >&2
      echo "cannot pass verified boot. This is a fault in how the image was signed." >&2
      exit 2 ;;
  esac
  [ -f "$DIR/$p.img" ] && continue
  if [ -n "$IMG_ZIP" ] && unzip -l "$IMG_ZIP" "$p.img" >/dev/null 2>&1; then
    if unzip -q -o -j "$IMG_ZIP" "$p.img" -d "$DIR"; then
      echo "$p" >> "$TMP/from_imgzip"
      continue
    fi
  fi
  echo "$p" >> "$TMP/unresolved"
done < "$TMP/parts"

if [ ! -f "$DIR/vbmeta.img" ]; then
  if ! cp "$VBMETA_IMG" "$DIR/vbmeta.img" || [ ! -s "$DIR/vbmeta.img" ]; then
    echo "" >&2
    echo "REFUSING: could not place vbmeta.img in the artefact, but PARTITIONS.txt lists vbmeta" >&2
    echo "as a partition to flash. Publishing that hands every downloader a release that stops" >&2
    echo "partway through flashing, with the OS partitions already written and the signature" >&2
    echo "over them missing." >&2
    exit 2
  fi
  echo "==> placed vbmeta.img ($(stat -c%s "$DIR/vbmeta.img") bytes) -- the same vbmeta its"
  echo "    descriptors were read from, so PARTITIONS.txt describes the file that ships"
fi

if [ -s "$TMP/from_imgzip" ]; then
  echo "==> took $(grep -c . "$TMP/from_imgzip") partition image(s) out of $(basename "$IMG_ZIP")"
  echo "    NOTE: that zip also carries Google's firmware as PARTITION images (abl, bl1, bl2,"
  echo "    bl31, gcf, gsa, gsa_bl1, ldfw, modem, pbl, tzsw). Only the partitions our vbmeta"
  echo "    pins were taken out of it; the zip itself is still in the artefact and is still a"
  echo "    redistribution question."
fi

SUPER_REMOVED=""
if [ -s "$TMP/unresolved" ]; then
  SPLITS="$(ls "$DIR"/super_*.img 2>/dev/null \
            | sed 's|.*/super_\([0-9][0-9]*\)\.img$|\1 &|' \
            | LC_ALL=C sort -n | cut -d' ' -f2- || true)"
  if [ -n "$SPLITS" ] && command -v simg2img >/dev/null 2>&1 && command -v lpunpack >/dev/null 2>&1; then
    echo "==> the logical partitions ship only as super_*.img splits; unpacking them"
    # shellcheck disable=SC2086
    if ! simg2img $SPLITS "$TMP/super.raw"; then
      echo "simg2img failed on the super splits (error above). Nothing written." >&2
      exit 2
    fi
    mkdir -p "$TMP/lp"
    if ! lpunpack "$TMP/super.raw" "$TMP/lp"; then
      echo "lpunpack failed on the reassembled super image (error above). Nothing written." >&2
      rm -f "$TMP/super.raw"
      exit 2
    fi
    rm -f "$TMP/super.raw"
    : > "$TMP/still_unresolved"
    while IFS= read -r p; do
      [ -n "$p" ] || continue
      got=""
      for cand in "$TMP/lp/$p.img" "$TMP/lp/${p}_a.img" "$TMP/lp/${p}_b.img"; do
        [ -f "$cand" ] && { got="$cand"; break; }
      done
      if [ -n "$got" ] && mv "$got" "$DIR/$p.img"; then
        echo "    recovered $p.img from super"
      else
        echo "$p" >> "$TMP/still_unresolved"
      fi
    done < "$TMP/unresolved"
    mv "$TMP/still_unresolved" "$TMP/unresolved"
    rm -rf "$TMP/lp"
    if [ ! -s "$TMP/unresolved" ]; then
      for s in $SPLITS; do
        SUPER_REMOVED="$SUPER_REMOVED $(basename "$s")"
        rm -f "$s"
      done
    fi
  fi
fi

if [ -s "$TMP/unresolved" ]; then
  echo "" >&2
  echo "REFUSING: this release cannot be described by a PARTITIONS.txt flash_rist.sh accepts." >&2
  echo "" >&2
  echo "Our vbmeta pins these partitions and the artefact carries no image for them:" >&2
  sed 's/^/    /' "$TMP/unresolved" >&2
  echo "" >&2
  if ls "$DIR"/super_*.img >/dev/null 2>&1; then
    echo "They are inside the super_*.img splits. That is a delivery flash_rist.sh cannot use:" >&2
    echo "its manifest has one entry shape, '<partition> <file> <mode>', flashed as" >&2
    echo "'fastboot flash <partition> <file>', and it refuses the partition name 'super' outright" >&2
    echo "because writing super wholesale rewrites the entire dynamic partition table." >&2
    echo "" >&2
    echo "Give this script simg2img and lpunpack on PATH -- both are AOSP host tools, built on the" >&2
    echo "release box -- and it unpacks them itself. By hand, from the unpacked artefact:" >&2
    echo "" >&2
    echo "    simg2img super_1.img super_2.img ... super_16.img super.raw" >&2
    echo "    lpunpack super.raw ./lp" >&2
    echo "    # then rename lp/<part>_a.img -> <part>.img beside the other images" >&2
    echo "" >&2
  fi
  echo "Nothing was written to the publish directory. A release that ships a PARTITIONS.txt" >&2
  echo "naming a file it does not contain fails on the downloader's machine instead of here." >&2
  exit 2
fi

covers="$(LC_ALL=C sort -u "$TMP/hash_parts" "$TMP/tree_parts" | tr '\n' ' ' | sed 's/ *$//')"
chains_out="$(tr '\n' ' ' < "$TMP/chain_parts" | sed 's/ *$//')"

# avb_custom_key is excluded: flash_rist.sh flashes the AVB key first and runs erase-extra after it.
# userdata and metadata are excluded because wipe=yes already covers them.
erase_extra=""
oem_uart="no"
if [ -f "$DIR/script.txt" ]; then
  erase_extra="$(sed -n 's/^erase[[:space:]][[:space:]]*//p' "$DIR/script.txt" \
                 | grep -vE '^(userdata|metadata|avb_custom_key)$' \
                 | LC_ALL=C sort -u | tr '\n' ' ' | sed 's/ *$//' || true)"
  grep -q '^run-cmd[[:space:]][[:space:]]*oem uart disable' "$DIR/script.txt" && oem_uart="yes"
fi

mkdir -p "$OUT" || { echo "could not create output directory: $OUT" >&2; exit 2; }
# Absolute path: the repack below cd's into $TMP/x, so a relative $OUT would stop resolving.
OUT="$(cd "$OUT" && pwd)"
REQ="$DIR/REQUIRED_STOCK.txt"
{
  echo "# What this device must ALREADY be running before you flash this image."
  echo "#"
  echo "# This artefact does not contain Google's bootloader or modem firmware. They are"
  echo "# already on your Pixel, and Google will give them to you directly for your own"
  echo "# device. So flash Google's stock factory image for the build below first, then"
  echo "# flash this one on top of it."
  echo "#"
  echo "# This is RistOS's FALLBACK packaging, not its normal one. Current RistOS releases"
  echo "# ship Google's firmware in the download, as GrapheneOS does, and need no stock"
  echo "# image first. If you are reading this in a current release, something is wrong:"
  echo "# say so at the project's issue tracker."
  echo "#"
  echo "# Nothing here is a security downgrade: the bootloader and modem are verified by"
  echo "# Google's own chain, not by the key this image is signed with, so leaving them in"
  echo "# place is exactly what a stock device does."
  echo "#"
  echo "device=$DEVICE_GUESS"
  echo "build-id=$build_id"
  [ -n "$bl_ver" ] && echo "version-bootloader=$bl_ver"
  [ -n "$rd_ver" ] && echo "version-baseband=$rd_ver"
  echo ""
  echo "# Check what your device currently has:"
  echo "#     adb shell getprop ro.build.id          (compare against build-id above)"
  echo "#"
  echo "# and in fastboot mode:"
  echo "#     fastboot getvar version-bootloader"
  echo "#     fastboot getvar version-baseband"
  echo "#"
  echo "# image/scripts/flash_rist.sh does that check for you and refuses to flash on a mismatch."
  echo ""
  if [ -n "$ANDROID_INFO" ]; then
    echo "# fastboot ENFORCES the following, read out of android-info.txt in the image zip:"
    printf '%s\n' "$ANDROID_INFO" | while IFS= read -r l; do echo "#     $l"; done
  else
    echo "# WARNING: the image zip carries no 'require' lines in android-info.txt, so fastboot"
    echo "# will NOT enforce any of this by itself. The check in flash_rist.sh is then the only"
    echo "# thing standing between a user and an OS flashed onto mismatched firmware."
  fi
} > "$REQ"

REMOVED=""
for f in $BL $RD $EC_FILES; do
  [ -n "$f" ] || continue
  REMOVED="$REMOVED $(basename "$f")"
  rm -f "$f"
done
for b in $SUPER_REMOVED; do
  REMOVED="$REMOVED $b"
done

for s in "$DIR"/flash-all.sh "$DIR"/flash-all.bat; do
  [ -f "$s" ] || continue
  for b in $REMOVED; do
    esc="$(printf '%s' "$b" | sed 's/[][\.*^$/]/\\&/g')"
    sed -i.bak "/$esc/d" "$s"
    rm -f "$s.bak"
  done
done
if [ -f "$DIR/flash-all.sh" ]; then
  TMPF="$(mktemp)"
  {
    head -1 "$DIR/flash-all.sh"
    cat <<'BANNER'
# NOTE: the firmware flash steps have been REMOVED from this script, because this artefact does
# not contain Google's firmware images. Your device must already be running the stock build named
# in REQUIRED_STOCK.txt. Any `fastboot update android-info.zip` line below is NOT an update -- it
# is the check that enforces exactly that, and it has deliberately been left in place.
# See image/scripts/flash_rist.sh, which runs the same check before it flashes anything.
BANNER
    tail -n +2 "$DIR/flash-all.sh"
  } > "$TMPF"
  mv "$TMPF" "$DIR/flash-all.sh"
  chmod +x "$DIR/flash-all.sh"
fi

PARTS_FILE="$DIR/PARTITIONS.txt"
{
  echo "# Every partition RistOS flashes, and nothing else."
  echo "#"
  echo "# GENERATED by image/scripts/deblob_release.sh from this release's own vbmeta.img. It is a"
  echo "# description of the artefact, not a statement about it: vbmeta-covers below is the literal"
  echo "# list of partition names in our AVB hash and hashtree descriptors."
  echo "#"
  echo "# DO NOT EDIT THIS FILE TO GET PAST A REFUSAL. It travels under SHA256SUMS, so editing it"
  echo "# breaks the download check -- and editing the statement does not change what is on the"
  echo "# phone, it only removes your warning that the phone is wrong."
  echo "#"
  echo "# Our vbmeta is FLAT: it delegates nothing to a vbmeta we do not ship, so vbmeta-chains-out"
  echo "# is empty and EVERY covered partition is flashed below. Leaving Google's copy of one of"
  echo "# them in place while flashing our vbmeta over the top is the failure that survives a"
  echo "# re-lock and cannot be undone. flash_rist.sh cross-checks the two lists and refuses."
  echo "#"
  echo "# The logical partitions (hashtree-covered: the ones flashed 'fastbootd') live inside"
  echo "# 'super' on the device. They are listed here individually and flashed individually,"
  echo "# because that is the only shape flash_rist.sh accepts -- it refuses the partition name"
  echo "# 'super' outright, since writing super wholesale rewrites the whole dynamic partition"
  echo "# table. Where a release shipped them only as super_*.img splits, deblob_release.sh"
  echo "# unpacked them back into individual images and dropped the splits."
  echo ""
  echo "slot-policy=current"
  echo "wipe=yes"
  echo "avb-custom-key=avb_pkmd.bin"
  [ -n "$rist_fp" ]     && echo "ristos-fingerprint=$rist_fp"
  [ -n "$erase_extra" ] && echo "erase-extra=$erase_extra"
  [ "$oem_uart" = "yes" ] && echo "oem-uart-disable=yes"
  echo "vbmeta-covers=$covers"
  echo "vbmeta-chains-out=$chains_out"
  echo ""
  echo "# <partition>        <file>                   <bootloader|fastbootd>"
  echo ""
  # vbmeta is not among its own descriptors; it must be listed explicitly, and first.
  printf '%-18s %-24s %s\n' vbmeta vbmeta.img bootloader
  while read -r p mode; do
    [ -n "$p" ] || continue
    printf '%-18s %-24s %s\n' "$p" "$p.img" "$mode"
  done < "$TMP/parts"
} > "$PARTS_FILE"

if [ -f "$HERE/flash_rist.sh" ]; then
  cp "$HERE/flash_rist.sh" "$DIR/flash_rist.sh"
  chmod +x "$DIR/flash_rist.sh"
fi

BASE="$(basename "$FACTORY" .zip)"
NEW="$OUT/${BASE}.zip"
rm -f "$NEW"
( cd "$TMP/x" && zip -qr "$NEW" . ) || { echo "repack failed" >&2; exit 2; }
cp "$REQ" "$OUT/REQUIRED_STOCK.txt"
cp "$PARTS_FILE" "$OUT/PARTITIONS.txt"

OTA_BLOCKED=0
for f in "$SRC"/*; do
  b="$(basename "$f")"
  case "$b" in
    *factory*.zip) continue ;;
    *target_files*.zip|*otatools*.zip)
      echo "    skipping $b -- a release-machine artefact, not part of the download"
      continue ;;
  esac
  case "$b" in
    *ota*.zip)
      echo ""
      echo "==> $b is an OTA package. Gating it before it can travel into $OUT."
      GATE="$HERE/../../tools/check_partial_ota.py"
      if [ ! -f "$GATE" ] || ! command -v python3 >/dev/null; then
        echo "BLOCKED  cannot run the OTA gate (need python3 and $GATE)." >&2
        echo "         $b is NOT copied. An unchecked OTA package is not a publishable one." >&2
        OTA_BLOCKED=1
        continue
      fi
      GARGS=()
      [ -f "$DIR/vbmeta.img" ] && GARGS+=(--vbmeta "$DIR/vbmeta.img")
      [ -n "${RIST_OTA_EXPECT_OTACERT:-}" ] && GARGS+=(--expect-otacert "$RIST_OTA_EXPECT_OTACERT")
      [ -n "${RIST_TARGET_FILES:-}" ]       && GARGS+=(--target-files "$RIST_TARGET_FILES")
      [ -n "${RIST_MIN_SPL:-}" ]            && GARGS+=(--min-security-patch "$RIST_MIN_SPL")
      # ${GARGS[@]+"${GARGS[@]}"}, not "${GARGS[@]}": bash 3.2 aborts on an empty array under set -u.
      python3 "$GATE" "$f" ${GARGS[@]+"${GARGS[@]}"}
      grc=$?
      if [ "$grc" -ne 0 ]; then
        echo ""
        echo "BLOCKED  $b did not pass tools/check_partial_ota.py (exit $grc)." >&2
        echo "         It is NOT copied into $OUT. Regenerate it with --partial -- see" >&2
        echo "         tools/check_partial_ota.py --help for the ota_from_target_files invocation --" >&2
        echo "         or publish it knowingly and separately, having read what it contains." >&2
        OTA_BLOCKED=1
        continue
      fi
      echo "    $b passed the OTA gate; copying it through."
      ;;
  esac
  [ -f "$f" ] && cp "$f" "$OUT/$b"
done

if [ -f "$HERE/flash_rist.sh" ]; then
  cp "$HERE/flash_rist.sh" "$OUT/flash_rist.sh"
  chmod +x "$OUT/flash_rist.sh"
else
  echo "WARNING  $HERE/flash_rist.sh not found -- the artefact will ship WITHOUT its firmware" >&2
  echo "         checker. Do not publish it like that: REQUIRED_STOCK.txt would then be a" >&2
  echo "         statement with nothing enforcing it beyond fastboot's own android-info check." >&2
fi

if command -v sha256sum >/dev/null 2>&1; then SHA256="sha256sum"
elif command -v shasum >/dev/null 2>&1; then SHA256="shasum -a 256"
else
  echo "neither sha256sum nor shasum found -- cannot write SHA256SUMS." >&2
  echo "The artefact in $OUT is NOT publishable without it: it is the only thing that lets" >&2
  echo "somebody who downloaded this check they got what we published." >&2
  exit 2
fi
SUMS_OK=1
( cd "$OUT" && {
    find . -maxdepth 1 -type f ! -name SHA256SUMS -print
    # The kernel source tarball and its .sha256 are published beside the image and are usually
    # symlinked into $OUT rather than copied; find -type f skips symlinks, so list them here.
    for k in kernel-source-*.tar.gz kernel-source-*.tar.gz.sha256; do
      [ -f "$k" ] && echo "./$k"
    done
  } | LC_ALL=C sort -u | while IFS= read -r f; do
    $SHA256 "$f" || exit 1
  done ) > "$OUT/SHA256SUMS" || SUMS_OK=0
N_SUMS="$(wc -l < "$OUT/SHA256SUMS" | tr -d ' ')"
if [ "$SUMS_OK" -ne 1 ] || [ "$N_SUMS" -eq 0 ]; then
  echo "" >&2
  echo "FAILED to write SHA256SUMS for $OUT ($N_SUMS lines). The errors above are the reason." >&2
  echo "Do not publish this directory. A release whose checksum file is empty is a release" >&2
  echo "nobody downloading it can verify, and the empty file looks exactly like a good one." >&2
  rm -f "$OUT/SHA256SUMS"
  exit 2
fi

echo "de-blobbed release written to $OUT"
echo "  SHA256SUMS: $N_SUMS files"
for b in $REMOVED; do echo "  removed  $b"; done
echo "  requires build-id=$build_id"
[ -n "$bl_ver" ] && echo "  requires version-bootloader=$bl_ver"
[ -n "$rd_ver" ] && echo "  requires version-baseband=$rd_ver"
echo "  PARTITIONS.txt: $(grep -c . "$TMP/parts") partitions + vbmeta"
echo "  vbmeta-covers:  $covers"
echo "  vbmeta-chains-out:${chains_out:+ $chains_out}${chains_out:- (empty -- our vbmeta delegates nothing)}"
echo ""
echo "Now, in order:"
echo "  1. bash image/scripts/check_no_blobs.sh --artefact $OUT"
echo "     That gate also refuses a pre-authorised adb key inside the image, and to see inside"
echo "     super.img it reads the <device>-target_files.zip this release was signed from. It looks"
echo "     for one beside $OUT; if it is elsewhere, name it:"
echo "        RIST_TARGET_FILES=<path> bash image/scripts/check_no_blobs.sh --artefact $OUT"
echo "     With no target_files it exits 2 UNCHECKED rather than passing."
echo "  2. IF THIS RELEASE IS TO BE PUNCHED, punch it NOW, before anything is signed:"
echo "        bash image/scripts/punch_blobs.sh --release $OUT --out ${OUT%-publish}-punched"
echo "     The punch rewrites 190,178,315 bytes inside the super images, so the SHA256SUMS this"
echo "     script just wrote does not describe the punched artefact. punch_blobs.sh regenerates"
echo "     it -- and writes SHA256SUMS.refilled, the pre-punch hashes a correct refill has to"
echo "     reproduce. Sign the checksums that come out of THAT step, not these. Signing here and"
echo "     punching afterwards ships a signature over bytes that no longer exist, and every"
echo "     downloader's first command fails on a release that is in fact perfectly good."
echo "     punch_blobs.sh refuses an input that already carries a .minisig, for that reason."
echo "     If the release is NOT punched, skip to 3."
echo "  3. sign the checksum file, or the download is only checkable for CORRUPTION and not for"
echo "     authorship -- an attacker who can replace the image can replace SHA256SUMS beside it:"
echo "        minisign -Sm $OUT/SHA256SUMS -s <your-secret-key>"
echo "     SIGN IT ON THE MACHINE THAT HOLDS THE KEY, NOT ON THE BUILD MACHINE. The release"
echo "     secret key has no business on it -- pull $OUT back first, re-check SHA256SUMS against"
echo "     the copy that arrived, and sign there."
echo "     It is a detached signature over"
echo "     the checksum file and not over each artefact, and image/INSTALL.md for what a"
echo "     downloader is told to do with it."
echo "  4. Upload the directory. The one rule no script can"
echo "     enforce: the minisign PUBLIC key must never be served from the same place as the image."

if [ "$OTA_BLOCKED" -ne 0 ]; then
  echo ""
  echo "NOTE: the factory artefact above is complete, but at least one OTA package in $SRC was" >&2
  echo "      REFUSED and is not in $OUT. Exiting 1 so that nothing downstream reads this run as" >&2
  echo "      clean." >&2
  exit 1
fi
