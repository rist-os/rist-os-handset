#!/usr/bin/env bash
# Archive the kernel source a RistOS build shipped, for the GPLv2 corresponding-source offer.
set -eu

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Set before the trap is installed: the cleanup handler reads these under set -u.
STAGE=""
TARBALL=""
INCOMPLETE=0

die() { echo "ERROR: $*" >&2; exit 1; }

cleanup() {
    rc=$?
    if [ "$rc" -ne 0 ] && [ "$INCOMPLETE" -ne 1 ]; then
        echo "ERROR: aborting -- removing partial output so nothing incomplete can be mistaken" >&2
        echo "       for a compliance artifact. Fix the cause and re-run; this is idempotent." >&2
        if [ -n "$STAGE" ]; then rm -rf "$STAGE" || true; fi
        if [ -n "$TARBALL" ]; then rm -f "$TARBALL" "$TARBALL.sha256" || true; fi
    fi
    return $rc
}
trap cleanup EXIT

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$@"
    else
        shasum -a 256 "$@"
    fi
}

TREE="${1:-${GRAPHENE_TREE:-}}"
[ -n "$TREE" ] || die "usage: $0 <AOSP_TREE> [OUT_DIR]   (BN and RIST_KERNEL_SRC come from the environment)"
[ -d "$TREE" ] || die "not a directory: $TREE"
TREE="$(cd "$TREE" && pwd)"
[ -f "$TREE/build/envsetup.sh" ] || die "$TREE does not look like an AOSP tree (no build/envsetup.sh)"

# Not `: "${BN:?...}"`: on bash 3.2 that expansion error exits with status zero.
[ -n "${BN:-}" ] || die "set BN to the build number this archive belongs to, e.g. BN=2026081500"
case "$BN" in
    *[!0-9]*) die "BN='$BN' is not numeric -- use the same build number as the sign scripts" ;;
esac

DEVICE="${DEVICE:-stallion}"
OUT_DIR="${2:-$TREE/releases/$BN}"

for t in tar git find; do
    command -v "$t" >/dev/null 2>&1 || die "$t is not on PATH and is required to build the archive"
done

echo "tree=$TREE device=$DEVICE BN=$BN out=$OUT_DIR"

KSRC=""
KSRC_ORIGIN=""
if [ -n "${RIST_KERNEL_SRC:-}" ]; then
    [ -d "$RIST_KERNEL_SRC" ] || die "RIST_KERNEL_SRC is set but is not a directory: $RIST_KERNEL_SRC"
    KSRC="$(cd "$RIST_KERNEL_SRC" && pwd)"
    KSRC_ORIGIN="RIST_KERNEL_SRC"
elif [ -d "$TREE/kernel" ] && [ -n "$(ls -A "$TREE/kernel" 2>/dev/null || true)" ]; then
    KSRC="$TREE/kernel"
    KSRC_ORIGIN="in-tree kernel/"
else
    echo "ERROR: no kernel source found, and this script will not pretend otherwise." >&2
    echo "       Looked at: \$RIST_KERNEL_SRC (unset or empty) and $TREE/kernel (absent or empty)." >&2
    echo "" >&2
    echo "       On a GrapheneOS tree this is EXPECTED. The kernel is a prebuilt binary under" >&2
    echo "       device/google/<codename>-kernels/ and is built from a separate repository." >&2
    echo "       Clone it, check out the revision the prebuilts were built from, and point" >&2
    echo "       RIST_KERNEL_SRC at it -- see grapheneos.org/build, 'Building the kernel'." >&2
    echo "       For stallion: gitlab.com/grapheneos/kernel_pixel, cloned FULL (not --depth) and" >&2
    echo "       --recurse-submodules, at the commit the shipped modules name in their scmversion" >&2
    echo "       stamp -- on branch 17-stallion, which is UNTAGGED. Do NOT check out the release" >&2
    echo "       tag: it is on branch 17 and is a different kernel. NOT kernel_pixel_muzel either," >&2
    echo "       which is a different generation's 6.6 tree -- see the header of this script." >&2
    echo "" >&2
    die "kernel source is mandatory; a GPLv2 offer we cannot fulfil is worse than no archive"
