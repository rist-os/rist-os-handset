#!/usr/bin/env python3
"""Guard every theme's text against its own background."""
import re
import sys
import pathlib

MIN = 4.5
APP = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/java/watch/rist/assistant"
SRC = APP / "Theme.kt"
VOICEMAIL_SRC = APP / "VoicemailActivity.kt"


def rgb(h):
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def luminance(c):
    def ch(v):
        x = v / 255.0
        return x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4
    r, g, b = c
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def blend(a, b, f):
    # int() truncation, matching Kotlin's .toInt().
    return tuple(int(a[i] * (1 - f) + b[i] * f) for i in range(3))


def readable_on(frm, toward, bg):
    if contrast(frm, bg) >= MIN:
        return frm
    best, f = frm, 0.1
    while f <= 1.0001:
        best = blend(frm, toward, f)
        if contrast(best, bg) >= MIN:
            return best
        f += 0.1
    return best


def on_accent(ground, ink, accent):
    return ground if contrast(ground, accent) >= contrast(ink, accent) else ink


def voicemail_card_mix(text):
    m = re.search(r"blendARGB\(\s*t\.ground\s*,\s*t\.ink\s*,\s*([0-9.]+)f\s*\)", text)
    if not m:
        raise SystemExit(
            "FAIL: could not find the voicemail card's blendARGB(t.ground, t.ink, ...) in %s.\n"
            "The checker is stale, not the palette -- find where that card gets its face now."
            % VOICEMAIL_SRC.name
        )
    return float(m.group(1))


HEX = r'c\("(#[0-9A-Fa-f]{6})"\)'
PAT = re.compile(
    r'RistTheme\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*'
    + HEX + r'\s*,\s*' + HEX + r'\s*,\s*' + HEX + r'\s*,\s*'   # ground, ink, inkMuted
    + HEX + r'\s*,\s*' + HEX + r'\s*,\s*'                      # tileFill, tileBorder
    r'[0-9.]+f\s*,\s*[0-9.]+f\s*,\s*'                          # tileRadiusDp, borderWidthDp
    + HEX,                                                     # accent
    re.S)


ACCEPTED = {
    ("ledger", "accent on voicemail card"): (
        4.03,
        "paint that card in t.tileFill like every other card -> 5.03:1 (VoicemailActivity.kt)",
    ),
}

# How far under a recorded shortfall still counts as the same shortfall.
DRIFT = 0.02

src = SRC.read_text()
VM_MIX = voicemail_card_mix(VOICEMAIL_SRC.read_text())
src = re.sub(r"//[^\n]*", "", src)

themes = PAT.findall(src)
declared = len(re.findall(r'RistTheme\("', src))
if not themes:
    print("FAIL: parsed no themes out of Theme.kt -- the checker is stale, not the palette")
    sys.exit(2)
if len(themes) != declared:
    got = {t[0] for t in themes}
    missed = [m for m in re.findall(r'RistTheme\("([^"]+)"', src) if m not in got]
    print("FAIL: %d themes declared but only %d parsed; missed: %s"
          % (declared, len(themes), ", ".join(missed)))
    print("The checker is stale -- fix the pattern before trusting a green run.")
    sys.exit(2)

fails = []
waived = []
stale = []
checked = 0

for tid, name, ground, ink, muted, fill, border, accent in themes:
    g, i, m = rgb(ground), rgb(ink), rgb(muted)
    f, a = rgb(fill), rgb(accent)
    vm = blend(g, i, VM_MIX)
    oa = on_accent(g, i, a)
    pairs = [
        ("ink on ground", ink, ground, contrast(i, g)),
        ("muted on ground", muted, ground, contrast(readable_on(m, i, g), g)),
        ("muted on tileFill", muted, fill, contrast(readable_on(m, i, f), f)),
        ("accent on ground", accent, ground, contrast(a, g)),
        ("accent on tileFill", accent, fill, contrast(a, f)),
        ("accent on voicemail card", accent, "#%02X%02X%02X" % vm, contrast(a, vm)),
        ("ground on accent", ground, accent, contrast(g, a)),
        ("onAccent on accent", "#%02X%02X%02X" % oa, accent, contrast(oa, a)),
    ]
    for label, fg, bg, ratio in pairs:
        checked += 1
        entry = ACCEPTED.get((tid, label))
        recorded, remedy = entry if entry is not None else (None, "")
        line = "  %-10s %-24s %s on %s = %.2f:1" % (tid, label, fg, bg, ratio)
        if ratio >= MIN:
            if recorded is not None:
                stale.append("%s (waived at %.2f:1, now passes -- delete the waiver)"
                             % (line, recorded))
            continue
        if recorded is not None and ratio >= recorded - DRIFT:
            waived.append("%s  [accepted; recorded at %.2f:1]\n      fix: %s"
                          % (line, recorded, remedy))
        elif recorded is not None:
            fails.append("%s  [WORSE than the %.2f:1 that was accepted]" % (line, recorded))
        else:
            fails.append(line)

print("checked %d pairs across %d themes against %s:1" % (checked, len(themes), MIN))

if waived:
    print("KNOWN SHORTFALLS (accepted, not fixed):")
    print("\n".join(waived))

if stale:
    print("STALE WAIVERS -- the checker is out of date, not the palette:")
    print("\n".join(stale))
    sys.exit(2)

if fails:
    print("CONTRAST FAILURES:")
    print("\n".join(fails))
    sys.exit(1)

print("all themes pass" if not waived else "no new contrast failures")
