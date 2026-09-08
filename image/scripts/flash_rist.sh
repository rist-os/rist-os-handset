#!/usr/bin/env bash
# flash_rist.sh -- put RistOS on a Pixel already running Google's stock factory build.
#
#     bash flash_rist.sh --dry-run  <release-dir>   # run every check, flash nothing
#     bash flash_rist.sh            <release-dir>   # check, then flash (WIPES THE PHONE)
#     bash flash_rist.sh --resume   <release-dir>   # continue after an interrupted flash
#     bash flash_rist.sh --relock   <release-dir>   # checked bootloader re-lock, afterwards
set -euo pipefail

if [ -z "${BASH_VERSION:-}" ]; then
  echo "This script needs bash. Run it as:  bash flash_rist.sh <release-dir>" >&2
  exit 2
fi

PROG="$(basename "$0")"

ok()   { printf 'ok        %s\n' "$1"; }
bad()  { printf 'MISMATCH  %s\n' "$1"; FAIL=1; }
say()  { printf '          %s\n' "$1"; }
info() { printf '%s\n' "$1"; }
hr()   { printf -- '------------------------------------------------------------------------\n'; }
die()  { printf '\n%s\n' "$1" >&2; exit "${2:-2}"; }

FAIL=0

MODE=flash          # flash | dry | resume | relock
ASSUME_YES=0
DIR=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run) MODE=dry ;;
    --resume)  MODE=resume ;;
    --relock)  MODE=relock ;;
    --yes|-y)  ASSUME_YES=1 ;;
    --force|--skip-check|--disable-verity|--disable-verification)
      die "Refusing: '$1' is not an option this script has.

Every one of those flags exists to switch off a check that stands between an image and a
device it was not built for. If you found one in a forum post, the post was solving a
different problem. Fix the firmware on the phone, not the check on the computer." ;;
    -h|--help)
      echo "usage: flash_rist.sh (see the script header)"
      exit 0 ;;
    -*) die "unknown option: $1" ;;
    *)
      [ -z "$DIR" ] || die "usage: $PROG [--dry-run|--resume|--relock] [--yes] <release-dir>"
      DIR="$1" ;;
  esac
  shift
done

[ -n "$DIR" ] || die "usage: $PROG [--dry-run|--resume|--relock] [--yes] <release-dir>"
[ -d "$DIR" ] || die "not a directory: $DIR"
DIR="$(cd "$DIR" && pwd)"

if [ ! -f "$DIR/REQUIRED_STOCK.txt" ]; then
  inner="$(find "$DIR" -maxdepth 2 -name REQUIRED_STOCK.txt 2>/dev/null | head -1 || true)"
  [ -n "$inner" ] && DIR="$(dirname "$inner")"
fi

REQ="$DIR/REQUIRED_STOCK.txt"
PARTS="$DIR/PARTITIONS.txt"

case " ${FASTBOOT_ARGS:-} ${FASTBOOT_OPTS:-} ${ADB_ARGS:-} " in
  *" --force "*|*" --skip-check "*|*" --disable-verity "*|*" --disable-verification "*)
    die "FASTBOOT_ARGS/FASTBOOT_OPTS contains --force, --skip-check or --disable-verit*.

Those disable the firmware requirement check and AVB verification -- the two things keeping
this image off a device it was not built for. Unset them and run again." ;;
esac

command -v fastboot >/dev/null 2>&1 || die "fastboot not found.

Install Google's own platform-tools:  https://developer.android.com/tools/releases/platform-tools
A fastboot from a Linux distribution package is often years old, and an old fastboot fails on
modern Pixels in ways that read like hardware faults."
command -v adb >/dev/null 2>&1 || die "adb not found.

It ships in the same platform-tools download as fastboot. This script needs it: the check that
your phone is on the right Google build can only be read from the running system, not from
fastboot."

# platform-tools 35.0.1 floor: below it, flashing logical partitions from fastbootd misbehaves.
FB_VER="$(fastboot --version 2>/dev/null | sed -n 's/^fastboot version \([0-9][0-9.]*\).*/\1/p' | head -1 || true)"
if [ -z "$FB_VER" ]; then
  say "WARNING: could not read the fastboot version. Make sure it is 35.0.1 or newer."
else
  fb_major="${FB_VER%%.*}"
  rest="${FB_VER#*.}"; fb_minor="${rest%%.*}"; [ "$fb_minor" = "$FB_VER" ] && fb_minor=0
  case "$fb_major" in ''|*[!0-9]*) fb_major=0 ;; esac
  case "$fb_minor" in ''|*[!0-9]*) fb_minor=0 ;; esac
  if [ "$fb_major" -lt 35 ]; then
    die "fastboot $FB_VER is too old; 35.0.1 or newer is required.

Download the current platform-tools from
  https://developer.android.com/tools/releases/platform-tools
and put it first on your PATH."
  fi
fi

[ -f "$REQ" ] || die "No REQUIRED_STOCK.txt in $DIR.

Either this is not a RistOS release, or it is one assembled without recording which Google
build it needs. Do not flash it. An OS flashed onto firmware it was not built against fails
in ways that look like hardware faults."

want_device=""; want_build=""; want_bl=""; want_bb=""
want_fp=""; want_vfp=""; want_zip=""; want_zip_sha=""; want_url=""

# `tr -d '\r'` first: the file may arrive with CRLF endings.
while IFS='=' read -r k v; do
  case "$k" in
    device)             want_device="$v" ;;
    build-id)           want_build="$v" ;;
    version-bootloader) want_bl="$v" ;;
    version-baseband)   want_bb="$v" ;;
    build-fingerprint)  want_fp="$v" ;;
    vendor-fingerprint) want_vfp="$v" ;;
    factory-zip)        want_zip="$v" ;;
    factory-zip-sha256) want_zip_sha="$v" ;;
    factory-url)        want_url="$v" ;;
  esac
done < <(tr -d '\r' < "$REQ" | grep -vE '^[[:space:]]*(#|$)' || true)

