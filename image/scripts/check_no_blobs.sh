#!/bin/bash
# Report Google's proprietary files in an artefact, and refuse a pre-authorised adb key.
set -uo pipefail

MODE=""
TARGET=""
case "${1:-}" in
  --staged|--artefact|--ota) MODE="$1"; TARGET="${2:-}" ;;
  *) echo "usage: $0 --staged <PRODUCT_OUT> | --artefact <dir-or-zip> | --ota <ota_update.zip>" >&2; exit 2 ;;
esac
[ -n "$TARGET" ] || { echo "usage: $0 $MODE <path>" >&2; exit 2; }
[ -e "$TARGET" ] || { echo "no such path: $TARGET" >&2; exit 2; }

HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
LIST="$HERE/proprietary-files.txt"
FAIL=0
UNCHECKED=0
fail() { printf 'FAIL  %s\n' "$1"; FAIL=1; }
pass() { printf 'ok    %s\n' "$1"; }
note() { printf '      %s\n' "$1"; }
unchecked() { printf 'UNCHECKED  %s\n' "$1"; UNCHECKED=1; }

# A temp file and while-read, not mapfile: macOS bash 3.2 has no mapfile.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ "$MODE" = "--ota" ]; then
  PARTIAL="$REPO/tools/check_partial_ota.py"
  [ -f "$PARTIAL" ] || {
    echo "FAIL  $PARTIAL not found. The OTA gate cannot run, so nothing is cleared." >&2
    exit 2
  }
  command -v python3 >/dev/null || { echo "python3 not found (needed by $PARTIAL)" >&2; exit 2; }
  shift 2 2>/dev/null || shift $#
  exec python3 "$PARTIAL" "$TARGET" "$@"
fi

listing=""
N_LISTED=0
listing_what=""
if [ "$MODE" = "--artefact" ]; then
  listing_rc=0
  if [ -d "$TARGET" ]; then
    ( cd "$TARGET" && find . -type f )   > "$TMP/raw"
    listing_rc=$?
    sed 's|^\./||' < "$TMP/raw" > "$TMP/listing"
    listing_what="files under $TARGET"
  elif [ "${TARGET##*.}" = "zip" ]; then
    command -v unzip >/dev/null || { echo "unzip not found" >&2; exit 2; }
    unzip -Z1 "$TARGET" > "$TMP/listing"
    listing_rc=$?
    listing_what="entries in $(basename "$TARGET")"
  else
    echo "--artefact expects a directory or a .zip" >&2; exit 2
  fi
  if [ "$listing_rc" -ne 0 ]; then
    echo "FAIL  could not list the artefact (exit $listing_rc). The error above is the reason." >&2
    echo "      Nothing was examined, so nothing is cleared. This is not a clean artefact." >&2
    exit 2
  fi
  N_LISTED="$(wc -l < "$TMP/listing" | tr -d ' ')"
  if [ "$N_LISTED" -eq 0 ]; then
    echo "FAIL  the artefact listed ZERO $listing_what." >&2
    echo "      An empty listing is a broken read, never a clean artefact -- a release directory or" >&2
    echo "      factory zip always contains files. Refusing to certify something unexamined." >&2
    exit 2
  fi
  listing="$(cat "$TMP/listing")"
  note "artefact: $N_LISTED $listing_what"

  if printf '%s\n' "$listing" | grep -qE '^payload\.bin$'; then
    echo "FAIL  this is an OTA package, not a factory artefact." >&2
    echo "      Its partitions are inside payload.bin; a zip listing sees seven entries and none" >&2
    echo "      of the twenty-four partition images it actually ships. The firmware scan below" >&2
    echo "      would find no bootloader-*.img -- correctly, because on an OTA the bootloader" >&2
    echo "      travels as the abl/bl1/bl2/bl31/gsa/ldfw/pbl/tzsw partitions inside the payload." >&2
    echo "" >&2
    echo "        bash $0 --ota $TARGET" >&2
    echo "" >&2
    echo "      Exiting 2. This is not a pass." >&2
    exit 2
  fi
fi

