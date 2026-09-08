#!/usr/bin/env bash
# Apply every patch in aosp/patches/ to the git project it targets.

set -euo pipefail

RIST_REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PATCH_DIR="$RIST_REPO/aosp/patches"

GIT_ID_NAME="RistOS Build"
GIT_ID_EMAIL="build@ristos.org"

MODE="apply"
WITH_0001="${RIST_APPLY_0001:-0}"
ONLY=""
TREE_TOP=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --check)      MODE="check" ;;
    --list)       MODE="list" ;;
    --with-0001)  WITH_0001=1 ;;
    --only)       shift; ONLY="${1:-}" ;;
    --only=*)     ONLY="${1#--only=}" ;;
    -h|--help)    echo "usage: apply_patches.sh [options]"; exit 0 ;;
    -*)           echo "apply_patches.sh: unknown option $1" >&2; exit 2 ;;
    *)            TREE_TOP="$1" ;;
  esac
  # a trailing --only with no value leaves $# at 0; a bare shift would exit under set -e
  shift || break
done

if ! command -v git >/dev/null 2>&1; then
  echo "FATAL: git is not on PATH." >&2
  exit 2
fi

if [ -z "$TREE_TOP" ]; then
  TREE_TOP="${ANDROID_BUILD_TOP:-${GRAPHENE_TREE:-}}"
fi
if [ -z "$TREE_TOP" ]; then
  echo "FATAL: no tree given. Pass it as \$1, or export ANDROID_BUILD_TOP / GRAPHENE_TREE." >&2
  echo "       (In a lunched shell ANDROID_BUILD_TOP is already set.)" >&2
  exit 2
fi
if [ ! -d "$TREE_TOP" ]; then
  echo "FATAL: $TREE_TOP is not a directory." >&2
  exit 2
fi
TREE_TOP="$(cd "$TREE_TOP" && pwd)"          # absolute before any cd

if [ ! -f "$TREE_TOP/build/envsetup.sh" ]; then
  echo "FATAL: $TREE_TOP does not look like a synced AOSP/GrapheneOS tree" >&2
  echo "       (no build/envsetup.sh). Refusing to guess -- pointing this at the wrong" >&2
  echo "       directory would report every patch as unresolvable and mean nothing." >&2
  exit 2
fi

if [ ! -d "$PATCH_DIR" ]; then
  echo "FATAL: $PATCH_DIR does not exist in this checkout." >&2
  exit 2
fi

shopt -s nullglob
PATCHES=( "$PATCH_DIR"/*.patch )
shopt -u nullglob
if [ "${#PATCHES[@]}" -eq 0 ]; then
  echo "FATAL: no *.patch files in $PATCH_DIR. Nothing to do, and that is not a pass -- this" >&2
  echo "       script exists because patches go missing silently." >&2
  exit 2
fi

GIT_ID_NOTE=""
HAVE_EMAIL="$(git config --get user.email 2>/dev/null || true)"
HAVE_NAME="$(git config --get user.name 2>/dev/null || true)"
if [ -z "$HAVE_EMAIL" ] || [ -z "$HAVE_NAME" ]; then
  GIT_ID_NOTE="no git user.name/user.email configured; using \"$GIT_ID_NAME <$GIT_ID_EMAIL>\" for these commits only"
else
  GIT_ID_NAME="$HAVE_NAME"
  GIT_ID_EMAIL="$HAVE_EMAIL"
fi

hints_for() {
  case "$(basename "$1")" in
    0001-*) echo "frameworks/base" ;;
    0002-*) echo "packages/apps/NetworkLocation packages/modules/NetworkLocation packages/apps/GrapheneOSNetworkLocation" ;;
    0003-*) echo "bootable/recovery" ;;
    0004-*) echo "frameworks/base" ;;
    0006-*) echo "frameworks/base" ;;
    *)      echo "" ;;
  esac
}

selected() {
  case "$(basename "$1")" in
    0001-*) [ "$WITH_0001" = "1" ] && return 0 || return 1 ;;
    *)      return 0 ;;
  esac
}

patch_files() {
  sed -n 's|^diff --git a/.* b/\(.*\)$|\1|p' "$1"
}

patch_needle() {
  patch_files "$1" | awk '{ n = gsub("/","/"); print n "\t" $0 }' | sort -rn | head -1 | cut -f2-
}

project_holds() {
  local dir="$1" patch="$2" f
  [ -d "$dir" ] || return 1
  [ -e "$dir/.git" ] || return 1          # -e: a submodule/repo checkout has .git as a FILE
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    [ -f "$dir/$f" ] || return 1
  done <<EOF
$(patch_files "$patch")
EOF
  return 0
}

resolve_project() {
  local patch="$1" hint cand needle found
  for hint in $(hints_for "$patch"); do
    cand="$TREE_TOP/$hint"
    if project_holds "$cand" "$patch"; then
      echo "$cand"
      return 0
    fi
  done
  needle="$(patch_needle "$patch")"
  [ -n "$needle" ] || return 1
  found="$(find "$TREE_TOP" \
             -maxdepth 8 \
             \( -path "$TREE_TOP/out" -o -path "$TREE_TOP/.repo" \) -prune -o \
             -path "*/$needle" -print 2>/dev/null | head -20 || true)"
  while IFS= read -r cand; do
    [ -n "$cand" ] || continue
    cand="${cand%/$needle}"
    if project_holds "$cand" "$patch"; then
      echo "$cand"
      return 0
    fi
  done <<EOF
$found
EOF
  return 1
}