[ -n "$want_url" ] || want_url="https://developers.google.com/android/images"

missing=""
[ -n "$want_device" ] || missing="$missing device"
[ -n "$want_build" ]  || missing="$missing build-id"
[ -n "$want_bl" ]     || missing="$missing version-bootloader"
[ -n "$want_bb" ]     || missing="$missing version-baseband"
if [ -n "$missing" ]; then
  die "REFUSING TO FLASH.

$REQ does not state:$missing

Those are the values this script compares the device against. Without them there is nothing to
compare, and a check that compares nothing must not print a tick.

Either the release was assembled without recording which Google build it needs, or the file did
not parse -- check it for CRLF line endings, and read any WARNING inside it. Do not fill the
values in yourself to make the flash go through: editing this file does not change what is on
the phone, it only removes your warning that the phone is wrong."
fi

[ -f "$PARTS" ] || die "No PARTITIONS.txt in $DIR.

This release does not say which partitions it owns, and this script will not guess. Guessing
wrong in either direction is bad: too few and the device boots a half-replaced OS, too many and
it overwrites firmware that is not ours to replace.

The release pipeline must emit it. The format is documented in the header of this script
($0). A minimal example:

    slot-policy=current
    wipe=yes
    avb-custom-key=avb_pkmd.bin
    vbmeta-covers=boot dtbo init_boot pvmfw vendor_boot vendor_kernel_boot product system system_dlkm system_ext vendor vendor_dlkm

    vbmeta   vbmeta.img   bootloader
    boot     boot.img     bootloader
    system   system.img   fastbootd"

SLOT_POLICY="current"
WIPE="yes"
AVB_KEY=""
RIST_FP=""
ERASE_EXTRA=""
OEM_UART=""
VBMETA_COVERS=""
VBMETA_CHAINS_OUT=""

P_NAME=(); P_FILE=(); P_MODE=()

lineno=0
while IFS= read -r raw; do
  lineno=$((lineno + 1))
  line="$(printf '%s' "$raw" | tr -d '\r')"
  case "$line" in
    ''|'#'*|' '*'#'*) ;;
  esac
  line="${line%%#*}"
  line="$(printf '%s' "$line" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
  [ -n "$line" ] || continue

  case "$line" in
    *=*)
      key="${line%%=*}"; val="${line#*=}"
      key="$(printf '%s' "$key" | sed 's/[[:space:]]*$//')"
      val="$(printf '%s' "$val" | sed 's/^[[:space:]]*//')"
      case "$key" in
        slot-policy)       SLOT_POLICY="$val" ;;
        wipe)              WIPE="$val" ;;
        avb-custom-key)    AVB_KEY="$val" ;;
        ristos-fingerprint) RIST_FP="$val" ;;
        erase-extra)       ERASE_EXTRA="$val" ;;
        oem-uart-disable)  OEM_UART="$val" ;;
        vbmeta-covers)     VBMETA_COVERS="$val" ;;
        vbmeta-chains-out) VBMETA_CHAINS_OUT="$val" ;;
        *) die "PARTITIONS.txt line $lineno: unknown directive '$key'.

An unrecognised directive is not something to ignore. It usually means this release was built
by a newer pipeline than this script, and the safe assumption is that it wants something done
that this script does not know how to do." ;;
      esac
      ;;
    *)
      # shellcheck disable=SC2086
      set -- $line
      [ "$#" -eq 3 ] || die "PARTITIONS.txt line $lineno: expected '<partition> <file> <bootloader|fastbootd>', got:
    $line"
      case "$3" in
        bootloader|fastbootd) ;;
        *) die "PARTITIONS.txt line $lineno: mode must be 'bootloader' or 'fastbootd', got '$3'." ;;
      esac
      P_NAME[${#P_NAME[@]}]="$1"
      P_FILE[${#P_FILE[@]}]="$2"
      P_MODE[${#P_MODE[@]}]="$3"
      ;;
  esac
done < "$PARTS"

[ "${#P_NAME[@]}" -gt 0 ] || die "PARTITIONS.txt lists no partitions to flash. Refusing: a flash that
flashes nothing must not report success."

case "$SLOT_POLICY" in current|other|all) ;; *) die "slot-policy must be current, other or all (got '$SLOT_POLICY')." ;; esac

is_never_ours() {
  case "$1" in
    bootloader|radio|modem|super|abl|bl1|bl2|bl31|gcf|gsa|gsa_bl1|ldfw|pbl|tzsw|gsc|ec|*.ec) return 0 ;;
    *) return 1 ;;
  esac
}

is_google_content() {
  case "$1" in vendor|vendor_dlkm|odm|odm_dlkm) return 0 ;; *) return 1 ;; esac
}

info ""
hr
info "Release:  $DIR"
info "Checking the release before touching the phone"
hr

blob_warn=0
i=0
while [ "$i" -lt "${#P_NAME[@]}" ]; do
  pn="${P_NAME[$i]}"; pf="${P_FILE[$i]}"
  if is_never_ours "$pn"; then
    die "REFUSING: PARTITIONS.txt asks to flash '$pn'.

That partition is Google's firmware, or is the whole 'super' container. Neither is ours to
write here: the firmware is verified against a key fused into the chip and is not redistributable,
and flashing 'super' wholesale would overwrite the vendor partitions this install flow exists to
leave in place.

This release is not safe to publish or to flash. Report it before using it."
  fi
  if is_google_content "$pn"; then blob_warn=1; fi
  [ -f "$DIR/$pf" ] || die "REFUSING: PARTITIONS.txt names '$pf' for partition '$pn', and that file
is not in $DIR.

A release with a missing image is a release that would flash a phone halfway."
  [ -s "$DIR/$pf" ] || die "REFUSING: $pf is empty."
  ok "have $pf  ->  $pn ($(printf '%s' "${P_MODE[$i]}"))"
  i=$((i + 1))
done