adb_fatal() {
  note ""
  note "That key gives its holder an adb shell on every phone that flashes this image, with no"
  note "on-device prompt -- the kiosk blocks the prompt, so it is not a second line of defence."
  note ""
  note "This is the PRIVATE variant. The public one is a separate build:"
  note "    RIST_PUBLIC_BUILD=true, via image/scripts/sign_public.sh"
  note "Rebuild it. Do NOT delete the file out of the artefact and re-zip: the key lives inside a"
  note "partition covered by our AVB hashtree, so removing it breaks the signature -- and deleting"
  note "it from the staged tree after signing changes nothing about what was signed."
  note ""
  note "This finding on its own disqualifies the artefact, whatever else passed above."
  exit 1
}

if [ "$MODE" = "--staged" ]; then
  : > "$TMP/adbhits"
  for part in system system_ext product vendor odm oem system_dlkm vendor_dlkm; do
    [ -d "$TARGET/$part" ] || continue
    find "$TARGET/$part" -name adb_keys -type f >> "$TMP/adbhits" 2>/dev/null
  done
  # -type f is load-bearing: /adb_keys is a symlink into /product/etc/security/ in every build.
  if [ -f "$TARGET/adb_keys" ] && [ ! -L "$TARGET/adb_keys" ]; then
    echo "$TARGET/adb_keys" >> "$TMP/adbhits"
  fi
  N_ADB="$(grep -c . "$TMP/adbhits")"
  if [ "$N_ADB" -gt 0 ]; then
    fail "a pre-authorised adb key is staged into this image:"
    while IFS= read -r f; do [ -n "$f" ] && note "${f#$TARGET/}"; done < "$TMP/adbhits"
    adb_fatal
  fi
  pass "no adb_keys in any staged partition of $TARGET"
else
  ak_loose="$(printf '%s\n' "$listing" | grep -E '(^|/)adb_keys$' || true)"
  if [ -n "$ak_loose" ]; then
    fail "an adb_keys file is sitting loose in the artefact:"
    printf '%s\n' "$ak_loose" | while IFS= read -r f; do note "$f"; done
    adb_fatal
  fi

fi