RC=0
# =() is required: an array that is never assigned is unbound under set -u
declare -a REPORT=()
note() { REPORT[${#REPORT[@]}]="$1"; }

echo "=== RistOS: applying aosp/patches/ to $TREE_TOP ==="
echo "    patches from : $PATCH_DIR"
echo "    mode         : $MODE"
if [ -n "$GIT_ID_NOTE" ]; then
  echo "    git identity : $GIT_ID_NOTE"
else
  echo "    git identity : $GIT_ID_NAME <$GIT_ID_EMAIL> (from git config)"
fi
echo ""

for PATCH in "${PATCHES[@]}"; do
  BASE="$(basename "$PATCH")"

  if [ -n "$ONLY" ]; then
    case "$BASE" in
      "$ONLY"*) : ;;
      *) continue ;;
    esac
  fi

  echo "--- $BASE"

  if ! selected "$PATCH"; then
    echo "    SKIP      0001 is not applied by default."
    echo "              Use --with-0001 to include it."
    note "SKIP     $BASE (deferred by aosp/rist.mk; --with-0001 to include)"
    echo ""
    continue
  fi

  # =() is required: an array that is never assigned is unbound under set -u
  unset PFILES; declare -a PFILES=()
  while IFS= read -r f; do
    [ -n "$f" ] && PFILES[${#PFILES[@]}]="$f"
  done <<EOF
$(patch_files "$PATCH")
EOF
  if [ "${#PFILES[@]}" -eq 0 ]; then
    echo "    FAIL      no 'diff --git' lines in this file -- it names no files to change." >&2
    echo "              Either it is not a patch, or it was truncated in transit." >&2
    note "FAIL     $BASE  (no diff --git lines)"
    RC=1
    echo ""
    continue
  fi

  PROJ="$(resolve_project "$PATCH" || true)"
  if [ -z "$PROJ" ]; then
    echo "    FAIL      could not find a git project holding this patch's files." >&2
    echo "              The patch names:" >&2
    patch_files "$PATCH" | sed 's/^/                /' >&2
    echo "              Tried: $(hints_for "$PATCH")" >&2
    echo "              Find it rather than guessing:" >&2
    echo "                repo forall -c 'pwd' | grep -i <projectname>" >&2
    echo "                find \"$TREE_TOP\" -path '*/$(patch_needle "$PATCH")' -not -path '*/out/*'" >&2
    note "FAIL     $BASE  (no project found)"
    RC=1
    echo ""
    continue
  fi

  REL="${PROJ#$TREE_TOP/}"
  echo "    project   $REL"
  echo "    files     ${PFILES[*]}"

  # reverse-applies cleanly == already applied; a zero-context patch needs --unidiff-zero either way
  if ( cd "$PROJ" && git apply --reverse --check -p1 "$PATCH" ) >/dev/null 2>&1 \
     || ( cd "$PROJ" && git apply --reverse --check --unidiff-zero -p1 "$PATCH" ) >/dev/null 2>&1; then
    echo "    ok        already applied (reverse-applies cleanly); nothing to do"
    note "ALREADY  $BASE  -> $REL"
    echo ""
    continue
  fi

  GITDIR="$( ( cd "$PROJ" && git rev-parse --git-dir ) 2>/dev/null || true )"
  if [ -n "$GITDIR" ]; then
    case "$GITDIR" in
      /*) : ;;
      *)  GITDIR="$PROJ/$GITDIR" ;;
    esac
    if [ -d "$GITDIR/rebase-apply" ]; then
      echo "    FAIL      $REL has a half-finished git am from an earlier run" >&2
      echo "              ($GITDIR/rebase-apply exists). Resolve it before retrying:" >&2
      echo "                git -C \"$PROJ\" am --abort      # or --continue, if you were mid-fix" >&2
      note "FAIL     $BASE  -> $REL  (stale rebase-apply)"
      RC=1
      echo ""
      continue
    fi
  fi

  METHOD=""
  if ( cd "$PROJ" && git apply --check -p1 "$PATCH" ) >/dev/null 2>&1; then
    METHOD="am"
  # --unidiff-zero disables git's surrounding-context check: fallback only, never the default
  elif ( cd "$PROJ" && git apply --check --unidiff-zero -p1 "$PATCH" ) >/dev/null 2>&1; then
    METHOD="applyz"
    echo "    NOTE      zero-context patch: git am CANNOT apply this one (git apply refuses a"
    echo "              zero-context hunk without --unidiff-zero, and git am has no way to pass"
    echo "              it down). Applying with git apply --index --unidiff-zero + commit."
  fi

  if [ -z "$METHOD" ]; then
    echo "    FAIL      does not apply to $REL, and it is not already applied." >&2
    echo "" >&2
    ( cd "$PROJ" && git apply --check -p1 "$PATCH" ) 2>&1 | sed 's/^/              /' >&2 || true
    echo "" >&2
    case "$BASE" in
      0004-*)
        echo "              0004 carries NO context lines (@@ -7329 +7329 @@): it matches that exact" >&2
        echo "              line or nothing, and --unidiff-zero did not rescue it either. Upstream" >&2
        echo "              has moved. Do not add fuzz -- find the string and regenerate:" >&2
        echo "                grep -n deprecated_abi_message_grapheneos \\" >&2
        echo "                  \"$PROJ/core/res/res/values/strings.xml\"" >&2
        ;;
      0003-*)
        echo "              The patch header carries a hand-application fallback; there are exactly" >&2
        echo "              two string literals to change. Read the section" >&2
        echo "              \"IF git am REJECTS THIS -- HAND-APPLY\" in:" >&2
        echo "                $PATCH" >&2
        ;;
      *)
        echo "              Hand-apply per the patch header, then regenerate it with" >&2
        echo "              git format-patch -- do not edit the @@ headers." >&2
        ;;
    esac
    note "FAIL     $BASE  -> $REL  (does not apply)"
    RC=1
    echo ""
    continue
  fi

  if [ "$MODE" = "list" ] || [ "$MODE" = "check" ]; then
    echo "    ok        would apply cleanly to $REL via $METHOD (nothing written)"
    note "WOULD    $BASE  -> $REL  ($METHOD)"
    echo ""
    continue
  fi

  DIRTY="$( ( cd "$PROJ" && git status --porcelain -- "${PFILES[@]}" ) 2>/dev/null || true )"
  if [ -n "$DIRTY" ]; then
    echo "    FAIL      $REL has uncommitted changes to the files this patch touches:" >&2
    printf '%s\n' "$DIRTY" | sed 's/^/                /' >&2
    echo "              Applying on top of them would produce a tree nobody can reason about." >&2
    echo "              Commit or discard them first." >&2
    note "FAIL     $BASE  -> $REL  (project dirty)"
    RC=1
    echo ""
    continue
  fi

  APPLY_OK=0
  if [ "$METHOD" = "am" ]; then
    if ( cd "$PROJ" && git -c "user.name=$GIT_ID_NAME" -c "user.email=$GIT_ID_EMAIL" \
           am --keep-non-patch "$PATCH" ) ; then
      APPLY_OK=1
    else
      echo "    FAIL      git am failed on $REL after --check said it would apply." >&2
      echo "              Aborting the am so the project is left exactly as it was." >&2
      ( cd "$PROJ" && git am --abort ) >/dev/null 2>&1 || true
      echo "              If git am exited 128, it is an identity problem -- see the header." >&2
    fi
  else
    SUBJ="$(sed -n 's/^Subject: \[PATCH[^]]*\] //p' "$PATCH" | head -1)"
    [ -n "$SUBJ" ] || SUBJ="rist: apply $BASE"
    if ( cd "$PROJ" \
         && git apply --index --unidiff-zero -p1 "$PATCH" \
         && git -c "user.name=$GIT_ID_NAME" -c "user.email=$GIT_ID_EMAIL" \
              commit -q -m "$SUBJ" -- "${PFILES[@]}" ) ; then
      APPLY_OK=1
    else
      echo "    FAIL      git apply --unidiff-zero / commit failed on $REL." >&2
      echo "              The index may hold a partial application. Inspect before retrying:" >&2
      echo "                git -C \"$PROJ\" status" >&2
      echo "                git -C \"$PROJ\" reset --hard   # only if you have nothing else there" >&2
    fi
  fi

  if [ "$APPLY_OK" = "1" ]; then
    echo "    APPLIED   $BASE -> $REL (via $METHOD)"
    note "APPLIED  $BASE  -> $REL  ($METHOD)"
  else
    note "FAIL     $BASE  -> $REL  ($METHOD failed)"
    RC=1
  fi
  echo ""
done

# no bare grep in the verdict: a non-matching grep returns 1 and kills the script under set -e
echo "=== summary ==="
if [ "${#REPORT[@]}" -eq 0 ]; then
  echo "nothing was selected. That is not a pass."
  exit 2
fi
for line in "${REPORT[@]}"; do
  echo "  $line"
done
echo ""

if [ "$RC" -ne 0 ]; then
  echo "PATCHES NOT FULLY APPLIED. Do not build a distributable image from this tree."
  exit 1
fi

case "$MODE" in
  check|list)
    echo "DRY RUN ONLY -- nothing was written."
    ;;
  *)
    echo "All selected patches are applied to $TREE_TOP."
    ;;
esac

echo ""
echo "THIS IS NOT PROOF THE IMAGE HAS THEM. An incremental build that does not touch a project"
echo "reuses that project's old output, so a patch can sit applied in the tree and be absent from"
echo "the image -- how the recovery strings and the boot logo each survived builds believed to"
echo "have fixed them. After the build, ask the artefact:"
echo ""
echo "    python3 tools/check_patches.py releases/\$BN/\$DEVICE-target_files.zip"
echo ""
exit 0
