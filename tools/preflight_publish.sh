#!/bin/bash
# Usage:  tools/preflight_publish.sh [ref]     (default: HEAD)

set -uo pipefail
REF="${1:-HEAD}"
HERE="$(cd "$(dirname "$0")" && pwd)"
FAIL=0
MAX_TRACKED_KB=5120

SKIPPED=""

note() { printf '  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAIL=1; }
pass() { printf 'ok    %s\n' "$1"; }
skip() { printf 'SKIP  %s\n' "$1"; SKIPPED="${SKIPPED}$1"$'\n'; }

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "not a git repository"; exit 2
fi

cd "$(git rev-parse --show-toplevel)" || { echo "cannot reach the repository root"; exit 2; }

if ! git rev-parse --verify --quiet "$REF^{commit}" >/dev/null 2>&1; then
  echo "'$REF' is not a commit in this repository."
  echo "Check 3 scans that ref for denylisted strings; an unresolvable one makes every pattern"
  echo "look absent and this script print PREFLIGHT PASS over a tree it never read."
  exit 2
fi

TRACKED="$(git ls-files)"
if [ -z "$TRACKED" ]; then
  echo "git ls-files returned nothing -- wrong directory, or an empty index."
  echo "Refusing to certify a tree this script has not actually looked at."
  exit 2
fi
TRACKED_COUNT=$(printf '%s\n' "$TRACKED" | wc -l | tr -d ' ')

# .publish-denylist must exist on disk for check 3 but must never be tracked.
BAD_TRACKED=$(printf '%s\n' "$TRACKED" | grep -E '(\.pk8|\.pem|\.jks|\.keystore|\.p12|\.der|_rsa|\.env$|\.env\.|local\.properties|adb_keys|^\.publish-denylist$|^\.publish-never-ship$)$' || true)
if [ -n "$BAD_TRACKED" ]; then
  fail "key material or local config is tracked:"
  echo "$BAD_TRACKED" | while read -r f; do note "$f"; done
else
  pass "no key material or local config tracked ($TRACKED_COUNT files examined)"
fi

# du's stderr is deliberately not discarded.
SIZES=$(git ls-files -z | xargs -0 du -k)
if [ -z "$SIZES" ]; then
  fail "could not measure tracked file sizes -- the ${MAX_TRACKED_KB} KB cap did not run."
else
  BIG=$(printf '%s\n' "$SIZES" | awk -v m="$MAX_TRACKED_KB" '$1 > m {print $1" KB  "$2}')
  if [ -n "$BIG" ]; then
    fail "tracked files over ${MAX_TRACKED_KB} KB (binaries belong outside the repo):"
    echo "$BIG" | while read -r l; do note "$l"; done
  else
    pass "no tracked file over ${MAX_TRACKED_KB} KB"
  fi
fi

DENY="${PREFLIGHT_DENYLIST:-.publish-denylist}"
[ "$DENY" = ".publish-denylist" ] || note "denylist path overridden: $DENY"
if [ ! -f "$DENY" ]; then
  if [ "${PREFLIGHT_DENYLIST_OPTIONAL:-0}" = 1 ]; then
    skip "denylist scan: no $DENY in this checkout (PREFLIGHT_DENYLIST_OPTIONAL=1)"
    echo "      Tree, BINARY, HISTORY and COMMIT-MESSAGE scans for private strings DID NOT RUN."
    echo "      Nothing on this run says anything about private data. Only a maintainer run"
    echo "      holding the real $DENY can certify a publish."
  else
    fail "no $DENY -- refusing to certify a publish without the private-string scan."
    echo "      Copy .publish-denylist.example to $DENY and put the REAL values in it."
    echo "      Somewhere that legitimately cannot hold it (CI, a fork) sets"
    echo "      PREFLIGHT_DENYLIST_OPTIONAL=1, which forfeits PREFLIGHT PASS for a counted SKIP."
  fi
else
  PATCOUNT=$(grep -cvE '^[[:space:]]*(#|$)' "$DENY" || true)
  [ -n "$PATCOUNT" ] || PATCOUNT=0
  if [ "$PATCOUNT" -eq 0 ]; then
    fail "$DENY holds no patterns -- only comments and blank lines."
    echo "      An empty denylist scans nothing and reports a clean tree. Fill it in."
  else
    # git grep -I skips binary files; those are scanned with strings from the working tree.
    TEXTLIST=$(mktemp "${TMPDIR:-/tmp}/rist_preflight_text.XXXXXX") || { echo "cannot create temp file"; exit 2; }
    BINLIST=$(mktemp "${TMPDIR:-/tmp}/rist_preflight_bin.XXXXXX")   || { echo "cannot create temp file"; exit 2; }
    trap 'rm -f "$TEXTLIST" "$BINLIST"' EXIT
    git grep -I -l "" -- . > "$TEXTLIST"
    TEXTCOUNT=$(wc -l < "$TEXTLIST" | tr -d ' ')
    if [ "$TEXTCOUNT" -eq 0 ]; then
      fail "could not classify tracked files as text or binary -- the denylist scan did not run."
    else
      printf '%s\n' "$TRACKED" | grep -v -x -F -f "$TEXTLIST" > "$BINLIST" || true
      BINCOUNT=$(wc -l < "$BINLIST" | tr -d ' ')
      BINSCAN=1
      if [ "$BINCOUNT" -gt 0 ] && ! command -v strings >/dev/null 2>&1; then
        BINSCAN=0
        skip "binary denylist scan: 'strings' is not installed (binutils), $BINCOUNT binary file(s) unexamined"
      fi
      HITS=0
      while IFS= read -r pat; do
        case "$pat" in ''|'#'*) continue ;; esac
        if git grep -I -l -E -e "$pat" "$REF" -- . >/dev/null 2>&1; then
          fail "denylisted pattern present in $REF: /$pat/"
          git grep -I -l -E -e "$pat" "$REF" -- . 2>/dev/null | sed 's/^/      /' | head -10
          HITS=1
        fi
        [ "$BINSCAN" -eq 1 ] && while IFS= read -r bf; do
          [ -f "$bf" ] || continue
          if LC_ALL=C strings -- "$bf" 2>/dev/null | grep -q -E -e "$pat"; then
            fail "denylisted pattern inside BINARY file: /$pat/"
            echo "      $bf"
            HITS=1
          fi
        done < "$BINLIST"
        if [ -n "$(git log --all --oneline -S"$pat" --pickaxe-regex 2>/dev/null | head -1)" ]; then
          fail "denylisted pattern in HISTORY (publish from an orphan commit): /$pat/"
          git log --all --oneline -S"$pat" --pickaxe-regex 2>/dev/null | sed 's/^/      /' | head -5
          HITS=1
        fi
        if [ -n "$(git log --all --grep="$pat" -E --oneline 2>/dev/null | head -1)" ]; then
          fail "denylisted pattern in a COMMIT MESSAGE: /$pat/"
          git log --all --grep="$pat" -E --oneline 2>/dev/null | sed 's/^/      /' | head -5
          HITS=1
        fi
      done < "$DENY"
      [ "$HITS" = 0 ] && pass "no denylisted string in tree, history or commit messages"
      if [ "$BINSCAN" -eq 1 ]; then
        note "$PATCOUNT patterns scanned over $TEXTCOUNT text and $BINCOUNT binary files"
      else
        note "$PATCOUNT patterns scanned over $TEXTCOUNT text files; $BINCOUNT binary files NOT scanned"
      fi
    fi
  fi
