#!/usr/bin/env python3
"""Fail on documentation that points at files this repository does not contain."""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SKIP_DIRS = {".git", "build", "build-out", ".gradle", ".claude", "releases", "flash", "node_modules"}

# Longest extension first, or "kt" matches inside "kts".
BARE = re.compile(r"(?<![\w/.-])((?:docs|scripts|tests|backend|image|tools|app|aosp)/[\w./-]+\.(?:md|sh|py|kts|kt|xml|bp|txt))(?![\w])")
LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")


def md_files():
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if fn.endswith(".md"):
                yield os.path.join(dirpath, fn)


def candidates(text):
    for m in LINK.finditer(text):
        t = m.group(1).split("#", 1)[0].strip()
        if not t or t.startswith(("http://", "https://", "mailto:", "#")):
            continue
        yield t, "link"
    for m in BARE.finditer(text):
        yield m.group(1), "path"


def main():
    exempt = set()

    files = sorted(md_files())
    if not files:
        print("scanned 0 markdown files under %s -- the walk found nothing, so nothing was "
              "checked. Refusing to report a clean tree." % ROOT)
        return 2
    docs_dir = os.path.join(ROOT, "docs")
    if os.path.isdir(docs_dir) and not any(f.startswith(docs_dir + os.sep) for f in files):
        print("docs/ exists but contributed no files to the scan -- the skip list has grown over "
              "a documentation directory. Refusing to report a clean tree.")
        return 2

    bad = []
    for f in files:
        if f in exempt:
            continue
        rel_dir = os.path.dirname(f)
        try:
            text = open(f, encoding="utf-8").read()
        except (OSError, UnicodeDecodeError):
            continue
        seen = set()
        for target, kind in candidates(text):
            if target in seen or "/.../" in target:
                continue
            seen.add(target)
            if os.path.exists(os.path.join(rel_dir, target)) or os.path.exists(os.path.join(ROOT, target)):
                continue
            bad.append((os.path.relpath(f, ROOT), target, kind))

    if bad:
        print("DANGLING REFERENCES (%d):" % len(bad))
        for f, t, kind in bad:
            print("  %-44s -> %s  (%s)" % (f, t, kind))
        print("\nEither bring the file into this repo or fix the reference.")
        return 1
    print("checked %d markdown files -- no dangling references" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
