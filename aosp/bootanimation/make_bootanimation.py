#!/usr/bin/env python3
"""Build a RistOS bootanimation.zip."""
import io
import os
import shutil
import subprocess
import sys
from PIL import Image, ImageDraw, ImageFont

_REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LOGO = os.path.join(_REPO, "app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png")
FONT = os.environ.get("RIST_BOOTANIM_FONT",
                      os.path.join(_REPO, "app/src/main/res/font/silkscreen_bold.ttf"))

W, H = 1080, 2424          # native panel
FPS = 30
FADE_FRAMES = 15           # ~0.5s fade-in
TITLE = "RistOS"

GLYPH_H = 440              # rendered height of the glyph in px
GAP = 96                   # glyph baseline -> wordmark
TITLE_PX = 104
BG = (0, 0, 0)

SOURCE_DATE_EPOCH = int(os.environ.get("SOURCE_DATE_EPOCH", "1600000000"))

HERE = os.path.dirname(os.path.abspath(__file__))
COMMITTED = os.path.join(HERE, "bootanimation.zip")

_args = sys.argv[1:]
VERIFY = "--verify" in _args
if VERIFY:
    _args.remove("--verify")
OUT = _args[0] if _args else "/tmp/bootanim"


def compose():
    src = Image.open(LOGO).convert("RGBA")
    glyph = src.crop(src.getbbox())
    gw, gh = glyph.size
    glyph = glyph.resize((max(1, round(gw * GLYPH_H / gh)), GLYPH_H), Image.LANCZOS)

    # Pin the layout engine: Raqm and BASIC lay the wordmark out a pixel differently.
    font = ImageFont.truetype(FONT, TITLE_PX, layout_engine=ImageFont.Layout.BASIC)
    probe = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    l, t, r, b = probe.textbbox((0, 0), TITLE, font=font)
    tw, th = r - l, b - t

    block_h = glyph.size[1] + GAP + th
    top = (H - block_h) // 2

    frame = Image.new("RGBA", (W, H), BG + (255,))
    frame.alpha_composite(glyph, ((W - glyph.size[0]) // 2, top))

    d = ImageDraw.Draw(frame)
    d.text(((W - tw) // 2 - l, top + glyph.size[1] + GAP - t),
           TITLE, font=font, fill=(255, 255, 255, 255))
    return frame.convert("RGB")


def verify(p0, p1):
    import zipfile

    fresh = {}
    for part, d in (("part0", p0), ("part1", p1)):
        for n in sorted(os.listdir(d)):
            fresh["%s/%s" % (part, n)] = Image.open(os.path.join(d, n)).convert("RGB").tobytes()

    bad = []
    with zipfile.ZipFile(COMMITTED) as z:
        have = set(n for n in z.namelist() if n.endswith(".png"))
        if have != set(fresh):
            bad.append("frame set differs: only-in-zip=%s only-in-fresh=%s"
                       % (sorted(have - set(fresh)), sorted(set(fresh) - have)))
        for name in sorted(have & set(fresh)):
            with z.open(name) as f:
                if Image.open(io.BytesIO(f.read())).convert("RGB").tobytes() != fresh[name]:
                    bad.append("pixels differ: " + name)
        with z.open("desc.txt") as f:
            want = "%d %d %d\np 1 0 part0\np 0 0 part1\n" % (W, H, FPS)
            if f.read().decode() != want:
                bad.append("desc.txt differs")

    if bad:
        print("VERIFY FAILED against %s" % COMMITTED)
        for b in bad:
            print("  " + b)
        return 1
    print("VERIFY OK -- %d frames, pixel-identical to %s" % (len(fresh), COMMITTED))
    return 0


def main():
    # OUT is recursively deleted below; refuse a path that is not ours.
    if os.path.exists(OUT) and not os.path.isfile(os.path.join(OUT, "desc.txt")):
        sys.exit("refusing to overwrite %s: not a previous output directory "
                 "(no desc.txt). Pass a fresh path." % OUT)
    shutil.rmtree(OUT, ignore_errors=True)
    p0, p1 = os.path.join(OUT, "part0"), os.path.join(OUT, "part1")
    os.makedirs(p0)
    os.makedirs(p1)

    full = compose()
    black = Image.new("RGB", (W, H), BG)

    for i in range(FADE_FRAMES):
        a = ((i + 1) / FADE_FRAMES) ** 0.65
        Image.blend(black, full, a).save(
            os.path.join(p0, "%05d.png" % i), optimize=True)

    full.save(os.path.join(p1, "00000.png"), optimize=True)

    # p <count> <pause> <path>  --  count 0 == loop forever
    with open(os.path.join(OUT, "desc.txt"), "w") as f:
        f.write("%d %d %d\np 1 0 part0\np 0 0 part1\n" % (W, H, FPS))

    if VERIFY:
        return verify(p0, p1)

    zip_path = os.path.join(OUT, "bootanimation.zip")
    names = ["desc.txt"]
    for part in ("part0", "part1"):
        names += ["%s/%s" % (part, n)
                  for n in sorted(os.listdir(os.path.join(OUT, part)))]

    # Pin every mtime and run zip under TZ=UTC: zip writes DOS timestamps in local time.
    for n in names:
        os.utime(os.path.join(OUT, n), (SOURCE_DATE_EPOCH, SOURCE_DATE_EPOCH))

    # -0 = STORED (bootanimation rejects a deflated zip); -X drops uid/gid.
    env = dict(os.environ, TZ="UTC")
    subprocess.run(["/usr/bin/zip", "-0", "-X", "-q", "-@", zip_path],
                   cwd=OUT, input="\n".join(names), text=True, check=True, env=env)
    print(zip_path, os.path.getsize(zip_path), "bytes")


if __name__ == "__main__":
    sys.exit(main() or 0)
