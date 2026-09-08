#!/bin/bash
# Usage: image/scripts/prune_camera_extensions.sh [--check] [<ANDROID_BUILD_TOP>]; run after adevtool generate-all and before m.
set -uo pipefail

CHECK=0
if [ "${1:-}" = "--check" ]; then CHECK=1; shift; fi

TOP="${1:-${ANDROID_BUILD_TOP:-$PWD}}"
DEVICE="${DEVICE:-stallion}"

[ -d "$TOP" ] || { echo "prune_camera_extensions: no such directory: $TOP" >&2; exit 2; }

SYSCONFIG="$TOP/vendor/google_devices/$DEVICE/sysconfig"
if [ ! -d "$SYSCONFIG" ]; then
  echo "prune_camera_extensions: no $SYSCONFIG" >&2
  echo "  Either \$ANDROID_BUILD_TOP is wrong, or 'adevtool generate-all -d $DEVICE' has not run." >&2
  echo "  Refusing to report success about a tree that is not there." >&2
  exit 2
fi

TARGETS="
product/permissions/androidx.camera.extensions.impl.xml
system_ext/permissions/com.google.android.camerax.extensions.xml
product/sysconfig/preinstalled-packages-camera-services-base.xml
"

rc=0
present=0
removed=0
gone=0
for rel in $TARGETS; do
  f="$SYSCONFIG/$rel"
  if [ ! -e "$f" ]; then
    printf 'already gone  %s\n' "$rel"
    gone=$((gone + 1))
    continue
  fi
  present=$((present + 1))
  if [ "$CHECK" -eq 1 ]; then
    printf 'STILL PRESENT %s\n' "$rel"
    rc=1
    continue
  fi
  if rm -f "$f"; then
    printf 'removed       %s\n' "$rel"
    removed=$((removed + 1))
  else
    printf 'FAILED        %s (could not remove)\n' "$rel"
    rc=1
  fi
done

echo ""
if [ "$CHECK" -eq 1 ]; then
  if [ "$rc" -eq 0 ]; then
    echo "CHECK PASS: all 3 dead camera-extension sysconfig files are absent from $SYSCONFIG"
  else
    echo "CHECK FAIL: $present of 3 still present. Re-run this script without --check, before 'm'."
  fi
  exit "$rc"
fi

if [ "$rc" -ne 0 ]; then
  echo "FAILED: the tree was not fully pruned. Do not build."
  exit 1
fi
echo "pruned $removed file(s), $gone already absent, of 3."
echo "Now build. After signing, tools/check_debrand.py proves it from target_files."
exit 0
