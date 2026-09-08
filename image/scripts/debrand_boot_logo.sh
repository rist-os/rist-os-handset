#!/usr/bin/env bash
# Usage: debrand_boot_logo.sh install|verify <AOSP_TREE>   |   debrand_boot_logo.sh selftest
set -eu

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/../../aosp/bootlogo"
REL="frameworks/base/core/res/assets/images"

die() { echo "ERROR: $*" >&2; exit 1; }

MODE="${1:-}"
TREE="${2:-}"
[ -n "$MODE" ] || die "usage: $0 install|verify <AOSP_TREE>   |   $0 selftest"
if [ "$MODE" != "selftest" ]; then
    [ -n "$TREE" ] || die "usage: $0 install|verify <AOSP_TREE>   |   $0 selftest"
    [ -d "$TREE" ] || die "not a directory: $TREE"
fi

for f in android-logo-mask.png android-logo-shine.png; do
    [ -f "$SRC/$f" ] || die "missing source asset $SRC/$f (run aosp/bootlogo/make_boot_logo.py)"
done

case "$MODE" in
install)
    DST="$TREE/$REL"
    [ -d "$DST" ] || die "no $REL in $TREE -- is this an AOSP checkout?"

    for f in android-logo-mask.png android-logo-shine.png; do
        [ -f "$DST/$f" ] || die "$REL/$f absent; the fallback logo moved -- re-check BootAnimation.cpp"
        echo "  replacing $f (was $(shasum -a 256 "$DST/$f" | cut -c1-16)...)"
        cp "$SRC/$f" "$DST/$f"
        echo "       with $f      ($(shasum -a 256 "$DST/$f" | cut -c1-16)...)"
    done
    echo "OK: boot logo assets replaced in $TREE/$REL"
    ;;