prove_no_adb_key_in_image() {
  TF=""; TF_WHY=""
  if [ -n "${RIST_TARGET_FILES:-}" ]; then
    TF="$RIST_TARGET_FILES"; TF_WHY="named by RIST_TARGET_FILES"
    [ -f "$TF" ] || { echo "FAIL  RIST_TARGET_FILES=$TF is not a file." >&2; UNCHECKED=1; return 1; }
  else
    if [ -d "$TARGET" ]; then SB="$TARGET"; else SB="$(dirname "$TARGET")"; fi
    for d in "$SB" "$SB/.." "$SB/../.."; do
      [ -d "$d" ] || continue
      find "$d" -maxdepth 1 -type f -name '*target_files*.zip' 2>/dev/null | LC_ALL=C sort > "$TMP/tf"
      N_TF="$(grep -c . "$TMP/tf")"
      [ "$N_TF" -eq 0 ] && continue
      if [ "$N_TF" -gt 1 ]; then
        echo "FAIL  more than one *target_files*.zip in $d:" >&2
        sed 's/^/        /' "$TMP/tf" >&2
        echo "      Refusing to guess which one produced this artefact. Name it:" >&2
        echo "        RIST_TARGET_FILES=<path> $0 $MODE $TARGET" >&2
        UNCHECKED=1; return 1
      fi
      TF="$(cat "$TMP/tf")"; TF_WHY="found in $d"
      break
    done
  fi
  if [ -z "$TF" ]; then
    echo "UNCHECKED  cannot tell whether this artefact contains a pre-authorised adb key." >&2
    echo "      The key would live at product/etc/security/adb_keys, INSIDE super.img, which a" >&2
    echo "      zip listing cannot see. Proving it absent needs the target_files package this" >&2
    echo "      release was signed from, and none was found beside the artefact." >&2
    echo "" >&2
    echo "        RIST_TARGET_FILES=releases/<BN>/<device>-target_files.zip \\" >&2
    echo "          $0 $MODE $TARGET" >&2
    echo "" >&2
    echo "      sign_public.sh stages it at that path; keep a copy" >&2
    echo "      off the box. If it was not kept, this artefact cannot be cleared for publication." >&2
    echo "      This is not a pass -- the verdict below cannot be better than exit 2." >&2
    UNCHECKED=1; return 1
  fi
  command -v unzip >/dev/null || { echo "unzip not found (needed to read $TF)" >&2; UNCHECKED=1; return 1; }
  unzip -Z1 "$TF" > "$TMP/tflist"
  tfrc=$?
  if [ "$tfrc" -ne 0 ]; then
    echo "FAIL  could not list $TF (unzip exit $tfrc). The error above is the reason." >&2
    echo "      Nothing was examined, so nothing is cleared." >&2
    UNCHECKED=1; return 1
  fi
  N_TFE="$(grep -c . "$TMP/tflist")"
  if [ "$N_TFE" -eq 0 ]; then
    echo "FAIL  $TF listed ZERO entries. An empty listing is a broken read, never a clean image." >&2
    UNCHECKED=1; return 1
  fi
  ak_img="$(grep -E '(^|/)adb_keys$' "$TMP/tflist" || true)"
  if [ -n "$ak_img" ]; then
    CLASSIFY="$REPO/tools/classify_adb_keys.py"
    if [ ! -f "$CLASSIFY" ]; then
      echo "FAIL  $CLASSIFY is missing, so the adb_keys entries in $TF cannot be classified." >&2
      echo "      Refusing to call them harmless on the strength of their names alone." >&2
      UNCHECKED=1; return 1
    fi
    if ! python3 "$CLASSIFY" "$TF" > "$TMP/adbclass" 2>"$TMP/adbclass.err"; then
      echo "FAIL  could not classify the adb_keys entries in $TF:" >&2
      sed 's/^/        /' "$TMP/adbclass.err" >&2
      echo "      Nothing is cleared on a failed classification." >&2
      UNCHECKED=1; return 1
    fi
    if [ ! -s "$TMP/adbclass" ]; then
      echo "FAIL  the name scan matched adb_keys entries in $TF but the classifier reported none." >&2
      echo "      The two disagree, so neither is trusted." >&2
      UNCHECKED=1; return 1
    fi
    TAB="$(printf '\t')"
    if grep -q "${TAB}real${TAB}" "$TMP/adbclass"; then
      fail "a pre-authorised adb key is INSIDE the image this artefact was built from:"
      note "$TF ($TF_WHY)"
      while IFS="$TAB" read -r f k w; do
        [ "$k" = real ] && note "  $f -- $w"
      done < "$TMP/adbclass"
      adb_fatal
    fi
    while IFS="$TAB" read -r f k w; do
      [ -n "$f" ] && note "inert adb_keys entry (no key material): $f -- $w"
    done < "$TMP/adbclass"
  fi

  INCR="$(unzip -p "$TF" SYSTEM/build.prop 2>/dev/null | sed -n 's/^ro\.build\.version\.incremental=//p' | head -1 | tr -d '\r')"
  if [ -d "$TARGET" ]; then
    FZ="$(find "$TARGET" -maxdepth 1 -type f -name '*factory*.zip' 2>/dev/null | head -1)"
    [ -n "$FZ" ] && FZ="$(basename "$FZ")"
  else
    FZ="$(basename "$TARGET")"
  fi
  BNF="$(printf '%s' "$FZ" | sed -n 's/.*factory-\([0-9][0-9]*\)\.zip$/\1/p')"
  if [ -n "$INCR" ] && [ -n "$BNF" ]; then
    if [ "$INCR" != "rist.$BNF" ]; then
      echo "FAIL  that target_files does not belong to this artefact." >&2
      echo "        artefact factory zip : $FZ  (build $BNF)" >&2
      echo "        $TF: ro.build.version.incremental=$INCR" >&2
      echo "      Clearing an artefact against another build's file list proves nothing about this" >&2
      echo "      one. Point RIST_TARGET_FILES at the right package." >&2
      UNCHECKED=1; return 1
    fi
    pass "no adb_keys inside the image ($N_TFE entries in $(basename "$TF"), build $INCR)"
    note "read from $TF ($TF_WHY), bound to $FZ"
  else
    pass "no adb_keys inside the image ($N_TFE entries in $(basename "$TF"))"
    note "read from $TF ($TF_WHY)"
    note "NOT BOUND to the artefact: no build number could be read from"
    note "  ${FZ:-<no factory zip in the artefact>} and/or SYSTEM/build.prop, so the line above is"
    note "  about that target_files package and nothing ties it to the files beside it. Check by eye."
  fi
  return 0
}