if [ -n "$AVB_KEY" ]; then
  [ -f "$DIR/$AVB_KEY" ] || die "REFUSING: avb-custom-key names '$AVB_KEY' and it is not in $DIR.

Without it the bootloader has no key of ours to verify against, and re-locking afterwards
would lock the device to a key it does not have."
  ok "have $AVB_KEY (our AVB public key)"
else
  die "REFUSING: PARTITIONS.txt does not set avb-custom-key.

The AVB public key is what makes re-locking possible at all. A release that does not ship one
can be flashed but never safely locked, and this script will not pretend otherwise."
fi

if [ -n "$VBMETA_COVERS" ]; then
  uncovered=""
  for cov in $VBMETA_COVERS; do
    found=0
    j=0
    while [ "$j" -lt "${#P_NAME[@]}" ]; do
      [ "${P_NAME[$j]}" = "$cov" ] && { found=1; break; }
      j=$((j + 1))
    done
    if [ "$found" -eq 0 ]; then
      for chained in $VBMETA_CHAINS_OUT; do
        [ "$chained" = "$cov" ] && { found=1; break; }
      done
    fi
    [ "$found" -eq 0 ] && uncovered="$uncovered $cov"
  done
  if [ -n "$uncovered" ]; then
    die "REFUSING TO FLASH -- this release would fail verified boot.

Our vbmeta covers these partitions, but the release neither flashes them nor delegates them:
   $uncovered

vbmeta pins an exact hash for everything it covers. Leaving Google's copy of one of those in
place while flashing our vbmeta over the top produces a device that fails verified boot. While
the bootloader is unlocked that is a reflash away from fixed. After a re-lock it is not.

This is a fault in how the release was built, not something you can work around here. Either
those partitions belong in PARTITIONS.txt, or the build must move them behind a chained vbmeta
that stays Google's and list them in vbmeta-chains-out."
  fi
  ok "vbmeta coverage: every partition our vbmeta pins is either flashed or delegated"
else
  say "NOTE: PARTITIONS.txt states no vbmeta-covers list, so the coverage cross-check did not run."
  say "      That check is what catches a keep-set that would fail verified boot after re-lock."
fi

if command -v sha256sum >/dev/null 2>&1; then CHECK="sha256sum -c --ignore-missing"
elif command -v shasum >/dev/null 2>&1; then CHECK="shasum -a 256 -c --ignore-missing"
else CHECK=""; fi

sums_match() { ( cd "$DIR" && $CHECK "$1" >/dev/null 2>&1 ); }

if [ -z "$CHECK" ]; then
  say "NOTE: no sha256sum/shasum on this machine, so the checksum files were not checked here."
  if [ -f "$DIR/punch-manifest.txt" ]; then
    say "      This release is punched, and the check that would have caught an un-refilled one"
    say "      is exactly the check that could not run. Install coreutils and re-run."
  fi
elif [ -f "$DIR/punch-manifest.txt" ]; then
  if [ ! -f "$DIR/SHA256SUMS.refilled" ]; then
    die "REFUSING: $DIR contains punch-manifest.txt, so this release was published with Google's
file data removed -- but it carries no SHA256SUMS.refilled, which is the file that says what a
correct refill looks like.

Without it there is no way to tell a refilled release from an un-refilled one, and flashing an
un-refilled release produces a phone that fails dm-verity at boot. After 'fastboot flashing lock'
that is not recoverable without a wipe.

Either the release was published incorrectly, or this directory is not the one you refilled into.
Do not flash it."
  fi
  if sums_match SHA256SUMS.refilled; then
    ok "files match SHA256SUMS.refilled -- the refill is byte-for-byte the signed release"
  elif [ -f "$DIR/SHA256SUMS" ] && sums_match SHA256SUMS; then
    die "REFUSING: THIS RELEASE HAS NOT BEEN REFILLED YET.

$DIR still matches SHA256SUMS -- the checksums of the artefact as downloaded, with Google's file
data zeroed out. It does not match SHA256SUMS.refilled, which is what it must match before it
goes anywhere near a phone.

Flashing it would succeed and then fail dm-verity at first boot, and if you had re-locked the
bootloader first that is not something you can walk back without a wipe.

Refill it first (image/INSTALL.md step 5):
   bash image/scripts/extract_from_device.sh --from factory --zip <google factory zip> --out blobs
   bash image/scripts/refill_blobs.sh --release $DIR --blobs blobs --out <refilled dir>
then run this script against the refilled directory." 3
  else
    die "REFUSING: $DIR matches neither SHA256SUMS.refilled nor SHA256SUMS.

It is not the release we published and it is not a correct refill of it, so this script cannot
tell you what it is. Run image/scripts/verify_download.sh for the per-file detail. Do not flash it."
  fi
elif [ -f "$DIR/SHA256SUMS" ]; then
  if sums_match SHA256SUMS; then
    ok "files match SHA256SUMS"
  else
    die "REFUSING: one or more files in $DIR do not match SHA256SUMS.

Run image/scripts/verify_download.sh for the details, and do not flash this download."
  fi
else
  say "NOTE: no SHA256SUMS in the release directory -- integrity was not checked."
fi

if [ "$blob_warn" -eq 1 ]; then
  info ""
  say "NOTE: this release still flashes a vendor partition, which contains Google's files."
  say "      It is safe to flash on your own phone; it is not"
  say "      the fully de-blobbed artefact, and it should not be redistributed."
fi

SERIAL=""