fi

for f in LICENSE NOTICE; do
  if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then pass "$f is tracked"
  else fail "$f is missing -- without it every file reads as all-rights-reserved"; fi
done

XMLCHK="$HERE/check_xml.py"
if [ ! -f "$XMLCHK" ]; then
  fail "tools/check_xml.py is missing -- the XML parse gate did not run."
elif ! command -v python3 >/dev/null 2>&1; then
  fail "python3 not found -- the XML parse gate did not run."
else
  # Status taken straight from python3, not off the end of a pipeline.
  XMLOUT=$(python3 "$XMLCHK" 2>&1)
  XMLRC=$?
  if [ "$XMLRC" -eq 0 ]; then
    pass "XML parses ($XMLOUT)"
  else
    fail "XML in this tree will not parse (check_xml.py exit $XMLRC):"
    printf '%s\n' "$XMLOUT" | sed 's/^/      /'
  fi
fi

# Generic prefixes stay here. The full list of private document names lives in the
# gitignored .publish-never-ship (see .publish-never-ship.example): one path prefix per
# line, '+path' for an allowlist entry, '#' comments.
NEVER_SHIP_BUILTIN='docs/legal/
docs/hosting/'

NS_FILE="${PREFLIGHT_NEVER_SHIP:-.publish-never-ship}"
[ "$NS_FILE" = ".publish-never-ship" ] || note "never-ship list path overridden: $NS_FILE"
NEVER_SHIP=""
NEVER_SHIP_ALLOW=""
NS_LOADED=0
if [ -f "$NS_FILE" ]; then
  NEVER_SHIP=$(grep -vE '^[[:space:]]*(#|\+|$)' "$NS_FILE" | sed 's/[[:space:]]*$//' || true)
  NEVER_SHIP_ALLOW=$(grep -E '^\+' "$NS_FILE" | sed 's/^+//; s/[[:space:]]*$//' || true)
  [ -n "$NEVER_SHIP" ] && NS_LOADED=1