[ -f "$LIST" ] || { echo "missing $LIST" >&2; exit 2; }
if ! grep -qE '^# COMPLETE: yes$' "$LIST"; then
  unchecked "image/proprietary-files.txt is not marked COMPLETE."
  note "It has never been generated from a build, so it cannot be used to clear an"
  note "artefact. On the build machine, with adevtool already run:"
  note ""
  note "  GRAPHENE_TREE=\$PWD bash image/scripts/list_google_files.sh > image/proprietary-files.txt"
  note ""
  note "then commit it. Until then this gate cannot tell you anything and says so."
  if [ "$MODE" = "--staged" ]; then
    note "In --staged mode the inventory is the whole check, so there is nothing left to run."
    exit 2
  fi
  INVENTORY_UNUSABLE=1
else
  INVENTORY_UNUSABLE=0
fi

grep -vE '^[[:space:]]*(#|$)' "$LIST" > "$TMP/entries"
N_ENTRIES="$(wc -l < "$TMP/entries" | tr -d ' ')"
if [ "$INVENTORY_UNUSABLE" -eq 0 ]; then
  [ "$N_ENTRIES" -gt 0 ] || { echo "FAIL  proprietary-files.txt is marked complete but lists nothing"; exit 2; }
  note "inventory: $N_ENTRIES Google-proprietary paths"
fi

if [ "$MODE" = "--staged" ]; then
  hits=0
  while IFS= read -r e; do
    rel="${e%%|*}"
    if [ -e "$TARGET/$rel" ]; then
      [ "$hits" -eq 0 ] && fail "Google-proprietary files present in the staged image:"
      hits=$((hits + 1))
      [ "$hits" -le 20 ] && note "$rel"
    fi
  done < "$TMP/entries"
  if [ "$hits" -gt 20 ]; then note "... and $((hits - 20)) more"; fi
  if [ "$hits" -eq 0 ]; then
    pass "no inventoried Google file in the staged image"
  else
    note ""
    note "$hits of $N_ENTRIES inventoried files are in this build."
    note "A build that boots WILL hit this: the OS cannot run without the modem, GNSS and"
    note "IMS blobs, and eSIM cannot run without EuiccGoogle/EuiccSupportPixel. This gate is"
    note "not telling you to delete them -- it is telling you this image may not be PUBLISHED"
    note "as-is. Use the punch-and-refill flow (punch_blobs.sh / refill_blobs.sh)."
  fi
  exit "$FAIL"
fi

fw="$(printf '%s\n' "$listing" | grep -E '(^|/)((bootloader|radio)-[^/]*\.img|[^/]*\.ec\.bin)$' || true)"
if [ -n "$fw" ] && [ "${RIST_SHIP_FIRMWARE:-}" = "true" ]; then
  pass "Google firmware present AND INTENDED ($(printf '%s\n' "$fw" | grep -c .) file(s), RIST_SHIP_FIRMWARE=true)"
  printf '%s\n' "$fw" | while read -r f; do note "  $f"; done
  note "These are Google's bytes, redistributed unmodified (see README.md)."
  note "To stop shipping them, unset RIST_SHIP_FIRMWARE and use the punch-and-refill fallback instead."
elif [ -n "$fw" ]; then
  fail "Google firmware images present in the artefact:"
  printf '%s\n' "$fw" | while read -r f; do note "$f"; done
  note ""
  note "If this release is MEANT to carry Google's firmware, set RIST_SHIP_FIRMWARE=true and"
  note "re-run: the gate will then list these files and pass, rather than refusing."
  note "If it is not, run image/scripts/deblob_release.sh to strip them and record what stock"
  note "build the device must already be on instead."
else
  pass "no Google firmware in $N_LISTED $listing_what (bootloader-*.img, radio-*.img, *.ec.bin)"
fi

if printf '%s\n' "$listing" | grep -qE '(^|/)REQUIRED_STOCK\.txt$'; then
  pass "REQUIRED_STOCK.txt present (states the stock build the device must already run)"
elif [ "${RIST_SHIP_FIRMWARE:-}" = "true" ]; then
  pass "no REQUIRED_STOCK.txt, and none is wanted (RIST_SHIP_FIRMWARE=true: the artefact brings its own firmware)"
