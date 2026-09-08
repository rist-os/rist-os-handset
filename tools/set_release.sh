#!/usr/bin/env bash
#   tools/set_release.sh <BUILD> [--offline]
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

NEW="${1:-}"
OFFLINE=0
[ "${2:-}" = "--offline" ] && OFFLINE=1

case "$NEW" in
  20[0-9][0-9][01][0-9][0-3][0-9][0-9][0-9]) ;;
  *) echo "usage: tools/set_release.sh YYYYMMDDNN [--offline]"; exit 2 ;;
esac

CUR_FILE="releases/CURRENT_BUILD"
[ -f "$CUR_FILE" ] || { echo "$CUR_FILE is missing; it is the source of truth this script moves."; exit 2; }
CUR="$(tr -d '[:space:]' < "$CUR_FILE")"

if [ "$OFFLINE" -eq 0 ]; then
  URL="https://dl.ristos.org/${NEW}/SHA256SUMS.minisig"
  echo "checking $URL"
  CODE="$(curl -s -o /dev/null -w '%{http_code}' -I "$URL" || echo 000)"
  if [ "$CODE" != "200" ]; then
    echo "$URL returned $CODE."
    echo "Build $NEW is not published yet, or is published under a different name. The docs are not"
    echo "moved. Upload first, then run this again -- or pass --offline if you know better."
    exit 1
  fi
  echo "ok    build $NEW is reachable"
fi

for f in README.md image/INSTALL.md; do
  [ -f "$f" ] || continue
  perl -pi -e "s/\Q$CUR\E/$NEW/g" "$f"
done

# CHANGELOG.md is append-only: a new section goes in above the previous one.
if [ -f CHANGELOG.md ]; then
  if grep -q "^## $CUR" CHANGELOG.md; then
    perl -0pi -e "s/^(## \Q$CUR\E)/## $NEW\n\nNot written yet.\n\n---\n\n\$1/m unless \$done++" CHANGELOG.md
  else
    echo "warning: CHANGELOG.md has no '## $CUR' section; nothing inserted"
  fi
fi

printf '%s\n' "$NEW" > "$CUR_FILE"

echo "moved $CUR -> $NEW"
git --no-pager diff --stat -- README.md image/INSTALL.md CHANGELOG.md "$CUR_FILE"
echo
echo "Now run: bash tools/preflight_publish.sh"