fi
NEVER_SHIP="$NEVER_SHIP_BUILTIN"$'\n'"$NEVER_SHIP"

# Prefix match at the root and at any depth, case-folded.
never_ship_hits() {
  printf '%s\n' "$1" | while IFS= read -r p; do
    [ -n "$p" ] || continue
    printf '%s\n' "$NEVER_SHIP" | while IFS= read -r pre; do
      [ -n "$pre" ] || continue
      lp=$(printf '%s' "$p" | tr 'A-Z' 'a-z')
      lpre=$(printf '%s' "$pre" | tr 'A-Z' 'a-z')
      case "$lp" in
        "$lpre"*|*/"$lpre"*) printf '%s\n' "$NEVER_SHIP_ALLOW" | grep -qxF "$p" || printf '%s\n' "$p" ;;
      esac
    done
    lp=$(printf '%s' "$p" | tr 'A-Z' 'a-z')
    case "$lp" in
      *_master_doc.md|*-master-doc.md|agents.md|*/agents.md) printf '%s\n' "$NEVER_SHIP_ALLOW" | grep -qxF "$p" || printf '%s\n' "$p" ;;
    esac
  done | sort -u
}

REFFILES=$(git ls-tree -r --name-only "$REF" 2>/dev/null)
NS_TRACKED=$(never_ship_hits "$(printf '%s\n%s\n' "$TRACKED" "$REFFILES")")
NS_DISK=$(never_ship_hits "$( { git ls-files --others --exclude-standard; git ls-files --others --ignored --exclude-standard --directory; } 2>/dev/null )")

if [ "$NS_LOADED" -eq 0 ]; then
  if [ -f "$NS_FILE" ]; then
    skip "never-publish path scan: $NS_FILE holds no entries -- only comments and blank lines"
  else
    skip "never-publish path scan: no $NS_FILE in this checkout"
  fi
  echo "      Only the built-in prefixes were checked. Copy .publish-never-ship.example to"
  echo "      $NS_FILE and fill in the real paths; only a run holding it can certify a publish."
fi
if [ -n "$NS_TRACKED" ]; then
  fail "paths that must never be published are TRACKED in the index or in $REF:"
  printf '%s\n' "$NS_TRACKED" | while read -r f; do note "$f"; done
  echo "      Not covered by any allowlist line. Remove them from the tree being published --"
  echo "      from DISK, not merely from the index, because an index exclusion survives exactly"
  echo "      one commit and the next \`git add -A\` undoes it."
elif [ -n "$NS_DISK" ]; then
  fail "paths that must never be published are ON DISK in this tree (untracked or ignored):"
  printf '%s\n' "$NS_DISK" | while read -r f; do note "$f"; done
  echo "      Not tracked, so nothing is published yet -- and that is the whole finding. This is"
  echo "      one \`git add -A\` away from being published. Delete them from the working tree."
elif [ "$NS_LOADED" -eq 1 ]; then
  pass "no never-publish path tracked or on disk ($(printf '%s\n' "$NEVER_SHIP" | grep -c .) prefixes, allowlist: $(printf '%s\n' "$NEVER_SHIP_ALLOW" | grep -c .) files)"
fi

# Markers must be short enough to survive a line wrap; grep works a line at a time.
BANNER_MARKERS='RIST-DO-NOT-PUBLISH
NOT FOR PUBLICATION
must not be printed'

BANNER_EXEMPT='tools/preflight_publish.sh'

BANNER_HITS=""
while IFS= read -r m; do
  [ -n "$m" ] || continue
  H=$( { git grep -I -i -l -F -e "$m" "$REF" -- . 2>/dev/null | sed "s|^${REF}:||"
         git grep -I -i -l -F -e "$m" -- . 2>/dev/null ; } | sort -u )
  [ -n "$H" ] || continue
  H=$(printf '%s\n' "$H" | grep -vxF "$BANNER_EXEMPT" || true)
  [ -n "$H" ] || continue
  BANNER_HITS="${BANNER_HITS}$(printf '%s\n' "$H" | sed "s|\$|   <- /$m/|")"$'\n'
done <<BANNEREOF
$BANNER_MARKERS
BANNEREOF