verify)
    fail=0
    # Three states: exit 0 clean, 1 dirty, 2 could-not-tell; PASS prints only when the built-image checks ran.
    unchecked=0

    for f in android-logo-mask.png android-logo-shine.png; do
        a="$(shasum -a 256 "$SRC/$f" | awk '{print $1}')"
        b="$(shasum -a 256 "$TREE/$REL/$f" 2>/dev/null | awk '{print $1}' || true)"
        if [ "$a" != "$b" ]; then
            echo "FAIL: $REL/$f is not ours -- run '$0 install $TREE'"
            fail=1
        fi
    done

    OUT="${ANDROID_PRODUCT_OUT:-}"
    FW=""
    if [ -n "$OUT" ] && [ -f "$OUT/system/framework/framework-res.apk" ]; then
        FW="$OUT/system/framework/framework-res.apk"
    fi
    if [ -n "$FW" ]; then
        tmp="$(mktemp -d)"
        trap 'rm -rf "$tmp"' EXIT
        # unzip exit 11 is "no matching files"; any other non-zero status is a real failure.
        # The status must be captured via if/else: a bare failing command aborts under set -eu.
        for f in android-logo-mask.png android-logo-shine.png; do
            rm -f "$tmp/$f"
            if unzip -o -q -j "$FW" "assets/images/$f" -d "$tmp"; then
                urc=0
            else
                urc=$?
            fi
            if [ "$urc" -eq 0 ]; then
                built="$(shasum -a 256 "$tmp/$f" | awk '{print $1}')"
                ours="$(shasum -a 256 "$SRC/$f" | awk '{print $1}')"
                if [ "$built" = "$ours" ]; then
                    echo "ok:   built framework-res carries the Rist $f"
                else
                    echo "FAIL: built framework-res carries a $f that is NOT ours."
                    echo "      built=$built"
                    echo "      ours =$ours"
                    fail=1
                fi
            elif [ "$urc" -eq 11 ]; then
                echo "UNCHECKED: assets/images/$f is not in the built framework-res.apk."
                echo "      That is not 'the logo is gone'. A rebase may have MOVED the fallback"
                echo "      artwork, in which case it is still in there under another name."
                echo "      Entries that look related:"
                related="$(unzip -Z1 "$FW" 2>/dev/null | grep -i 'android-logo' || true)"
                if [ -n "$related" ]; then
                    printf '%s\n' "$related" | sed 's/^/        /'
                else
                    echo "        (none -- the archive lists no android-logo* entry at all)"
                fi
                echo "      Read BootAnimation.cpp before calling this image clean."
                unchecked=1
            else
                echo "UNCHECKED: could not read $FW (unzip exit $urc; see its message above)."
                echo "      Whether the GrapheneOS logo is in there is UNKNOWN. This is not a pass."
                unchecked=1
            fi
        done
    else
        echo "UNCHECKED: no framework-res.apk to inspect."
        if [ -z "$OUT" ]; then
            echo "      ANDROID_PRODUCT_OUT is not set, so this ran in a shell where lunch was never"
            echo "      sourced. Run:  source build/envsetup.sh && lunch stallion-cur-user"
        else
            echo "      ANDROID_PRODUCT_OUT=$OUT but $OUT/system/framework/framework-res.apk is absent."
            echo "      The build has not staged it, so there is nothing to inspect yet."
        fi
        echo "      This is the check that actually matters -- checks 3 and 4 below need the same"
        echo "      variable, so NOTHING about the built image has been verified by this run."
        unchecked=1
    fi

    if [ -z "$OUT" ]; then
        echo "UNCHECKED: duplicate-destination check needs ANDROID_PRODUCT_OUT."
        unchecked=1
    elif [ ! -f "$OUT/product_copy_files_ignored.txt" ]; then
        echo "UNCHECKED: no $OUT/product_copy_files_ignored.txt -- the build has not written it,"
        echo "      so whether our bootanimation lost a duplicate-destination fight is unknown."
        unchecked=1
    elif grep -q 'media/bootanimation' "$OUT/product_copy_files_ignored.txt"; then
        echo "FAIL: our bootanimation was dropped as a duplicate destination:"
        grep 'media/bootanimation' "$OUT/product_copy_files_ignored.txt" | sed 's/^/      /'
        fail=1
    else
        # Not anchored at ^product/: the resolved destination may be spelled system/product/media/...
        echo "ok:   bootanimation was not dropped as a duplicate destination"
    fi

    if [ -z "$OUT" ]; then
        echo "UNCHECKED: bootanimation.zip check needs ANDROID_PRODUCT_OUT."
        unchecked=1
    else
        ANIM_SRC="$HERE/../../aosp/bootanimation/bootanimation.zip"
        if [ ! -f "$ANIM_SRC" ]; then
            echo "UNCHECKED: $ANIM_SRC is missing from this checkout, so there is nothing to"
            echo "      compare the installed animations against."
            unchecked=1
            anim_ours=""
        else
            anim_ours="$(shasum -a 256 "$ANIM_SRC" | awk '{print $1}')"
        fi
        for f in bootanimation.zip bootanimation-dark.zip; do
            p="$OUT/product/media/$f"
            if [ ! -f "$p" ]; then
                echo "FAIL: /product/media/$f missing"
                echo "      ro.boot.theme=1 REPLACES the light name in bootanimation's search list"
                echo "      rather than falling back to it, and Pixels set that property."
                fail=1
                continue
            fi
            aok=1
            if [ -n "$anim_ours" ]; then
                got="$(shasum -a 256 "$p" | awk '{print $1}')"
                if [ "$got" != "$anim_ours" ]; then
                    echo "FAIL: /product/media/$f is not the animation in this repo."
                    echo "      built=$got"
                    echo "      ours =$anim_ours"
                    echo "      A duplicate PRODUCT_COPY_FILES destination is not a build error;"
                    echo "      the FIRST destination wins and ours is only logged. Stock Pixel"
                    echo "      factory images do ship a /product/media/bootanimation.zip."
                    fail=1; aok=0
                fi
            fi
            if ! unzip -l "$p" >/dev/null 2>&1; then
                echo "FAIL: /product/media/$f is not a readable zip."
                fail=1; aok=0
            else
                if ! unzip -l "$p" 2>/dev/null | grep -q 'desc\.txt'; then
                    echo "FAIL: /product/media/$f has no desc.txt -- bootanimation cannot play it."
                    fail=1; aok=0
                fi
                # unzip -v prints a Method column: Stored, or Defl:N for deflated entries.
                if unzip -v "$p" 2>/dev/null | grep -q 'Defl'; then
                    echo "FAIL: /product/media/$f has deflated (non-STORED) entries:"
                    unzip -v "$p" 2>/dev/null | grep 'Defl' | head -5 | sed 's/^/      /'
                    echo "      preloadZip skips those silently. The part plays zero frames and"
                    echo "      the GrapheneOS fallback logo draws instead."
                    fail=1; aok=0
                fi
            fi
            # An `[ ... ] && echo` one-liner here would abort the script under set -e.
            if [ "$aok" -eq 1 ]; then
                echo "ok:   /product/media/$f is ours, STORED, and has a desc.txt"
            fi
        done
    fi

    # FAIL first: "dirty" and "could not look" must never print the same last line.
    [ "$fail" -eq 0 ] || die "boot-logo debranding is NOT clean -- do not ship this image"
    if [ "$unchecked" -ne 0 ]; then
        echo ""
        echo "INCONCLUSIVE -- this is NOT a pass."
        echo "The source assets in the tree are ours, but one or more checks against the BUILT image"
        echo "could not run (see the UNCHECKED lines above). The built image is what ships, and"
        echo "nothing here has established anything about it."
        echo ""
        echo "Re-run after 'm', in a shell with lunch sourced:"
        echo "    source build/envsetup.sh && lunch stallion-cur-user"
        echo "    $0 verify $TREE"
        exit 2
    fi
    echo "PASS: no GrapheneOS boot logo in this image"
    ;;