else
  fail "no REQUIRED_STOCK.txt: an artefact without Google's firmware must say which build supplies it"
fi

if printf '%s\n' "$listing" | grep -qE '(^|/)flash_rist\.sh$'; then
  pass "flash_rist.sh present (enforces REQUIRED_STOCK.txt and checks get_unlock_ability)"
elif [ "${RIST_SHIP_FIRMWARE:-}" = "true" ]; then
  pass "no flash_rist.sh, and none is wanted (RIST_SHIP_FIRMWARE=true: flash-all.sh flashes this, and fastboot enforces android-info.txt)"
else
  fail "no flash_rist.sh: REQUIRED_STOCK.txt would ship as a statement with nothing enforcing it"
  note "Re-run image/scripts/deblob_release.sh, which copies it in."
fi

esc_dot() { printf '%s' "$1" | sed 's/\./\\./g'; }
artefact_path() { printf '%s\n' "$listing" | grep -E "(^|/)$(esc_dot "$1")\$" | head -1; }
artefact_cat() {
  ac_p="$(artefact_path "$1")"
  [ -n "$ac_p" ] || return 1
  if [ -d "$TARGET" ]; then cat "$TARGET/$ac_p"; else unzip -p "$TARGET" "$ac_p"; fi
}

: > "$TMP/allbasenames"
printf '%s\n' "$listing" | sed 's|.*/||' >> "$TMP/allbasenames"
nested_unreadable=""
if [ -d "$TARGET" ] && command -v unzip >/dev/null 2>&1; then
  printf '%s\n' "$listing" | grep -E '\.zip$' > "$TMP/zips" || true
  while IFS= read -r z; do
    [ -n "$z" ] || continue
    case "${z##*/}" in
      *factory*.zip|*install*.zip) ;;
      *) continue ;;
    esac
    if unzip -Z1 "$TARGET/$z" > "$TMP/nz" 2>/dev/null; then
      sed 's|.*/||' "$TMP/nz" >> "$TMP/allbasenames"
    else
      nested_unreadable="$nested_unreadable ${z##*/}"
    fi
  done < "$TMP/zips"
fi
have_name() { grep -qxF "$1" "$TMP/allbasenames"; }