# An if rather than ${SERIAL:+-s "$SERIAL"}, which word-splits.
fb_raw() {
  if [ -n "$SERIAL" ]; then fastboot -s "$SERIAL" "$@"; else fastboot "$@"; fi
}
adb_raw() {
  if [ -n "$SERIAL" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi
}

# `fastboot getvar` writes the value to STDERR; stderr is merged in deliberately.
# Sets globals rather than printing: a status set inside command substitution cannot come back.
GOT=""
GETVAR_ERR=""
getvar() {
  local name="$1" raw rc
  set +e
  raw="$(fb_raw getvar "$name" 2>&1)"
  rc=$?
  set -e
  GOT="$(printf '%s\n' "$raw" | sed -n "s/^$name: *//p" | head -1 | tr -d '\r')"
  if [ "$rc" -ne 0 ]; then
    GETVAR_ERR="fastboot exited $rc: $(printf '%s' "$raw" | tr '\n' ' ' | cut -c1-160)"
  elif [ -z "$GOT" ]; then
    GETVAR_ERR="fastboot returned no '$name' value: $(printf '%s' "$raw" | tr '\n' ' ' | cut -c1-160)"
  else
    GETVAR_ERR=""
  fi
}

# Case-insensitive: android-info.txt and the image filenames disagree on case.
lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }
same()  { [ "$(lower "$1")" = "$(lower "$2")" ]; }

check_var() {   # <fastboot variable> <expected value>
  local name="$1" want="$2"
  getvar "$name"
  if [ -n "$GETVAR_ERR" ]; then
    bad "$name: could not read it from the device."
    say "$GETVAR_ERR"
    say "This is NOT a pass. An unreadable device and a matching device are different outcomes,"
    say "and this script will not print the same line for both."
    return 0
  fi
  if ! same "$GOT" "$want"; then
    bad "$name: device has '$GOT', release needs '$want'"
    return 0
  fi
  ok "$name: $GOT"
  return 0
}

wait_for_fastboot() {   # <seconds>
  local deadline=$(( $(date +%s) + ${1:-90} )) n
  while [ "$(date +%s)" -lt "$deadline" ]; do
    n="$(fastboot devices 2>/dev/null | grep -c fastboot || true)"
    [ "$n" -ge 1 ] && return 0
    sleep 2
  done
  return 1
}

wait_for_fastbootd() {  # <seconds> -- userspace fastboot
  local deadline=$(( $(date +%s) + ${1:-90} ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    getvar is-userspace
    [ "$GOT" = "yes" ] && return 0
    sleep 2
  done
  return 1
}

one_fastboot_device() {
  local n
  n="$(fastboot devices 2>/dev/null | grep -c fastboot || true)"
  if [ "$n" -eq 0 ]; then
    die "No device in fastboot mode.

Power the phone off, hold Volume-Down, then plug the USB cable in.
If it still does not appear: try a different cable. A charge-only cable is the most common
cause and the most confusing one."
  elif [ "$n" -gt 1 ]; then
    die "More than one device is in fastboot mode. Unplug all but the one you mean."
  fi
  SERIAL="$(fastboot devices 2>/dev/null | grep fastboot | head -1 | awk '{print $1}')"
}

ADB_CONTEXT=stock

one_adb_device() {
  local lines n state
  lines="$(adb devices 2>/dev/null | sed -n '2,$p' | grep -v '^[[:space:]]*$' || true)"
  n="$(printf '%s\n' "$lines" | grep -c . || true)"
  if [ "$n" -eq 0 ] && [ "$ADB_CONTEXT" = "ristos" ]; then
    die "No device visible to adb.

--relock needs to see the phone running RistOS before it locks the bootloader to our key. That is
the entire safety property: locking a device that cannot boot is the one unrecoverable mistake in
this process.

Turn USB debugging on in RistOS (Settings -> About phone -> tap 'Build number' seven times, then
Developer options -> USB debugging) and run this again.

If you cannot enable it, do NOT lock blindly. Check the flag yourself, in fastboot, and only lock
if the phone has actually booted RistOS all the way to the kiosk:

    fastboot flashing get_unlock_ability     # must print 1
    fastboot flashing lock"
  fi
  if [ "$n" -eq 0 ]; then
    die "No device visible to adb.

The phone must be booted into Google's stock Android, with USB debugging on:

  Settings -> About phone -> tap 'Build number' seven times
  Settings -> System -> Developer options -> enable 'USB debugging'
  then accept the 'Allow USB debugging?' prompt on the phone's screen.

You do not need to sign in to anything or finish setup. Skip every screen you can.

Why this is required: fastboot can read the bootloader and modem versions but not the OS build,
and the OS build is what this image is compiled against. There is no way to check it from
fastboot, and this script does not have a mode that skips the check."
  elif [ "$n" -gt 1 ]; then
    die "More than one device is visible to adb. Unplug all but the one you mean."
  fi
  state="$(printf '%s\n' "$lines" | head -1 | awk '{print $2}')"
  SERIAL="$(printf '%s\n' "$lines" | head -1 | awk '{print $1}')"
  case "$state" in
    device) ;;
    unauthorized)
      die "The phone is connected but has not authorised this computer.

Look at the phone's screen: there should be an 'Allow USB debugging?' prompt. Accept it, then
run this again." ;;
    *)
      die "adb reports the device in state '$state', which is not usable. Unplug, replug, and
make sure the phone is fully booted into Android." ;;
  esac
}

getprop() {  # <prop> -> stdout, empty on failure
  adb_raw shell getprop "$1" 2>/dev/null | tr -d '\r\n' || true
}

STATE_DIR="${RIST_STATE_DIR:-$HOME/.ristos}"
RECEIPT_MAX_AGE=21600      # 6 hours
receipt_path() { printf '%s/stock-%s.ok' "$STATE_DIR" "$(printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_')"; }

write_receipt() {  # <serial> <build-id> <fingerprint>
  mkdir -p "$STATE_DIR"
  chmod 700 "$STATE_DIR" 2>/dev/null || true
  {
    echo "serial=$1"
    echo "build-id=$2"
    echo "fingerprint=$3"
    echo "release=$(basename "$DIR")"
    echo "at=$(date +%s)"
  } > "$(receipt_path "$1")"
}

