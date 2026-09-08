#!/bin/bash
#     bash verify_download.sh <download-dir> [minisign-public-key]
set -uo pipefail

DIR="${1:-}"
PUBKEY="${2:-}"
[ -n "$DIR" ] || { echo "usage: $0 <download-dir> [minisign-public-key]" >&2; exit 2; }
[ -d "$DIR" ] || { echo "not a directory: $DIR" >&2; exit 2; }
DIR="$(cd "$DIR" && pwd)"

FAIL=0
CANTTELL=0
pass() { printf 'ok    %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAIL=1; }
cant() { printf 'UNCHECKED  %s\n' "$1"; CANTTELL=1; }
note() { printf '      %s\n' "$1"; }

SUMS="$DIR/SHA256SUMS"
[ -f "$SUMS" ] || {
  echo "No SHA256SUMS in $DIR." >&2
  echo "Nothing here can be checked. Do not flash it." >&2
  exit 2
}
N_SUMS="$(grep -c . "$SUMS")"
if [ "$N_SUMS" -eq 0 ]; then
  echo "SHA256SUMS is empty. It lists no files, so checking against it proves nothing." >&2
  echo "Do not flash this download." >&2
  exit 2
fi

SIG=""
for cand in "$SUMS.minisig" "$SUMS.sig"; do
  [ -f "$cand" ] && { SIG="$cand"; break; }
done

SIGNED=0
if [ -z "$PUBKEY" ]; then
  cant "no public key given, so AUTHORSHIP was not checked."
  note "Re-run as:  $0 $DIR <public-key>"
  note "Get that key from somewhere other than the site you downloaded this from."
elif [ -z "$SIG" ]; then
  cant "no signature file (SHA256SUMS.minisig) next to SHA256SUMS."
  note "A signed release carries one. If we have not published a signing key yet then this"
  note "is expected -- and it means this download is checkable for corruption only. If we"
  note "have, then this is not a complete release and you should not flash it."
elif ! command -v minisign >/dev/null 2>&1; then
  cant "minisign is not installed, so AUTHORSHIP was not checked."
  note "macOS: brew install minisign    Debian/Ubuntu: apt install minisign"
else
  if minisign -Vm "$SUMS" -P "$PUBKEY"; then
    pass "signature over SHA256SUMS is valid for the key you supplied"
    SIGNED=1
  else
    fail "SIGNATURE DOES NOT VERIFY."
    note "This is not a download problem to retry. Either the key is not ours, or the file"
    note "was changed after we signed it. Delete it and do not flash it."
  fi
fi

if command -v sha256sum >/dev/null 2>&1; then CHECK="sha256sum -c"
elif command -v shasum >/dev/null 2>&1; then CHECK="shasum -a 256 -c"
else
  cant "neither sha256sum nor shasum found, so INTEGRITY was not checked either."
  CHECK=""
fi

if [ -n "$CHECK" ]; then
  if ( cd "$DIR" && $CHECK SHA256SUMS ); then
    pass "all $N_SUMS files match SHA256SUMS"
  else
    fail "one or more files do not match SHA256SUMS (see the lines above)."
    note "A file that is missing is as disqualifying as one that differs."
  fi
fi

REQ="$DIR/REQUIRED_STOCK.txt"
if [ -f "$REQ" ]; then
  req_build="$(tr -d '\r' < "$REQ" | sed -n 's/^build-id=//p' | head -1)"
  req_zip="$(tr -d '\r' < "$REQ" | sed -n 's/^factory-zip=//p' | head -1)"
  req_zip_sha="$(tr -d '\r' < "$REQ" | sed -n 's/^factory-zip-sha256=//p' | head -1)"

  if [ -n "$req_build" ]; then
    note "this release requires Google build $req_build to be on the phone already"
  fi

  if [ -n "$req_zip" ] && [ -n "$req_zip_sha" ] && [ -f "$DIR/$req_zip" ]; then
    if command -v sha256sum >/dev/null 2>&1; then
      got_zip_sha="$(sha256sum "$DIR/$req_zip" | awk '{print $1}')"
    elif command -v shasum >/dev/null 2>&1; then
      got_zip_sha="$(shasum -a 256 "$DIR/$req_zip" | awk '{print $1}')"
    else
      got_zip_sha=""
    fi
    if [ -z "$got_zip_sha" ]; then
      cant "found $req_zip but no sha256 tool, so Google's factory image was not checked."
    elif [ "$(printf '%s' "$got_zip_sha" | tr 'A-F' 'a-f')" = "$(printf '%s' "$req_zip_sha" | tr 'A-F' 'a-f')" ]; then
      pass "Google's factory image $req_zip matches the hash this release expects"
      note "That is OUR record of it. Compare it against Google's own published SHA-256 too."
    else
      fail "$req_zip does not match the hash this release expects."
      note "expected: $req_zip_sha"
      note "found:    $got_zip_sha"
      note "Do not flash it. Re-download it from https://developers.google.com/android/images"
      note "and check it against the SHA-256 Google publishes on that page."
    fi
  elif [ -n "$req_zip" ]; then
    note "Google's factory image ($req_zip) is not in this directory yet."
    note "You need it unless the phone already runs ${req_build:-the required build}; see INSTALL.md step 3."
  fi
fi

echo
if [ "$FAIL" -ne 0 ]; then
  echo "VERIFICATION FAILED -- do not flash this."
  echo "Something that WAS checked did not match. This is not a download to retry."
  exit 1
fi
if [ "$SIGNED" -ne 1 ] || [ "$CANTTELL" -ne 0 ]; then
  echo "INTEGRITY ONLY -- the bytes are intact, but nothing here shows WE produced them."
  echo "Whoever could replace the image could replace SHA256SUMS beside it. Get the public"
  echo "key by a route the download host does not control and run this again."
  echo "This is NOT a pass. It is the absence of an answer to the question that matters."
  exit 2
fi
echo "VERIFIED: $N_SUMS files, signed by the key you supplied."
echo
echo "Next: INSTALL.md. Read the emergency-calling section BEFORE you unlock anything --"
echo "unlocking erases the phone, and that step comes after a decision, not before one."
echo
echo "This download is a complete factory package. It carries Google's bootloader and modem"
echo "firmware alongside RistOS, so you do not need a separate download from Google: unzip it"
echo "and run flash-all.sh."
echo
echo "That firmware is Google's, redistributed unmodified. RistOS is not affiliated with,"
echo "sponsored by or endorsed by Google."
exit 0