if artefact_cat REQUIRED_STOCK.txt > "$TMP/req" 2>/dev/null && [ -s "$TMP/req" ]; then
  tr -d '\r' < "$TMP/req" | grep -vE '^[[:space:]]*(#|$)' > "$TMP/reqkv" || true
  rs_missing=""
  for k in device build-id version-bootloader version-baseband; do
    if [ -z "$(sed -n "s/^$(esc_dot "$k")=//p" "$TMP/reqkv" | head -1)" ]; then
      rs_missing="$rs_missing $k"
    fi
  done
  if [ -n "$rs_missing" ]; then
    fail "REQUIRED_STOCK.txt does not state:$rs_missing"
    note "flash_rist.sh:250-270 proves all four present before it touches the phone and dies"
    note "naming the ones that are absent. This artefact would refuse at its first gate."
    note "Do not add the lines by hand -- the file travels under SHA256SUMS. Fix the producer:"
    note "image/scripts/deblob_release.sh writes this file."
  else
    pass "REQUIRED_STOCK.txt states all four keys flash_rist.sh requires (device, build-id,"
    note "version-bootloader, version-baseband): build-id=$(sed -n 's/^build-id=//p' "$TMP/reqkv" | head -1)"
  fi
else
  note "REQUIRED_STOCK.txt could not be read, so its contents were not checked."
fi

if ! printf '%s\n' "$listing" | grep -qE '(^|/)PARTITIONS\.txt$' && [ "${RIST_SHIP_FIRMWARE:-}" = "true" ]; then
  pass "no PARTITIONS.txt, and none is wanted (RIST_SHIP_FIRMWARE=true: fastboot update reads the image zip's own list)"
elif ! printf '%s\n' "$listing" | grep -qE '(^|/)PARTITIONS\.txt$'; then
  fail "no PARTITIONS.txt: the release does not say which partitions it owns"
  note "flash_rist.sh:272-290 refuses outright -- 'this script will not guess'. Guessing wrong"
  note "in either direction is bad: too few partitions and the device boots a half-replaced OS,"
  note "too many and it overwrites firmware that is not ours to replace."
  note "image/scripts/deblob_release.sh generates it from the release's own vbmeta.img."
elif artefact_cat PARTITIONS.txt > "$TMP/parts_raw" 2>/dev/null && [ -s "$TMP/parts_raw" ]; then
  tr -d '\r' < "$TMP/parts_raw" | sed 's/#.*$//; s/^[[:space:]]*//; s/[[:space:]]*$//' \
    | grep -v '^$' > "$TMP/parts_lines" || true

  p_avbkey=""; p_covers=""; p_chains=""; p_slot="current"; p_bad_directive=""
  : > "$TMP/p_entries"
  : > "$TMP/p_names"
  p_badline=""
  while IFS= read -r pl; do
    [ -n "$pl" ] || continue
    case "$pl" in
      *=*)
        pk="${pl%%=*}"; pv="${pl#*=}"
        pk="$(printf '%s' "$pk" | sed 's/[[:space:]]*$//')"
        pv="$(printf '%s' "$pv" | sed 's/^[[:space:]]*//')"
        case "$pk" in
          slot-policy)        p_slot="$pv" ;;
          wipe|ristos-fingerprint|erase-extra|oem-uart-disable) ;;
          avb-custom-key)     p_avbkey="$pv" ;;
          vbmeta-covers)      p_covers="$pv" ;;
          vbmeta-chains-out)  p_chains="$pv" ;;
          *) p_bad_directive="$p_bad_directive $pk" ;;
        esac
        ;;
      *)
        # awk, not `set -- $pl`: that would clobber the script's own positional parameters.
        pf_n="$(printf '%s\n' "$pl" | awk '{print NF}')"
        pf_1="$(printf '%s\n' "$pl" | awk '{print $1}')"
        pf_2="$(printf '%s\n' "$pl" | awk '{print $2}')"
        pf_3="$(printf '%s\n' "$pl" | awk '{print $3}')"
        if [ "$pf_n" -ne 3 ]; then
          p_badline="$p_badline|$pl"
        else
          case "$pf_3" in
            bootloader|fastbootd) printf '%s %s %s\n' "$pf_1" "$pf_2" "$pf_3" >> "$TMP/p_entries"
                                  printf '%s\n' "$pf_1" >> "$TMP/p_names" ;;
            *) p_badline="$p_badline|$pl" ;;
          esac
        fi
        ;;
    esac
  done < "$TMP/parts_lines"

  if [ -n "$p_bad_directive" ]; then
    fail "PARTITIONS.txt sets a directive flash_rist.sh does not know:$p_bad_directive"
    note "An unrecognised directive is a hard refusal there (:330), not something it ignores --"
    note "it means the release was built by a newer pipeline than the flasher shipped inside it."
  fi
  if [ -n "$p_badline" ]; then
    fail "PARTITIONS.txt has a line that is neither a directive nor a valid entry:"
    printf '%s' "$p_badline" | tr '|' '\n' | while IFS= read -r bl; do
      [ -n "$bl" ] && note "$bl"
    done
    note "Entries are '<partition> <file> <bootloader|fastbootd>'."
  fi

  n_entries="$(grep -c . "$TMP/p_entries" || true)"
  if [ "$n_entries" -eq 0 ]; then
    fail "PARTITIONS.txt lists no partitions to flash"
    note "flash_rist.sh refuses: 'a flash that flashes nothing must not report success'."
  fi

  case "$p_slot" in
    current|other|all) ;;
    *) fail "PARTITIONS.txt slot-policy is '$p_slot'; flash_rist.sh accepts only current|other|all" ;;
  esac

  if [ -z "$p_avbkey" ]; then
    fail "PARTITIONS.txt does not set avb-custom-key"
    note "flash_rist.sh:420 refuses: 'a release that does not ship one can be flashed but never"
    note "safely locked, and this script will not pretend otherwise'."
  elif have_name "$p_avbkey"; then
    pass "avb-custom-key=$p_avbkey and the file is in the artefact"
  else
    fail "avb-custom-key names '$p_avbkey' and no such file is in the artefact"
  fi

  ent_missing=""
  ent_never=""
  while read -r en ef em; do
    [ -n "$en" ] || continue
    case "$en" in
      bootloader|radio|modem|super|abl|bl1|bl2|bl31|gcf|gsa|gsa_bl1|ldfw|pbl|tzsw|gsc|ec|*.ec)
        ent_never="$ent_never $en" ;;
    esac
    have_name "$ef" || ent_missing="$ent_missing $ef"
  done < "$TMP/p_entries"

  if [ -n "$ent_never" ]; then
    fail "PARTITIONS.txt asks to flash:$ent_never"
    note "Those are Google's firmware, or the whole 'super' container. flash_rist.sh:365 refuses"
    note "the release outright -- writing 'super' wholesale rewrites the entire dynamic partition"
    note "table, and the firmware is verified against a key fused into the chip. A release that"
    note "names one of these is not publishable."
  fi
  if [ -n "$ent_missing" ]; then
    fail "PARTITIONS.txt names files that are not in the artefact:$ent_missing"
    note "A release with a missing image is a release that would flash a phone halfway."
    [ -n "$nested_unreadable" ] && note "(and these nested zips could not be read:$nested_unreadable)"
  fi

  if [ -z "$p_covers" ]; then
    fail "PARTITIONS.txt states no vbmeta-covers list"
    note "flash_rist.sh then prints a NOTE and flashes anyway, and a NOTE is the check not"
    note "running. That check is what catches a keep-set which fails verified boot after"
    note "re-lock, which is the one genuinely unrecoverable outcome in this whole flow."
    note "Generate it from the release's own vbmeta:"
    note "    avbtool info_image --image vbmeta.img | sed -n 's/^ *Partition Name: *//p' | sort -u"
  else
    uncov=""
    for cov in $p_covers; do
      if grep -qxF "$cov" "$TMP/p_names"; then continue; fi
      cov_ok=0
      for ch in $p_chains; do
        [ "$ch" = "$cov" ] && { cov_ok=1; break; }
      done
      [ "$cov_ok" -eq 0 ] && uncov="$uncov $cov"
    done
    if [ -n "$uncov" ]; then
      fail "our vbmeta covers partitions this release neither flashes nor delegates:$uncov"
      note "vbmeta pins an exact hash or dm-verity root for everything it covers. Leaving"
      note "Google's copy of one of those in place while flashing our vbmeta over the top gives"
      note "a device that fails verified boot -- a reflash away while unlocked, and not"
      note "recoverable after a re-lock. flash_rist.sh:434-467 refuses this release."
    else
      pass "vbmeta coverage: all $(printf '%s' "$p_covers" | wc -w | tr -d ' ') covered partitions are flashed or delegated"
      note "vbmeta-chains-out is${p_chains:+ $p_chains}${p_chains:- empty -- this release delegates nothing}"
    fi
  fi