# sets RECEIPT_WHY on failure
read_receipt() {   # <serial>
  local f age r_build r_rel r_at
  f="$(receipt_path "$1")"
  RECEIPT_WHY=""
  [ -f "$f" ] || { RECEIPT_WHY="no stock-verification receipt for device $1"; return 1; }
  r_build="$(sed -n 's/^build-id=//p' "$f" | head -1)"
  r_rel="$(sed -n 's/^release=//p' "$f" | head -1)"
  r_at="$(sed -n 's/^at=//p' "$f" | head -1)"
  case "$r_at" in ''|*[!0-9]*) RECEIPT_WHY="receipt is unreadable"; return 1 ;; esac
  age=$(( $(date +%s) - r_at ))
  [ "$age" -ge 0 ] || { RECEIPT_WHY="receipt is dated in the future"; return 1; }
  [ "$age" -le "$RECEIPT_MAX_AGE" ] || { RECEIPT_WHY="receipt is $((age / 3600))h old (limit $((RECEIPT_MAX_AGE / 3600))h)"; return 1; }
  [ "$r_rel" = "$(basename "$DIR")" ] || { RECEIPT_WHY="receipt was written for release '$r_rel', not '$(basename "$DIR")'"; return 1; }
  same "$r_build" "$want_build" || { RECEIPT_WHY="receipt records build '$r_build', release needs '$want_build'"; return 1; }
  return 0
}

check_stock_over_adb() {
  info ""
  hr
  info "Checking the phone is on Google's build $want_build"
  hr

  one_adb_device
  ok "device serial: $SERIAL"

  local dev_build dev_fp dev_vfp dev_device
  dev_build="$(getprop ro.build.id)"
  dev_fp="$(getprop ro.build.fingerprint)"
  dev_vfp="$(getprop ro.vendor.build.fingerprint)"
  dev_device="$(getprop ro.product.device)"
  [ -n "$dev_device" ] || dev_device="$(getprop ro.product.vendor.device)"

  if [ -z "$dev_build" ]; then
    die "Could not read ro.build.id from the phone.

adb sees the device but the property came back empty. That is not a pass: it usually means the
phone is in recovery, or in the middle of booting, or the shell is restricted. Boot fully into
Android and try again."
  fi

  if [ -n "$dev_device" ] && ! same "$dev_device" "$want_device"; then
    die "WRONG DEVICE.

This phone reports itself as '$dev_device'. This release is for '$want_device' only.

RistOS is built and validated for one device. Nothing here works on another Pixel model, and
flashing it there would not produce a working phone."
  fi
  [ -n "$dev_device" ] && ok "device: $dev_device"

  if ! same "$dev_build" "$want_build"; then
    hr
    info "REFUSING TO FLASH -- the phone is on the wrong Google build."
    info ""
    info "  the phone has:      $dev_build"
    info "  this release needs: $want_build"
    info ""
    info "Our system image is compiled against that exact Google build. Against a different one it"
    info "does not merely look wrong -- it does not boot, or it boots with a dead modem."
    info ""
    info "Fix it like this:"
    info ""
    info "  1. Download Google's factory image for build $want_build from"
    info "     $want_url"
    if [ -n "$want_zip" ]; then
      info "     The file is named:  $want_zip"
    fi
    if [ -n "$want_zip_sha" ]; then
      info "     sha256:             $want_zip_sha"
    fi
    info "  2. Unzip it and run its flash-all.sh. That wipes the phone and puts Google's"
    info "     firmware and Google's Android on it."
    info "  3. Boot it, turn USB debugging back on, and run this script again."
    info ""
    die "" 1
  fi
  ok "ro.build.id: $dev_build"

  if [ -n "$want_fp" ]; then
    if [ -z "$dev_fp" ]; then
      die "REFUSING: this release pins build-fingerprint but ro.build.fingerprint came back empty.
That is an unread check, not a passed one."
    fi
    same "$dev_fp" "$want_fp" || die "REFUSING TO FLASH -- build fingerprint mismatch.

  the phone has:      $dev_fp
  this release needs: $want_fp

The build ID matched but the full fingerprint did not, which means this is a different build of
the same release -- a carrier variant, a re-spin, or an image from somewhere other than Google.
Flash Google's own factory image for $want_build from $want_url and try again." 1
    ok "ro.build.fingerprint matches"
  fi

  if [ -n "$want_vfp" ]; then
    if [ -z "$dev_vfp" ]; then
      die "REFUSING: this release pins vendor-fingerprint but ro.vendor.build.fingerprint came back
empty. That is an unread check, not a passed one."
    fi
    same "$dev_vfp" "$want_vfp" || die "REFUSING TO FLASH -- VENDOR fingerprint mismatch.

  the phone has:      $dev_vfp
  this release needs: $want_vfp

The vendor partition is the one our system image actually has to agree with -- it carries the
hardware drivers, the modem interface and the kernel modules. This is the mismatch that produces
a phone with no cellular service.

Flash Google's factory image for $want_build from $want_url and try again." 1
    ok "ro.vendor.build.fingerprint matches"
  fi

  write_receipt "$SERIAL" "$dev_build" "$dev_fp"
  ok "stock build verified; receipt written to $(receipt_path "$SERIAL")"
}

