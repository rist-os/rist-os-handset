#!/bin/bash
# Shared setup for the image build/sign scripts. Source this first; do not run it.

: "${GRAPHENE_TREE:?set GRAPHENE_TREE to the root of your synced GrapheneOS tree}"
[ -f "$GRAPHENE_TREE/build/envsetup.sh" ] || {
  echo "GRAPHENE_TREE=$GRAPHENE_TREE does not look like an AOSP tree (no build/envsetup.sh)" >&2
  exit 2
}
: "${BN:?set BN to the build number, e.g. BN=2026071900}"

DEVICE="${DEVICE:-stallion}"

RIST_BUILD_TAG="${RIST_BUILD_TAG:-rist}"
# Must be exported here, before m: soong_ui writes build_number.txt before any product makefile is read.
# An inherited BUILD_NUMBER is ignored on purpose: build/envsetup.sh exports a bare date stamp.
BUILD_NUMBER="${RIST_BUILD_NUMBER:-${RIST_BUILD_TAG}.${BN}}"

rist_check_build_number() {
  case "$BUILD_NUMBER" in
    # a space breaks BUILD_FINGERPRINT (config.mk) and the AVB argument split in sign_target_files_apks.py
    *[[:space:]]*) echo "BUILD_NUMBER must not contain whitespace: '$BUILD_NUMBER'" >&2; exit 2 ;;
    # sign_target_files_apks.py splits the fingerprint on '/' and rewrites the LAST field as the tags
    */*|*:*)       echo "BUILD_NUMBER must not contain '/' or ':' (fingerprint separators): '$BUILD_NUMBER'" >&2; exit 2 ;;
    # an "eng." prefix makes PackageManager wipe the package-parser cache on every boot (userdebug)
    eng.*)         echo "BUILD_NUMBER must not start with 'eng.': '$BUILD_NUMBER'" >&2; exit 2 ;;
  esac
  # a ro.* value over PROP_VALUE_MAX is stored out-of-line and __system_property_get() callers read an error string
  if [ "$(printf %s "$BUILD_NUMBER" | wc -c | tr -d ' ')" -ge 92 ]; then
    echo "BUILD_NUMBER must be under 92 bytes: '$BUILD_NUMBER'" >&2; exit 2
  fi
}
rist_check_build_number
export BUILD_NUMBER

if [ -n "${RIST_BUILD_DISPLAY_ID:-}" ]; then
  # must not END in a token ending "-keys": RewriteProps() in sign_target_files_apks.py drops that word
  export BUILD_DISPLAY_ID="$RIST_BUILD_DISPLAY_ID"
fi

export CCACHE_DIR="${CCACHE_DIR:-$(cd "$GRAPHENE_TREE/.." && pwd)/ccache}"
export USE_CCACHE=1

cd "$GRAPHENE_TREE" || exit 2

rist_newest() {
  local pat
  local ls_out
  local ls_rc
  local cand
  for pat in "$@"; do
    ls_out="$(ls -t -d -- $pat)"
    ls_rc=$?
    [ "$ls_rc" -eq 0 ] || continue
    [ -n "$ls_out" ] || continue
    cand="$(printf '%s\n' "$ls_out" | head -1)"
    if [ -s "$cand" ]; then
      printf '%s\n' "$cand"
      return 0
    fi
    echo "rist_newest: newest match is empty or unreadable: $cand" >&2
    return 1
  done
  echo "rist_newest: nothing matched. The ls message(s) above are the reason. Patterns tried:" >&2
  for pat in "$@"; do echo "    $pat" >&2; done
  return 1
}

rist_apk_versioncode() {
  local apk="$1"
  local aapt2="$2"
  local badging
  local rc
  local vc
  if [ ! -f "$apk" ]; then
    echo "rist_apk_versioncode: no APK at $apk -- it was never staged into the image." >&2
    return 1
  fi
  if [ ! -x "$aapt2" ]; then
    echo "rist_apk_versioncode: $aapt2 is not executable -- the host aapt2 was not built, so the" >&2
    echo "  versionCode gate cannot run. It must FAIL rather than report a mismatch it did not see." >&2
    return 1
  fi
  badging="$("$aapt2" dump badging "$apk")"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "rist_apk_versioncode: aapt2 could not read $apk (exit $rc). See its message above." >&2
    return 1
  fi
  # aapt2 has printed both versionCode='108' and versionCode=108 across versions
  vc="$(printf '%s\n' "$badging" | grep -oE "versionCode='?[0-9]+" | grep -oE '[0-9]+' | head -1)"
  if [ -z "$vc" ]; then
    echo "rist_apk_versioncode: aapt2 read $apk but its badging carries no versionCode." >&2
    return 1
  fi
  printf '%s\n' "$vc"
  return 0
}

rist_export_signing_password() {
  if [ -n "${RIST_SIGNING_PASSWORD_FILE:-}" ]; then
    [ -r "$RIST_SIGNING_PASSWORD_FILE" ] || {
      echo "RIST_SIGNING_PASSWORD_FILE is set but not readable: $RIST_SIGNING_PASSWORD_FILE" >&2
      exit 2
    }
    password="$(cat "$RIST_SIGNING_PASSWORD_FILE")"
    export password
    echo "signing: using passphrase from RIST_SIGNING_PASSWORD_FILE ($(wc -c < "$RIST_SIGNING_PASSWORD_FILE" | tr -d ' ') bytes)"
  else
    export password=""
    echo "signing: WARNING -- no RIST_SIGNING_PASSWORD_FILE; assuming UNENCRYPTED keys."
    echo "signing: an unencrypted platform key is a plain file that signs system code."
  fi
}

echo "tree=$GRAPHENE_TREE device=$DEVICE BN=$BN BUILD_NUMBER=$BUILD_NUMBER${BUILD_DISPLAY_ID:+ BUILD_DISPLAY_ID='$BUILD_DISPLAY_ID'}"