fi
[ -n "$(ls -A "$KSRC" 2>/dev/null || true)" ] || die "kernel source directory is empty: $KSRC"
echo "kernel source: $KSRC  (via $KSRC_ORIGIN)"

NAME="kernel-source-$DEVICE-$BN"
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
STAGE="$OUT_DIR/.$NAME.stage"
rm -rf "$STAGE"
mkdir -p "$STAGE/config" "$STAGE/scripts" "$STAGE/binaries"

MF="$STAGE/MANIFEST.txt"
{
    echo "RistOS kernel corresponding-source archive"
    echo "=========================================="
    echo
    echo "Captured:        $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "Build number:    $BN"
    echo "Device:          $DEVICE"
    echo "Captured on:     $(uname -srm)"
    echo "AOSP tree:       (redacted)"
    echo "Kernel source:   $(basename "$KSRC")  (located via $KSRC_ORIGIN)"
    echo
    echo "This archive exists to satisfy GNU GPL version 2 section 3 for the Linux kernel binary"
    echo "shipped in RistOS build $BN. NOTICE records where the corresponding source for each build is published."
    echo
} > "$MF"

warn() { echo "WARNING: $*" >&2; echo "WARNING: $*" >> "$MF"; }

echo "=== [1/7] kernel source revision ==="
echo "--- kernel source revision ---" >> "$MF"

if git -C "$KSRC" rev-parse --git-dir >/dev/null 2>&1; then
    KSHA="$(git -C "$KSRC" rev-parse HEAD)"
    KDESC="$(git -C "$KSRC" describe --always --tags --dirty 2>/dev/null || echo '(no tags)')"
    echo "  HEAD $KSHA  ($KDESC)"
    {
        echo "HEAD:            $KSHA"
        echo "describe:        $KDESC"
        echo "remotes:"
        git -C "$KSRC" remote -v 2>/dev/null | sed 's/^/  /'
        echo "submodules (recursive):"
        git -C "$KSRC" submodule status --recursive 2>/dev/null | sed 's/^/  /'
    } >> "$MF"

    DIRTY="$(git -C "$KSRC" status --porcelain 2>/dev/null || true)"
    if [ -n "$DIRTY" ]; then
        warn "kernel checkout has uncommitted changes, so the HEAD sha above does NOT fully"
        warn "describe this source. source/ is the working tree as built, which is what actually"
        warn "matters; local-changes.patch records the tracked-file diff (untracked files appear"
        warn "in the list below but not in the patch -- they are in source/)."
        git -C "$KSRC" diff HEAD > "$STAGE/local-changes.patch" || true
        {
            echo "uncommitted files:"
            echo "$DIRTY" | sed 's/^/  /'
        } >> "$MF"
    else
        echo "clean:           yes (no uncommitted changes)" >> "$MF"
    fi
else
    warn "kernel source at $KSRC is not a git checkout, so the exact revision cannot be proven"
    warn "from the archive itself. Record its provenance by hand before relying on this."
    echo "HEAD:            UNKNOWN (not a git checkout)" >> "$MF"
fi
echo >> "$MF"

echo "=== [2/7] archived revision vs shipped modules ==="
echo "--- archived revision vs shipped modules ---" >> "$MF"

ko_scmversion() {
    if command -v modinfo >/dev/null 2>&1; then
        modinfo -F scmversion "$1" 2>/dev/null | head -1
    else
        strings -a "$1" 2>/dev/null | sed -n 's/^scmversion=\(g[0-9a-f]\{8,\}\).*/\1/p' | head -1
    fi
}

TFD="$TREE/out/target/product/$DEVICE/obj/PACKAGING/target_files_intermediates/$DEVICE-target_files"
KO_LIST=""
if [ -d "$TFD" ]; then
    KO_LIST="$(find "$TFD/VENDOR_KERNEL_BOOT" "$TFD/VENDOR_DLKM" "$TFD/SYSTEM_DLKM" \
        -type f -name '*.ko' 2>/dev/null || true)"