check_fastboot_side() {
  info ""
  hr
  info "Checking the bootloader"
  hr

  one_fastboot_device
  ok "device serial: $SERIAL"

  # fastbootd cannot `erase avb_custom_key`, `oem uart disable` or bootloader-only getvars; return to the bootloader first.
  getvar is-userspace
  if [ "$GOT" = "yes" ]; then
    info "The phone is in userspace fastboot (fastbootd). Rebooting to the bootloader ..."
    fb_soft reboot bootloader
    SERIAL=""
    wait_for_fastboot 120 || die "The phone did not come back in bootloader fastboot within two minutes.

Power it off (hold Power for 30 seconds), hold Volume-Down, plug the cable in, and try again."
    one_fastboot_device
    getvar is-userspace
    if [ "$GOT" = "yes" ]; then
      die "The phone is still in userspace fastboot after being asked to reboot to the bootloader.

Refusing to continue: several of the steps below only exist in the real bootloader, and running
them here would fail partway through. Reboot the phone to the bootloader by hand (power off, hold
Volume-Down, plug in) and re-run with --resume."
    fi
    ok "back in bootloader fastboot ($SERIAL)"
  fi

  getvar product
  if [ -n "$GETVAR_ERR" ]; then
    die "Could not read 'product' from the bootloader.
$GETVAR_ERR
This is not a pass. Check the cable and that the phone is really in fastboot mode."
  fi
  if ! same "$GOT" "$want_device"; then
    die "WRONG DEVICE.

The bootloader reports '$GOT'. This release is for '$want_device' only.
Nothing further was checked and nothing was flashed."
  fi
  ok "product: $GOT"

  getvar slot-count
  if [ "$GOT" != "2" ]; then
    bad "slot-count: expected 2, device says '${GOT:-unreadable}'"
    say "This flow assumes an A/B device. Something is wrong with the phone or the bootloader."
  else
    ok "slot-count: 2"
  fi

  check_var version-bootloader "$want_bl"
  got_bl="$GOT"
  check_var version-baseband "$want_bb"
  got_bb="$GOT"

  getvar unlocked
  if [ -n "$GETVAR_ERR" ]; then
    bad "unlocked: could not read the lock state from the device."
    say "$GETVAR_ERR"
  elif [ "$GOT" != "yes" ]; then
    bad "the bootloader is LOCKED (unlocked=$GOT)."
    say "Enable OEM unlocking in Developer options, then run:  fastboot flashing unlock"
    say "That erases the phone. There is no version of it that does not."
  else
    ok "bootloader unlocked"
  fi

  # Must be checked BEFORE flashing: with get_unlock_ability at 0 a failed re-lock cannot be undone.
  set +e
  ua_raw="$(fb_raw flashing get_unlock_ability 2>&1)"
  set -e
  # fastboot prefixes bootloader replies with "(bootloader) "; strip it before matching.
  ua="$(printf '%s\n' "$ua_raw" | sed 's/^(bootloader)[[:space:]]*//' \
        | sed -n 's/^get_unlock_ability:[[:space:]]*//p' | head -1 | tr -d '\r')"
  if [ "$ua" != "1" ]; then
    bad "flashing get_unlock_ability = '${ua:-unreadable}'; it must be 1."
    say "That flag is the whole recovery path. With it at 0, a re-locked device that fails"
    say "verified boot cannot be unlocked again to repair it. Refusing to go further."
  else
    ok "flashing get_unlock_ability = 1 (a re-lock would still be recoverable)"
  fi

  getvar current-slot
  if [ -n "$GETVAR_ERR" ] || [ -z "$GOT" ]; then
    bad "current-slot: could not read which slot is active."
    say "${GETVAR_ERR:-empty value}"
    CURRENT_SLOT=""
  else
    CURRENT_SLOT="$GOT"
    ok "current-slot: $CURRENT_SLOT"
  fi

  getvar snapshot-update-status
  SNAPSHOT="$GOT"
  if [ -n "$SNAPSHOT" ] && [ "$SNAPSHOT" != "none" ]; then
    say "NOTE: snapshot-update-status is '$SNAPSHOT'; it will be cancelled before flashing."
  fi

  if [ "$FAIL" -ne 0 ]; then
    info ""
    info "REFUSING TO FLASH. Nothing has been written to the phone."
    info ""
    if ! same "$got_bl" "$want_bl" || ! same "$got_bb" "$want_bb"; then
      info "Your bootloader or modem firmware is not the build this image was made for. This"
      info "download does not contain Google's bootloader or modem -- it expects yours to already"
      info "be the right ones. Fix that first:"
      info ""
      info "  1. Download Google's factory image for build $want_build from"
      info "     $want_url"
      [ -n "$want_zip" ] && info "     The file is named:  $want_zip"
      info "     Its bootloader must be $want_bl"
      info "     and its baseband     $want_bb"
      info "  2. Run its flash-all.sh."
      info "  3. Boot it, re-enable USB debugging, and run this script again."
      info ""
    fi
    exit 1
  fi
}

FLASH_STARTED=0
FLASH_DONE=0

on_exit() {
  local rc=$?
  if [ "$FLASH_STARTED" -eq 1 ] && [ "$FLASH_DONE" -eq 0 ]; then
    cat <<EOF

########################################################################
#  THE PHONE IS HALF-FLASHED. READ THIS BEFORE UNPLUGGING ANYTHING.    #
########################################################################

Something failed partway through writing to the device. The phone is very
probably not bootable right now. That is recoverable, and here is exactly how.

DO NOT RUN 'fastboot flashing lock'. Not now, not to "reset" it. Re-locking a
device that cannot pass verified boot is the one mistake in this whole process
with no cheap way back.

Your bootloader is still unlocked, which means every door is still open.

Option 1 -- try again. The flash is safe to repeat:

    bash $PROG --resume "$DIR"

Option 2 -- go back to stock Android and start over:

    1. Download Google's factory image for $want_build from
       $want_url
    2. Unzip it, then run:   bash flash-all.sh
    3. That returns the phone to a normal, working, Google Pixel.

If fastboot no longer sees the phone: hold Power for 30 seconds to force it off,
then hold Volume-Down and plug the cable back in.

EOF
  fi
  exit "$rc"
}
trap on_exit EXIT

fb() {
  printf '  + fastboot %s\n' "$*"
  fb_raw "$@"
}

fb_soft() {
  printf '  + fastboot %s\n' "$*"
  if ! fb_raw "$@"; then
    say "(that step failed and is not fatal; continuing)"
  fi
}

slot_args() {
  case "$SLOT_POLICY" in
    current) printf '' ;;
    other)   printf -- '--slot=other' ;;
    all)     printf -- '--slot=all' ;;
  esac
}

