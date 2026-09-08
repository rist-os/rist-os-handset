#!/usr/bin/env python3
"""Build the Rist replacements for the AOSP framework boot-logo assets."""
import io
import os
import sys
from PIL import Image, ImageDraw, ImageFont

_REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LOGO = os.path.join(_REPO, "app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png")
FONT = os.environ.get("RIST_BOOTANIM_FONT",
                      os.path.join(_REPO, "app/src/main/res/font/silkscreen_bold.ttf"))
HERE = os.path.dirname(os.path.abspath(__file__))

# Dimensions must match the assets being replaced.
MASK_W, MASK_H = 448, 605
SHINE_W, SHINE_H = 2048, 605

GLYPH_H = 300
GAP = 44
TITLE = "RistOS"
TITLE_PX = 66

CYAN = (92, 223, 228)


def build_mask():
    src = Image.open(LOGO).convert("RGBA")
    glyph = src.crop(src.getbbox())
    gw, gh = glyph.size
    glyph = glyph.resize((max(1, round(gw * GLYPH_H / gh)), GLYPH_H), Image.LANCZOS)

    # BASIC layout engine pinned: Raqm lays text out a pixel differently.
    font = ImageFont.truetype(FONT, TITLE_PX, layout_engine=ImageFont.Layout.BASIC)
    probe = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    l, t, r, b = probe.textbbox((0, 0), TITLE, font=font)
    tw, th = r - l, b - t

    ink = Image.new("L", (MASK_W, MASK_H), 0)
    block_h = glyph.size[1] + GAP + th
    top = (MASK_H - block_h) // 2
    ink.paste(glyph.getchannel("A"), ((MASK_W - glyph.size[0]) // 2, top))
    ImageDraw.Draw(ink).text(((MASK_W - tw) // 2 - l, top + glyph.size[1] + GAP - t),
                             TITLE, font=font, fill=255)

    mask = Image.new("RGBA", (MASK_W, MASK_H), (0, 0, 0, 255))
    mask.putalpha(Image.eval(ink, lambda v: 255 - v))
    return mask


def build_shine():
    shine = Image.new("RGB", (SHINE_W, SHINE_H))
    px = shine.load()
    for x in range(SHINE_W):
        t = x / (SHINE_W - 1)
        k = 0.72 + 0.28 * (1.0 - abs(2.0 * t - 1.0)) ** 2
        col = (int(CYAN[0] * k), int(CYAN[1] * k), int(CYAN[2] * k))
        for y in range(SHINE_H):
            px[x, y] = col
    return shine


def main():
    mask_p = os.path.join(HERE, "android-logo-mask.png")
    shine_p = os.path.join(HERE, "android-logo-shine.png")
    build_mask().save(mask_p, optimize=True)
    build_shine().save(shine_p, optimize=True)
    for p in (mask_p, shine_p):
        print("%s  %d bytes" % (p, os.path.getsize(p)))
    return 0


if __name__ == "__main__":
    sys.exit(main() or 0)