fi
if [ -z "$KO_LIST" ]; then
    KO_LIST="$(find "$TREE/device/google/$DEVICE-kernels" -type f -name '*.ko' 2>/dev/null || true)"
fi

if [ -z "$KO_LIST" ]; then
    warn "found no shipped kernel modules under $TREE, so the archived revision could NOT be"
    warn "checked against the binaries. This is the one check that catches a wrong-branch clone --"
    warn "verify the revision by hand before publishing."
else
    SCM_TALLY="$(echo "$KO_LIST" | while IFS= read -r k; do
        [ -n "$k" ] || continue
        v="$(ko_scmversion "$k")"
        [ -n "$v" ] && echo "$v"
    done | LC_ALL=C sort | uniq -c | LC_ALL=C sort -rn)"

    if [ -z "$SCM_TALLY" ]; then
        warn "no shipped module carries an scmversion stamp, so the archived revision could not be"
        warn "checked against the binaries. Verify the revision by hand before publishing."
    else
        KNOWN_REVS="$(
            git -C "$KSRC" rev-parse HEAD 2>/dev/null || true
            git -C "$KSRC" submodule status --recursive 2>/dev/null \
                | awk '{ r=$1; sub(/^[-+U]/,"",r); print r }'
        )"
        echo "modules stamped with a source revision:" >> "$MF"
        # The while-read pipeline runs in a subshell, so misses are accumulated in a file.
        MISSFILE="$STAGE/.scm-unmatched"
        : > "$MISSFILE"
        echo "$SCM_TALLY" | while IFS= read -r line; do
            [ -n "$line" ] || continue
            n="$(echo "$line" | awk '{print $1}')"
            v="$(echo "$line" | awk '{print $2}')"
            bare="${v#g}"
            hit=""
            for r in $KNOWN_REVS; do
                case "$r" in "$bare"*) hit="$r"; break ;; esac
            done
            if [ -n "$hit" ]; then
                if [ "$hit" = "${KSHA:-}" ]; then
                    echo "  $n module(s)  $v  -> superproject HEAD $hit"
                else
                    echo "  $n module(s)  $v  -> submodule $hit"
                fi
            else
                echo "  $n module(s)  $v  -> NO MATCH in this checkout"
                echo "$n $v" >> "$MISSFILE"
            fi
        done | tee -a "$MF"
        UNMATCHED="$(cat "$MISSFILE" 2>/dev/null || true)"
        rm -f "$MISSFILE"
        if [ -n "$UNMATCHED" ]; then
            echo "ERROR: the shipped kernel modules were built from a revision this checkout does" >&2
            echo "       not contain. Unaccounted-for scmversion stamps:" >&2
            echo "$UNMATCHED" | sed 's/^/         /' >&2
            echo "" >&2
            echo "       Checkout HEAD is: ${KSHA:-unknown}" >&2
            echo "       Submodules:" >&2
            git -C "$KSRC" submodule status --recursive 2>/dev/null | sed 's/^/         /' >&2
            echo "" >&2
            echo "       This is the wrong-branch failure. On stallion the Pixel-specific modules" >&2
            echo "       come from the SUPERPROJECT, whose stallion revision lives on branch" >&2
            echo "       17-stallion and is UNTAGGED -- a tag-based or shallow clone lands on" >&2
            echo "       branch 17 instead, which pins the same common/ack and therefore looks" >&2
            echo "       right if you only check the GKI modules. Re-clone full (not --depth)," >&2
            echo "         git fetch origin '+refs/heads/*:refs/remotes/origin/*'" >&2
            echo "       and check out the sha the modules above name, then re-run." >&2
            echo "" >&2
            die "refusing to archive source that is not the source of the shipped binaries"
        fi
    fi
fi
echo >> "$MF"