do_flash() {
  info ""
  hr
  info "Flashing"
  hr
  info ""
  info "This erases the phone. Everything on it. There is no partial version of this step."
  info ""
  info "  release:        $(basename "$DIR")"
  info "  device:         $want_device ($SERIAL)"
  info "  stock build:    $want_build  (verified)"
  info "  active slot:    ${CURRENT_SLOT:-unknown}"
  info "  slot policy:    $SLOT_POLICY"
  info "  partitions:     ${#P_NAME[@]}"
  info ""
  info "Partitions NOT touched, and left exactly as Google put them:"
  info "  the bootloader, the modem/radio, the GSC (security chip) firmware, and any partition"
  info "  this release delegates:${VBMETA_CHAINS_OUT:+ $VBMETA_CHAINS_OUT}"
  info ""

  if [ "$ASSUME_YES" -ne 1 ]; then
    printf 'Type WIPE and press enter to continue, or anything else to stop: '
    read -r answer || answer=""
    if [ "$answer" != "WIPE" ]; then
      info "Stopped. Nothing was written to the phone."
      exit 0
    fi
  fi

  FLASH_STARTED=1

  if [ -n "$SNAPSHOT" ] && [ "$SNAPSHOT" != "none" ]; then
    fb_soft snapshot-update cancel
  fi

  # The AVB key must go on before the images.
  info ""
  info "Installing our AVB public key ..."
  fb_soft erase avb_custom_key
  fb flash avb_custom_key "$DIR/$AVB_KEY"

  if [ "$(lower "${OEM_UART:-}")" = "yes" ]; then
    fb_soft oem uart disable
  fi

  for e in $ERASE_EXTRA; do
    if is_never_ours "$e"; then
      die "REFUSING: erase-extra names '$e', which is firmware. Not ours to erase."
    fi
    fb_soft erase "$e"
  done

  info ""
  info "Flashing the partitions that must be written from the bootloader ..."
  i=0
  while [ "$i" -lt "${#P_NAME[@]}" ]; do
    if [ "${P_MODE[$i]}" = "bootloader" ]; then
      # shellcheck disable=SC2046
      fb $(slot_args) flash "${P_NAME[$i]}" "$DIR/${P_FILE[$i]}"
    fi
    i=$((i + 1))
  done

  if [ "$(lower "$WIPE")" = "yes" ]; then
    info ""
    info "Erasing user data ..."
    # The new OS uses different FBE/metadata encryption keys; userdata and metadata must be wiped.
    fb_soft erase userdata
    fb_soft erase metadata
  fi

  # Dynamic partitions can only be written from fastbootd. Never run `wipe-super` / `update-super`: it would delete the vendor partitions.
  need_fastbootd=0
  i=0
  while [ "$i" -lt "${#P_NAME[@]}" ]; do
    [ "${P_MODE[$i]}" = "fastbootd" ] && need_fastbootd=1
    i=$((i + 1))
  done

  if [ "$need_fastbootd" -eq 1 ]; then
    info ""
    info "Rebooting into userspace fastboot (the phone will show 'fastbootd') ..."
    fb_soft reboot fastboot
    if ! wait_for_fastbootd 120; then
      die "The phone did not come back in userspace fastboot within two minutes.

Nothing further was flashed. The phone is part-way through: see the recovery notes that follow."
    fi
    ok "in fastbootd"

    getvar current-slot
    if [ -n "$GOT" ] && [ -n "$CURRENT_SLOT" ] && [ "$GOT" != "$CURRENT_SLOT" ]; then
      die "The active slot changed from '$CURRENT_SLOT' to '$GOT' during the flash.

Refusing to continue: half the partitions would land in one slot and half in the other, which
produces a device that cannot boot either. Re-run with --resume."
    fi

    info ""
    info "Flashing the dynamic partitions ..."
    i=0
    while [ "$i" -lt "${#P_NAME[@]}" ]; do
      if [ "${P_MODE[$i]}" = "fastbootd" ]; then
        # shellcheck disable=SC2046
        fb $(slot_args) flash "${P_NAME[$i]}" "$DIR/${P_FILE[$i]}"
      fi
      i=$((i + 1))
    done
  fi

  FLASH_DONE=1

  info ""
  hr
  info "Flashed."
  hr
  cat <<EOF

Two things remain, and they are deliberately NOT automated.

1. BOOT IT, AND CHECK THAT IT COMES ALL THE WAY UP.

   Unplug and press power, or run:  fastboot reboot

   The first boot takes several minutes. It provisions the Rist assistant and comes up in the
   kiosk. Do not skip this and do not do it in a hurry -- re-locking on top of an OS that does
   not boot is the one mistake here with no cheap way back.

2. ONLY THEN, RE-LOCK:

       bash $PROG --relock "$DIR"

   That re-runs the safety checks (get_unlock_ability, that the phone is actually running
   RistOS) and then locks. If you would rather do it by hand, the command is:

       fastboot flashing lock

   Re-locking erases the phone AGAIN. That is normal and unavoidable; it is the same protection
   that stops somebody else locking your phone to their key.

   Afterwards the boot screen shows YELLOW with a key fingerprint. Yellow is correct. It means
   the bootloader is locked and booting only an OS signed by a key that is not Google's. Green
   would mean Google's key -- which is exactly what was replaced.

EOF
}