if [ -n "$BANNER_HITS" ]; then
  fail "files in $REF carry a not-for-publication banner:"
  printf '%s' "$BANNER_HITS" | while read -r l; do note "$l"; done
  echo "      A banner is not advisory. Either the file does not belong in the tree being"
  echo "      published, or the banner is stale and someone deletes the sentence deliberately."
else
  pass "no not-for-publication banner in $REF"
fi

# CHANGELOG.md is scoped to its first heading only.
BUILD_FILE="releases/CURRENT_BUILD"
if [ ! -f "$BUILD_FILE" ]; then
  skip "no $BUILD_FILE -- cannot tell which build the documents should name"
else
  CURRENT_BUILD="$(tr -d '[:space:]' < "$BUILD_FILE")"
  case "$CURRENT_BUILD" in
    20[0-9][0-9][01][0-9][0-3][0-9][0-9][0-9]) ;;
    *) skip "$BUILD_FILE does not contain a YYYYMMDDNN build number"; CURRENT_BUILD="" ;;
  esac

  if [ -n "$CURRENT_BUILD" ]; then
    DRIFT=""
    for f in README.md image/INSTALL.md; do
      [ -f "$f" ] || continue
      WRONG=$(grep -oE '\b20[0-9]{2}[01][0-9][0-3][0-9][0-9]{2}\b' "$f" 2>/dev/null \
                | grep -vxF "$CURRENT_BUILD" | sort -u || true)
      [ -n "$WRONG" ] && DRIFT="${DRIFT}$(printf '%s\n' "$WRONG" | sed "s|^|   $f names |")"$'\n'
    done

    if [ -f CHANGELOG.md ]; then
      TOP=$(grep -m1 -oE '^## 20[0-9]{2}[01][0-9][0-3][0-9][0-9]{2}' CHANGELOG.md | sed 's/^## //' || true)
      if [ -n "$TOP" ] && [ "$TOP" != "$CURRENT_BUILD" ]; then
        DRIFT="${DRIFT}   CHANGELOG.md's newest entry is $TOP"$'\n'
      fi
    fi

    if [ -n "$DRIFT" ]; then
      fail "documents disagree with $BUILD_FILE ($CURRENT_BUILD):"
      printf '%s' "$DRIFT" | while read -r l; do note "$l"; done
      echo "      Run: tools/set_release.sh $CURRENT_BUILD"
    else
      pass "every published build reference is $CURRENT_BUILD"
    fi
  fi
fi

INTERNAL_PATTERNS='\bdoc [0-9]{2}\b
\btask #?[0-9]{2,3}\b
(^|[^[:alnum:]#*/"'"'"'])#[0-9]{2,3}([^0-9A-Fa-f#]|$)
\(not published\)'
INTERNAL_HITS=""
while IFS= read -r pat; do
  [ -n "$pat" ] || continue
  h=$(git grep -n -I -i -E -e "$pat" -- . ':!aosp/patches' 2>/dev/null | head -5 || true)
  # awk, not sed: several patterns contain "|", which sed would take as its delimiter.
  [ -n "$h" ] && INTERNAL_HITS="${INTERNAL_HITS}$(printf '%s\n' "$h" | awk -v p="$pat" '{print "   [" p "] " $0}')"$'\n'
done <<EOF_PATTERNS
$INTERNAL_PATTERNS
EOF_PATTERNS
if [ -n "$INTERNAL_HITS" ]; then
  fail "internal-process references in the tree (first 5 per pattern):"
  printf '%s' "$INTERNAL_HITS" | while IFS= read -r l; do [ -n "$l" ] && note "$l"; done
else
  pass "no internal-process references (doc numbers, tracker items, backend paths, people)"
fi

echo
TOTAL=$(printf '%s\n' "$SIZES" | awk '{s+=$1} END {printf "%.1f", s/1024}')
echo "tracked payload: ${TOTAL} MB across $TRACKED_COUNT files"

SKIPCOUNT=0
[ -n "$SKIPPED" ] && SKIPCOUNT=$(printf '%s' "$SKIPPED" | grep -c '')

if [ "$FAIL" != 0 ]; then
  echo "PREFLIGHT FAIL -- do not publish"
elif [ "$SKIPCOUNT" -ne 0 ]; then
  echo "PREFLIGHT PARTIAL -- $SKIPCOUNT of the checks did not run. This is NOT a publish certification."
  printf '%s' "$SKIPPED" | while IFS= read -r s; do
    [ -n "$s" ] && printf '  did not run: %s\n' "$s"
  done
  echo "  Everything that DID run passed. Re-run where the skipped checks can run before publishing."
else
  echo "PREFLIGHT PASS"
fi

exit $FAIL
