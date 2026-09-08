#!/usr/bin/env python3
"""Usage: tools/check_xml.py [path ...]   (default: the whole repository)"""
import os
import sys
from xml.parsers import expat

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SKIP_DIRS = {"build", "build-out", "out", "releases", "flash", "node_modules", "__pycache__"}

EXTENSIONS = (".xml", ".xsd", ".xsl", ".svg")

ANCHORS = ("app/src/main/res", "aosp")


def walk(roots):
    for root in roots:
        if os.path.isfile(root):
            yield root
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = sorted(
                d for d in dirnames if d not in SKIP_DIRS and not d.startswith(".")
            )
            for fn in sorted(filenames):
                if fn.endswith(EXTENSIONS):
                    yield os.path.join(dirpath, fn)


def comment_faults(text):
    faults = []
    i = 0
    while True:
        start = text.find("<!--", i)
        if start < 0:
            return faults
        end = text.find("-->", start + 4)
        if end < 0:
            faults.append((start, "comment opened here is never closed with -->"))
            return faults
        body = text[start + 4:end]
        hit = body.find("--")
        if hit >= 0:
            faults.append((start + 4 + hit,
                           "double hyphen (--) inside an XML comment: XML forbids it in the body"))
        elif body.endswith("-"):
            faults.append((end - 1,
                           "comment ends with --->: a comment body may not end in a hyphen"))
        i = end + 3


def line_col(text, offset):
    line = text.count("\n", 0, offset) + 1
    col = offset - (text.rfind("\n", 0, offset) + 1) + 1
    return line, col


def report(path, line, col, reason, text):
    rel = os.path.relpath(path, ROOT)
    print("%s:%d:%d: %s" % (rel, line, col, reason))
    lines = text.splitlines()
    if 1 <= line <= len(lines):
        print("    %s" % lines[line - 1].replace("\t", " ").rstrip())
        print("    %s^" % (" " * max(col - 1, 0)))


def check(path):
    try:
        with open(path, "rb") as fh:
            raw = fh.read()
    except OSError as err:
        print("%s: unreadable: %s" % (os.path.relpath(path, ROOT), err))
        return False
    try:
        expat.ParserCreate().Parse(raw, True)
        return True
    except expat.ExpatError as err:
        text = raw.decode("utf-8", "replace")
        detail = "%s (expat, line %d column %d)" % (
            expat.ErrorString(err.code), err.lineno, err.offset + 1)
        faults = comment_faults(text)
        if faults:
            offset, reason = faults[0]
            line, col = line_col(text, offset)
            report(path, line, col, reason, text)
            print("    parser said: %s" % detail)
        else:
            report(path, err.lineno, err.offset + 1, detail, text)
        return False


def main(argv):
    roots = argv[1:] or [ROOT]
    for r in roots:
        if not os.path.exists(r):
            print("no such path: %s" % r)
            return 2

    files = list(walk(roots))

    if not files:
        print("scanned 0 XML files under %s -- the walk found nothing, so nothing was checked."
              % ", ".join(roots))
        return 2
    if not argv[1:]:
        for anchor in ANCHORS:
            full = os.path.join(ROOT, anchor)
            if os.path.isdir(full) and not any(f.startswith(full + os.sep) for f in files):
                print("%s exists but contributed no files to the scan -- the skip list has grown "
                      "over a source directory. Refusing to report a clean tree." % anchor)
                return 2

    bad = 0
    for path in files:
        if not check(path):
            bad += 1

    if bad:
        print()
        print("%d of %d XML files will not parse. Fix them before building -- aapt2 and the "
              "resource merger reject exactly these." % (bad, len(files)))
        return 1
    print("%d XML files parse clean" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