selftest)
    # Exit codes asserted: 0 clean, 1 dirty, 2 could-not-tell.
    command -v zip >/dev/null 2>&1 || die "selftest needs the 'zip' command"
    ANIM_SRC="$HERE/../../aosp/bootanimation/bootanimation.zip"
    [ -f "$ANIM_SRC" ] || die "selftest needs $ANIM_SRC"

    st_fail=0
    ROOT="$(mktemp -d)"
    trap 'rm -rf "$ROOT"' EXIT

    make_fixture() {
        # make_fixture <dir>
        local d="$1"
        rm -rf "$d"; mkdir -p "$d/tree/$REL" "$d/out/system/framework" "$d/out/product/media"
        cp "$SRC/android-logo-mask.png"  "$d/tree/$REL/"
        cp "$SRC/android-logo-shine.png" "$d/tree/$REL/"
        local stage="$d/.apk"
        rm -rf "$stage"; mkdir -p "$stage/assets/images"
        cp "$SRC/android-logo-mask.png"  "$stage/assets/images/"
        cp "$SRC/android-logo-shine.png" "$stage/assets/images/"
        ( cd "$stage" && zip -q -r -X "$d/out/system/framework/framework-res.apk" assets )
        cp "$ANIM_SRC" "$d/out/product/media/bootanimation.zip"
        cp "$ANIM_SRC" "$d/out/product/media/bootanimation-dark.zip"
        : > "$d/out/product_copy_files_ignored.txt"
    }

    repack_apk() {
        # repack_apk <dir> <stagedir>
        rm -f "$1/out/system/framework/framework-res.apk"
        ( cd "$2" && zip -q -r -X "$1/out/system/framework/framework-res.apk" assets )
    }

    run_case() {
        # run_case <name> <expected-rc> <expected-substring> <dir> [OUT-override]
        local name="$1" want_rc="$2" want_txt="$3" d="$4" outdir="${5-$4/out}"
        local out rc
        set +e
        out="$(ANDROID_PRODUCT_OUT="$outdir" bash "$HERE/$(basename "${BASH_SOURCE[0]}")" \
                 verify "$d/tree" 2>&1)"
        rc=$?
        set -e
        if [ "$rc" = "$want_rc" ] && printf '%s' "$out" | grep -qF "$want_txt"; then
            printf '%-52s rc=%s  OK\n' "$name" "$rc"
        else
            printf '%-52s rc=%s (want %s)  *** MISMATCH ***\n' "$name" "$rc" "$want_rc"
            echo "    wanted substring: $want_txt"
            printf '%s\n' "$out" | sed 's/^/    | /'
            st_fail=$((st_fail + 1))
        fi
    }

    D="$ROOT/c"; make_fixture "$D"
    run_case "clean image passes" 0 "PASS: no GrapheneOS boot logo" "$D"

    D="$ROOT/notinstalled"; make_fixture "$D"
    printf 'their hexagon' > "$D/tree/$REL/android-logo-mask.png"
    run_case "install never ran (tree asset is theirs)" 1 "is not ours" "$D"

    D="$ROOT/builtdirty"; make_fixture "$D"
    S="$D/.apk"; printf 'their hexagon' > "$S/assets/images/android-logo-mask.png"
    repack_apk "$D" "$S"
    run_case "built framework-res carries THEIR mask" 1 "android-logo-mask.png that is NOT ours" "$D"

    D="$ROOT/shineonly"; make_fixture "$D"
    S="$D/.apk"; printf 'their shine' > "$S/assets/images/android-logo-shine.png"
    repack_apk "$D" "$S"
    run_case "built framework-res carries THEIR shine" 1 "android-logo-shine.png that is NOT ours" "$D"

    D="$ROOT/noentry"; make_fixture "$D"
    S="$D/.apk"; rm -f "$S/assets/images/android-logo-mask.png"
    repack_apk "$D" "$S"
    run_case "logo entry absent from framework-res" 2 "is not in the built framework-res.apk" "$D"

    D="$ROOT/badapk"; make_fixture "$D"
    printf 'not a zip' > "$D/out/system/framework/framework-res.apk"
    run_case "framework-res unreadable" 2 "UNCHECKED" "$D"

    D="$ROOT/nolunch"; make_fixture "$D"
    run_case "ANDROID_PRODUCT_OUT unset" 2 "INCONCLUSIVE" "$D" ""

    D="$ROOT/dropped"; make_fixture "$D"
    echo 'system/product/media/bootanimation.zip' > "$D/out/product_copy_files_ignored.txt"
    run_case "dropped duplicate, system/product/ spelling" 1 "dropped as a duplicate destination" "$D"

    D="$ROOT/nodark"; make_fixture "$D"
    rm -f "$D/out/product/media/bootanimation-dark.zip"
    run_case "bootanimation-dark.zip missing" 1 "bootanimation-dark.zip missing" "$D"

    D="$ROOT/notours"; make_fixture "$D"
    ( cd "$ROOT" && rm -rf stockanim && mkdir stockanim && echo '1080 2400 30' > stockanim/desc.txt \
        && cd stockanim && zip -q -r -X -0 "$D/out/product/media/bootanimation.zip" desc.txt )
    run_case "stock animation won the copy race" 1 "is not the animation in this repo" "$D"

    D="$ROOT/deflated"; make_fixture "$D"
    ( cd "$ROOT" && rm -rf defanim && mkdir -p defanim/part0 && echo '1080 2400 30' > defanim/desc.txt \
        && head -c 40000 /dev/zero > defanim/part0/0001.png \
        && cd defanim && rm -f "$D/out/product/media/bootanimation.zip" \
        && zip -q -r -X -9 "$D/out/product/media/bootanimation.zip" desc.txt part0 )
    run_case "animation deflated instead of STORED" 1 "non-STORED" "$D"

    D="$ROOT/zerobyte"; make_fixture "$D"
    : > "$D/out/product/media/bootanimation.zip"
    run_case "zero-byte bootanimation.zip" 1 "not a readable zip" "$D"

    echo ""
    if [ "$st_fail" -ne 0 ]; then
        die "SELFTEST FAILED: $st_fail case(s) did not behave as asserted."
    fi
    echo "SELFTEST PASS: every check in 'verify' was shown to be able to FAIL, to report"
    echo "               'could not tell', and to PASS."
    ;;

*)
    die "unknown mode '$MODE' (expected install, verify or selftest)"
    ;;
esac