do_relock() {
  info ""
  hr
  info "Re-locking the bootloader"
  hr

  ADB_CONTEXT=ristos
  one_adb_device
  ok "device serial: $SERIAL (booted, adb reachable)"

  rl_device="$(getprop ro.product.device)"
  [ -n "$rl_device" ] || rl_device="$(getprop ro.product.vendor.device)"
  if [ -n "$rl_device" ] && ! same "$rl_device" "$want_device"; then
    die "WRONG DEVICE.

This phone reports itself as '$rl_device'. This release is for '$want_device' only.
Nothing was done."
  fi
  [ -n "$rl_device" ] && ok "device: $rl_device"

  fp="$(getprop ro.build.fingerprint)"
  vbs="$(getprop ro.boot.verifiedbootstate)"
  [ -n "$fp" ] || die "Could not read ro.build.fingerprint. Boot the phone fully and try again."
  info "  running: $fp"

  if [ -n "$RIST_FP" ]; then
    same "$fp" "$RIST_FP" || die "REFUSING TO LOCK.

The phone is running:  $fp
This release is:       $RIST_FP

Locking the bootloader binds the device to our AVB key. Doing that while it is running some
other OS is how a phone becomes unrecoverable. Flash this release first."
    ok "the phone is running this release"
  else
    say "NOTE: this release states no ristos-fingerprint, so the running OS could not be matched"
    say "      against it. Confirm yourself that the phone booted into RistOS before continuing."
  fi

  if [ -n "$vbs" ] && [ "$vbs" != "orange" ]; then
    say "NOTE: verifiedbootstate is '$vbs' (expected 'orange' while unlocked)."
  fi

  info ""
  info "Rebooting to the bootloader ..."
  adb_raw reboot bootloader || die "adb reboot bootloader failed."
  SERIAL=""
  wait_for_fastboot 120 || die "The phone did not come back in fastboot mode."
  one_fastboot_device

  getvar product
  same "${GOT:-}" "$want_device" || die "WRONG DEVICE: bootloader reports '${GOT:-unreadable}'."
  ok "product: $GOT"

  getvar unlocked
  if [ "$GOT" = "no" ]; then
    info ""
    info "The bootloader is already locked. Nothing to do."
    exit 0
  fi

  set +e
  ua_raw="$(fb_raw flashing get_unlock_ability 2>&1)"
  set -e
  # fastboot prefixes bootloader replies with "(bootloader) "; strip it before matching.
  ua="$(printf '%s\n' "$ua_raw" | sed 's/^(bootloader)[[:space:]]*//' \
        | sed -n 's/^get_unlock_ability:[[:space:]]*//p' | head -1 | tr -d '\r')"
  [ "$ua" = "1" ] || die "REFUSING TO LOCK.

flashing get_unlock_ability = '${ua:-unreadable}'. It must be 1.

With it at 0, a locked device that fails verified boot cannot be unlocked again to repair it.
Locking now would be a one-way door with no handle on the other side."
  ok "flashing get_unlock_ability = 1"

  info ""
  info "Locking will ERASE THE PHONE AGAIN. That is normal: it is the same protection that stops"
  info "somebody else locking your phone to their key. Afterwards the boot screen will be YELLOW"
  info "with a key fingerprint, which is the correct end state for a self-signed OS."
  info ""
  if [ "$ASSUME_YES" -ne 1 ]; then
    printf 'Type LOCK and press enter to lock the bootloader, or anything else to stop: '
    read -r answer || answer=""
    [ "$answer" = "LOCK" ] || { info "Stopped. The bootloader is still unlocked."; exit 0; }
  fi

  fb flashing lock
  info ""
  info "Locked. Confirm on the phone's own screen -- it asks with the volume and power keys."
  info "The next boot shows the yellow warning screen and your key's fingerprint. Compare that"
  info "fingerprint against the one published with this release: it is computed by the bootloader"
  info "from the image actually on the device, so it holds even if the download was substituted."
}

case "$MODE" in
  relock)
    do_relock
    ;;

  resume)
    one_fastboot_device
    if ! read_receipt "$SERIAL"; then
      die "REFUSING to resume: $RECEIPT_WHY

--resume only skips the stock-build check when THIS script has already run that check, on THIS
phone, for THIS release, recently. It is a way to continue an interrupted flash, not a way
around the check.

If the phone still boots into Google's stock Android, run without --resume.
If it does not boot at all, put stock back first -- your bootloader is still unlocked:

    Download Google's factory image for $want_build from
    $want_url
    unzip it, and run:  bash flash-all.sh

then start again."
    fi
    ok "resuming on a stock-verification receipt for $SERIAL (build $want_build)"
    check_fastboot_side
    do_flash
    ;;

  dry|flash)
    n_fb="$(fastboot devices 2>/dev/null | grep -c fastboot || true)"
    if [ "$n_fb" -ge 1 ]; then
      one_fastboot_device
      if read_receipt "$SERIAL"; then
        ok "the phone is already in fastboot, and a recent stock-verification receipt exists"
        say "($(receipt_path "$SERIAL"))"
      else
        die "The phone is in fastboot mode, and this script has not verified which Google build
it is running. It cannot: fastboot does not report the OS build.

  $RECEIPT_WHY

Boot the phone into Google's stock Android, turn USB debugging on, and run this again. The
script will do the check and reboot the phone to fastboot itself.

    fastboot reboot

If the phone no longer boots -- because a previous flash was interrupted -- your bootloader is
still unlocked, so nothing is lost. Put Google's factory image for $want_build back on it
($want_url), then start over."
      fi
    else
      check_stock_over_adb
      info ""
      info "Rebooting the phone into fastboot ..."
      adb_raw reboot bootloader || die "adb reboot bootloader failed. Reboot into
the bootloader by hand: power off, hold Volume-Down, plug the cable in. Then re-run with --resume."
      saved_serial="$SERIAL"
      SERIAL=""
      wait_for_fastboot 120 || die "The phone did not appear in fastboot mode within two minutes.

Nothing has been flashed. Power the phone off (hold Power for 30 seconds), hold Volume-Down,
plug the cable in, and re-run with --resume."
      one_fastboot_device
      if [ "$SERIAL" != "$saved_serial" ]; then
        die "The device serial changed from '$saved_serial' to '$SERIAL' between adb and fastboot.

Refusing to continue -- that means more than one phone is involved, and the build check was
performed on a different one from the one about to be flashed."
      fi
    fi

    check_fastboot_side

    if [ "$MODE" = "dry" ]; then
      info ""
      hr
      info "--dry-run: every check passed. Nothing was flashed."
      hr
      info ""
      info "Run the same command without --dry-run to flash. It will erase the phone."
      exit 0
    fi

    do_flash
    ;;
esac
