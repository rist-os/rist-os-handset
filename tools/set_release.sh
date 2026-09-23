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

# CUR must be a build number, for one blunt reason: the substitution below is s/\Q$CUR\E/$NEW/g.
# With CUR empty that pattern matches the empty string at every position, so perl interleaves the
# new build number between every character of README.md and image/INSTALL.md -- and the script then
# printed "moved  -> 2026092200" and exited 0.
case "$CUR" in
  20[0-9][0-9][01][0-9][0-3][0-9][0-9][0-9]) ;;
  *) echo "$CUR_FILE does not hold a YYYYMMDDNN build number (got '$CUR'). Refusing to run:"
     echo "an empty or malformed value here rewrites every character of the documents." ; exit 2 ;;
esac
if [ "$CUR" = "$NEW" ]; then
  echo "$CUR_FILE already says $NEW; nothing to move."; exit 0
fi

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

# Report what actually changed per file. perl exits 0 whether it substituted anything or not, and
# `[ -f ] || continue` skipped a missing document in silence -- so a document that had drifted out
# of sync (or been renamed) was left behind with no trace, which is how README.md ended up
# containing no build number at all while this script kept reporting success.
DRIFT=""
for f in README.md image/INSTALL.md; do
  if [ ! -f "$f" ]; then
    echo "MISSING  $f -- expected to carry the build number"; DRIFT="$DRIFT $f"; continue
  fi
  hits=$(grep -c -F "$CUR" "$f" || true)
  if [ "$hits" -eq 0 ]; then
    # README.md links to image/INSTALL.md rather than versioning itself, so no occurrence there is
    # expected. image/INSTALL.md embeds the download URL and must contain one.
    if [ "$f" = "README.md" ]; then
      echo "ok       $f carries no build number by design (it links to image/INSTALL.md)"
    else
      echo "no-op    $f contains no occurrence of $CUR -- nothing substituted"
      DRIFT="$DRIFT $f"
    fi
  else
    perl -pi -e "s/\Q$CUR\E/$NEW/g" "$f"
    echo "ok       $f: $hits line(s) moved $CUR -> $NEW"
  fi
done

# CHANGELOG.md is append-only: a new section goes in above the previous one.
if [ -f CHANGELOG.md ]; then
  if grep -q "^## Unreleased" CHANGELOG.md; then
    # the notes were written ahead of the build: give them its number
    perl -0pi -e "s/^## Unreleased\$/## $NEW/m" CHANGELOG.md
  elif grep -q "^## $CUR" CHANGELOG.md; then
    perl -0pi -e "s/^(## \Q$CUR\E)/## $NEW\n\nNot written yet.\n\n---\n\n\$1/m unless \$done++" CHANGELOG.md
  else
    echo "warning: CHANGELOG.md has no '## $CUR' section; nothing inserted"
  fi
fi

printf '%s\n' "$NEW" > "$CUR_FILE"

echo "moved $CUR -> $NEW"
if [ -n "$DRIFT" ]; then
  echo
  echo "WARNING: these documents did not reference $CUR, so they still do not name the release:"
  for f in $DRIFT; do echo "    $f"; done
  echo "preflight_publish.sh only fails on a build number that is WRONG, never on one that is"
  echo "absent, so nothing downstream will catch this. Fix them by hand before publishing."
fi
git --no-pager diff --stat -- README.md image/INSTALL.md CHANGELOG.md "$CUR_FILE"
echo
echo "Now run: bash tools/preflight_publish.sh"
