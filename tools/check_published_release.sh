#!/usr/bin/env bash
#   tools/check_published_release.sh [--full]    # exit 0 clean, 1 mismatch, 2 could not run
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || { echo "not in the repository"; exit 2; }

FULL=0
[ "${1:-}" = "--full" ] && FULL=1

BUILD="$(tr -d '[:space:]' < releases/CURRENT_BUILD 2>/dev/null)" || true
[ -n "${BUILD:-}" ] || { echo "releases/CURRENT_BUILD is missing or empty"; exit 2; }
BASE="https://dl.ristos.org/${BUILD}"

KEY="$(grep -hoE 'RW[A-Za-z0-9+/=]{40,}' SECURITY.md image/INSTALL.md 2>/dev/null | head -1)"
[ -n "$KEY" ] || { echo "no minisign public key found in SECURITY.md or image/INSTALL.md"; exit 2; }

command -v minisign >/dev/null 2>&1 || { echo "minisign is not installed; cannot check authorship"; exit 2; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fail=0
say() { printf '%s\n' "$1"; }
bad() { printf 'MISMATCH  %s\n' "$1"; fail=1; }

say "build $BUILD -- $BASE"

for f in SHA256SUMS SHA256SUMS.minisig; do
  curl -fsS -o "$TMP/$f" "$BASE/$f" || { echo "could not fetch $f"; exit 2; }
done

if minisign -Vm "$TMP/SHA256SUMS" -P "$KEY" >/dev/null 2>&1; then
  say "ok        SHA256SUMS carries our signature"
else
  bad "SHA256SUMS is NOT signed by the key in README.md -- treat the bucket as compromised"
  exit 1
fi

LEDGER_JSON="$(python3 - "$BUILD" <<'PY'
import json,sys
b=sys.argv[1]
d=json.load(open("releases/release-ledger.json"))
r=d.get("releases",d)
if isinstance(r,dict): r=list(r.values())
e=next((x for x in r if x.get("build")==b), None)
if not e: print("{}"); sys.exit(0)
print(json.dumps((e.get("published") or {}).get("artefacts") or {}))
PY
)"
if [ "$LEDGER_JSON" = "{}" ]; then
  say "SKIP      release-ledger.json has no entry for $BUILD -- hashes cannot be cross-checked"
  fail=1
fi

while read -r want name; do
  [ -n "$name" ] || continue
  url="$BASE/$name"
  code_len="$(curl -fsS -o /dev/null -w '%{http_code} %{size_download} %{size_upload}' -r 0-0 "$url" 2>/dev/null || echo "000 0 0")"
  code="${code_len%% *}"
  case "$code" in 200|206) : ;; *) bad "$name is not reachable (HTTP $code)"; continue ;; esac

  led="$(printf '%s' "$LEDGER_JSON" | python3 -c "
import json,sys
a=json.load(sys.stdin).get(sys.argv[1]) or {}
print(a.get('sha256',''), a.get('bytes',''))
" "$name" 2>/dev/null)"
  lsha="${led%% *}"; lbytes="${led##* }"

  if [ -n "$lsha" ] && [ "$lsha" != "$want" ]; then
    bad "$name: manifest says $want, ledger says $lsha"
    continue
  fi

  if [ "$FULL" -eq 1 ]; then
    curl -fsS -o "$TMP/blob" "$url" || { bad "$name could not be downloaded in full"; continue; }
    got="$(shasum -a 256 "$TMP/blob" | cut -d' ' -f1)"
    rm -f "$TMP/blob"
    if [ "$got" != "$want" ]; then bad "$name hashes to $got, manifest says $want"; continue; fi
    say "ok        $name matches the signed manifest, byte for byte"
  else
    if [ -n "$lbytes" ]; then
      have="$(curl -fsSI "$url" 2>/dev/null | awk 'tolower($1)=="content-length:"{print $2}' | tr -d '\r' | tail -1)"
      if [ -n "$have" ] && [ "$have" != "$lbytes" ]; then
        bad "$name is $have bytes, ledger recorded $lbytes"
        continue
      fi
    fi
    if [ -n "$lsha" ]; then
      say "ok        $name reachable, hash agrees with the ledger"
    else
      say "ok        $name reachable and signed, but the ledger has no hash for it"
    fi
  fi
done < "$TMP/SHA256SUMS"

echo
if [ "$fail" -eq 0 ]; then
  say "PUBLISHED RELEASE OK"
else
  say "PUBLISHED RELEASE FAILED -- do not assume the bucket is clean"
fi
exit "$fail"