echo "=== [3/7] shipped kernel binaries ==="
echo "--- shipped kernel prebuilts (sha256) ---" >> "$MF"
KBIN_COUNT=0
BINLIST="$STAGE/binaries/kernel-prebuilts.sha256"
if [ -d "$TREE/device/google" ]; then
    BINS="$(find "$TREE/device/google" -type f -path '*kernel*' \
        \( -name 'Image' -o -name 'Image.*' -o -name '*.ko' -o -name '*.dtb' -o -name '*.dtbo' \) \
        2>/dev/null | LC_ALL=C sort || true)"
    if [ -n "$BINS" ]; then
        echo "$BINS" | while IFS= read -r b; do
            if [ -n "$b" ]; then
                sha256 "$b" | sed "s#$TREE/##"
            fi
        done > "$BINLIST"
        KBIN_COUNT="$(wc -l < "$BINLIST" | tr -d ' ')"
        echo "  hashed $KBIN_COUNT kernel prebuilt file(s)"
        {
            echo "files hashed:    $KBIN_COUNT (see binaries/kernel-prebuilts.sha256)"
            echo "prebuilt dirs:"
            echo "$BINS" | sed "s#$TREE/##; s#/[^/]*\$##" | LC_ALL=C sort -u | sed 's/^/  /'
        } >> "$MF"
        KIMG="$(echo "$BINS" | grep -E "/${DEVICE}-kernels/.*/Image(\.lz4|\.gz)?\$" | head -1 || true)"
        if [ -z "$KIMG" ]; then
            KIMG="$(echo "$BINS" | grep -E '/Image(\.lz4|\.gz)?$' | head -1 || true)"
            if [ -n "$KIMG" ]; then
                warn "no kernel Image under device/google/$DEVICE-kernels/. Copied ${KIMG#$TREE/}"
                warn "instead, which is ANOTHER DEVICE'S kernel image -- do not treat binaries/ as"
                warn "the binary this source corresponds to until that is explained."
            fi
        fi
        if [ -n "$KIMG" ]; then
            cp "$KIMG" "$STAGE/binaries/$(basename "$KIMG")"
            echo "kernel image:    ${KIMG#$TREE/}" >> "$MF"
            echo "                 (copied into binaries/$(basename "$KIMG"))" >> "$MF"
            echo "kernel version:  $(strings -n 8 "$KIMG" 2>/dev/null | grep -m1 -E '^[0-9]+\.[0-9]+\.[0-9]+-android' || echo '(not readable from the image)')" >> "$MF"
        fi
    fi
fi
if [ "$KBIN_COUNT" -eq 0 ]; then
    warn "found no kernel prebuilts under $TREE/device/google (path *kernel*). This archive cannot"
    warn "state which binary it corresponds to -- check the tree layout before shipping the build."
fi
echo >> "$MF"

echo "=== [4/7] kernel build configuration ==="
echo "--- kernel build configuration ---" >> "$MF"