else
  fail "PARTITIONS.txt is present but could not be read, or is empty"
  note "An unreadable manifest is not a passed one."
fi

if printf '%s\n' "$listing" | grep -qE '(^|/)(super|image-)[^/]*\.(img|zip)$'; then
  note ""
  note "NOT CHECKED: this artefact contains packed partition images. Whether Google's files are"
  note "inside them is decided by the --staged run on the build machine, not here. If you did not"
  note "run --staged before packing, you do not know."
fi

echo ""
prove_no_adb_key_in_image

echo ""
if [ "$FAIL" -ne 0 ]; then
  echo "BLOBS FAIL -- do not publish $TARGET"
  [ "$UNCHECKED" -ne 0 ] && echo "      (and some questions above were also left unanswered)"
  exit 1
fi
if [ "$UNCHECKED" -ne 0 ]; then
  echo "BLOBS UNCHECKED -- nothing was found, but the run could not answer every question."
  echo "      The UNCHECKED lines above name which, and each one says what to supply."
  echo "      This is not a pass. Exiting 2."
  exit 2
fi
if [ "${RIST_SHIP_FIRMWARE:-}" = "true" ]; then
  echo "BLOBS OK -- $TARGET carries Google firmware BY DESIGN (RIST_SHIP_FIRMWARE=true) and no pre-authorised adb key"
else
  echo "BLOBS OK -- $TARGET carries no Google firmware and no pre-authorised adb key"
fi
exit 0