# Newline-separated, not an array: bash 3.2 aborts on "${ARR[@]}" for an empty array under set -u.
find_configs() {
    echo "$TREE/out/target/product/$DEVICE/obj/KERNEL_OBJ/.config"
    echo "$KSRC/out/.config"
    find "$KSRC" -maxdepth 6 -type f \
        \( -name '.config' -o -name '*_defconfig' -o -name 'build.config*' \) 2>/dev/null || true
    if [ -d "$TREE/device/google" ]; then
        find "$TREE/device/google" -maxdepth 5 -type f \
            \( -name '.config' -o -name 'kernel-config*' \) 2>/dev/null || true
    fi
}
find_configs | LC_ALL=C sort -u | while IFS= read -r c; do
    if [ -n "$c" ] && [ -f "$c" ]; then
        # Strip both roots; $KSRC lives outside $TREE, and s#/#__#g below would flatten a leaked path.
        rel="${c#$TREE/}"; rel="${rel#$KSRC/}"
        case "$rel" in /*) rel="$(basename "$(dirname "$c")")/$(basename "$c")" ;; esac
        flat="$(printf '%s' "$rel" | sed 's#^/##; s#/#__#g')"
        cp "$c" "$STAGE/config/$flat"
    fi
done
CFG_COUNT="$(find "$STAGE/config" -type f | wc -l | tr -d ' ')"
echo "  captured $CFG_COUNT config file(s)"

_leaks="$(find "$STAGE" | sed "s#^$STAGE/*##" | grep -cE 'home__|__builder__|(^|/)home/[a-z]' || true)"
if [ "${_leaks:-0}" -gt 0 ]; then
    echo "  STAGED NAMES CARRY A BUILD PATH ($_leaks); refusing to archive:" >&2
    find "$STAGE" | sed "s#^$STAGE/*##" | grep -E 'home__|__builder__|(^|/)home/[a-z]' | head -5 >&2
    die "build paths in the staged tree -- fix the flatten before publishing a GPL artefact"
fi
echo "config files:    $CFG_COUNT" >> "$MF"
find "$STAGE/config" -type f 2>/dev/null | LC_ALL=C sort | sed "s#$STAGE/config/#  #" >> "$MF"

if [ "$CFG_COUNT" -eq 0 ]; then
    if [ "${RIST_ALLOW_INCOMPLETE:-0}" = "1" ]; then
        INCOMPLETE=1
        warn "NO kernel .config captured, and RIST_ALLOW_INCOMPLETE=1 was set. This archive is NOT"
        warn "complete corresponding source under GPLv2 section 3 and must not be sent to a"
        warn "requester in this state. It is stamped INCOMPLETE."
    else
        echo "ERROR: no kernel .config, defconfig or build.config found." >&2
        echo "       Source without the configuration it was built with does not reproduce our" >&2
        echo "       binary, so it is not the 'corresponding source' GPLv2 section 3 requires." >&2
        echo "       Looked in: $TREE/out/target/product/$DEVICE/obj/KERNEL_OBJ/," >&2
        echo "                  $KSRC, and $TREE/device/google/." >&2
        echo "       If the kernel was built elsewhere, copy its .config to $KSRC/out/ and re-run." >&2
        die "refusing to emit an archive that is not corresponding source (RIST_ALLOW_INCOMPLETE=1 overrides)"
    fi
fi
echo >> "$MF"

echo "=== [5/7] build and installation scripts ==="
echo "--- scripts used to control compilation and installation ---" >> "$MF"

find "$KSRC" -maxdepth 1 -type f -name '*.sh' 2>/dev/null | LC_ALL=C sort | while IFS= read -r s; do
    if [ -n "$s" ]; then
        cp "$s" "$STAGE/scripts/kernel__$(basename "$s")"
    fi
done

mkdir -p "$STAGE/scripts/rist-image"
cp "$HERE"/*.sh "$STAGE/scripts/rist-image/" 2>/dev/null || \
    warn "could not copy $HERE/*.sh into the archive"
SCRIPT_COUNT="$(find "$STAGE/scripts" -type f | wc -l | tr -d ' ')"
echo "  captured $SCRIPT_COUNT script(s)"
echo "scripts:         $SCRIPT_COUNT (kernel build wrappers + image/scripts/)" >> "$MF"

CLANG="$(find "$TREE/prebuilts/clang/host" -maxdepth 3 -type d -name 'clang-*' 2>/dev/null \
    | LC_ALL=C sort | tail -1)"
if [ -n "$CLANG" ]; then
    echo "toolchain:       ${CLANG#$TREE/}" >> "$MF"
else
    echo "toolchain:       (not found under prebuilts/clang/host)" >> "$MF"
fi
echo >> "$MF"

echo "=== [6/7] OS tree provenance ==="
echo "--- OS tree provenance ---" >> "$MF"
if command -v repo >/dev/null 2>&1 && [ -d "$TREE/.repo" ]; then
    if ( cd "$TREE" && repo manifest -r -o "$STAGE/manifest-snapshot.xml" >/dev/null 2>&1 ); then
        echo "  manifest-snapshot.xml written"
        echo "manifest:        manifest-snapshot.xml (repo manifest -r, every project pinned)" >> "$MF"
    else
        warn "repo manifest -r failed; there is no manifest snapshot in this archive"
    fi
else
    warn "repo is not on PATH, or $TREE has no .repo; no manifest snapshot in this archive"
fi

BP_FOUND=0
for BP in "$TREE/out/target/product/$DEVICE/build.prop" \
          "$TREE/out/target/product/$DEVICE/system/build.prop" \
          "$TREE/out/target/product/$DEVICE/vendor/build.prop"; do
    if [ -f "$BP" ]; then
        BP_FOUND=1
        echo "  ${BP#$TREE/}:" >> "$MF"
        grep -E '^ro\.(system\.|vendor\.|product\.)?build\.(fingerprint|id|version\.release|version\.security_patch)=' "$BP" \
            2>/dev/null | sed 's/^/    /' >> "$MF" || true
    fi
done
if [ "$BP_FOUND" -eq 0 ]; then
    echo "  (no build.prop yet -- run this after the build or the fingerprint goes unrecorded)" >> "$MF"
fi
echo >> "$MF"

echo "=== [7/7] archiving source (this is the slow part) ==="
mkdir -p "$STAGE/source"
( cd "$KSRC" && tar cf - . ) | ( cd "$STAGE/source" && tar xf - ) \
    || die "failed to copy the kernel source tree into the staging area"

SRC_FILES="$(find "$STAGE/source" -type f | wc -l | tr -d ' ')"
[ "$SRC_FILES" -gt 100 ] || \
    die "only $SRC_FILES file(s) landed in source/ -- that is not a kernel tree; refusing to continue"
echo "  source/: $SRC_FILES files"
{
    echo "--- archive contents ---"
    echo "source files:    $SRC_FILES"
} >> "$MF"

if [ "$INCOMPLETE" -eq 1 ]; then
    NAME="$NAME-INCOMPLETE"
    {
        echo
        echo "*** THIS ARCHIVE IS STAMPED INCOMPLETE -- see the WARNING lines above. It does not"
        echo "*** satisfy GPLv2 section 3 and must not be sent to a requester as corresponding"
        echo "*** source, nor published as though it were."
    } >> "$MF"
fi

echo "=== checksums ==="
( cd "$STAGE" && find . -type f ! -name 'SHA256SUMS' -print | LC_ALL=C sort | while IFS= read -r f; do
    sha256 "$f"
done ) > "$STAGE/SHA256SUMS"
echo "  SHA256SUMS: $(wc -l < "$STAGE/SHA256SUMS" | tr -d ' ') files"

echo "=== packing ==="
TARBALL="$OUT_DIR/$NAME.tar.gz"
rm -f "$TARBALL" "$TARBALL.sha256"
tar czf "$TARBALL" -C "$STAGE" . || die "tar failed writing $TARBALL"

tar tzf "$TARBALL" >/dev/null || die "the archive just written is not readable: $TARBALL"
( cd "$OUT_DIR" && sha256 "$NAME.tar.gz" ) > "$TARBALL.sha256"

rm -rf "$STAGE"
STAGE=""

echo
if [ "$INCOMPLETE" -eq 1 ]; then
    KEPT="$TARBALL"
    TARBALL=""
    echo "DONE, BUT INCOMPLETE: $KEPT"
    echo "  No kernel build configuration was captured, so this is NOT corresponding source under"
    echo "  GPLv2 section 3. Do not publish it and do not answer a source request with it."
    echo "  See MANIFEST.txt inside the archive."
    exit 3
fi

echo "PASS: kernel corresponding-source archive written"
echo "  $TARBALL"
echo "  $(cat "$TARBALL.sha256")"
echo
echo "Next: copy it off the build machine, publish it in the same release directory as the image, as NOTICE"
echo "describes, and retain it for three years past the last unit shipped from this build."
