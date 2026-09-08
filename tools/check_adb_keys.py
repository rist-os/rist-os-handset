#!/usr/bin/env python3
"""Decide, from an artefact, whether it carries a pre-authorised adb key."""

import os
import re
import shutil
import stat
import sys
import tempfile
import zipfile

TF_ROOTS = ("META/", "SYSTEM/", "PRODUCT/", "VENDOR/", "SYSTEM_EXT/", "ODM/", "ROOT/",
            "BOOT/", "RECOVERY/", "VENDOR_BOOT/", "IMAGES/", "SYSTEM_DLKM/", "VENDOR_DLKM/")
TREE_DIRS = ("system", "product", "vendor", "system_ext", "odm", "root", "boot", "recovery")

# A key line is "<base64 blob> <user@host>"; real blobs are ~380 base64 characters.
KEYISH = re.compile(r"^[A-Za-z0-9+/=]{100,}$")


class Report(object):
    def __init__(self, quiet=False):
        self.findings = []
        self.unknowns = []
        self.quiet = quiet

    def ok(self, msg):
        if not self.quiet:
            print("ok         %s" % msg)

    def note(self, msg):
        if not self.quiet:
            print("           %s" % msg)

    def fail(self, msg):
        self.findings.append(msg)
        print("FAIL       %s" % msg)

    def unknown(self, msg):
        self.unknowns.append(msg)
        print("UNCHECKED  %s" % msg)

    def verdict(self):
        if self.unknowns:
            print("ADB_KEYS UNCHECKED -- nothing has been established about this artefact.")
            return 2
        if self.findings:
            print("ADB_KEYS FAIL")
            return 1
        print("ADB_KEYS PASS")
        return 0


class Hit(object):
    def __init__(self, path, kind, size, lines):
        self.path = path        # as it appears in the artefact
        self.kind = kind        # "file" | "symlink"
        self.size = size
        self.lines = lines      # decoded text lines, or None if not read


def read_expect_key(path, rep):
    if not os.path.isfile(path):
        rep.unknown("--expect-key %s does not exist, so there is nothing to compare against."
                    % path)
        rep.note("aosp/adb_keys is gitignored; put your ~/.android/adbkey.pub there.")
        return None
    try:
        with open(path, "rb") as f:
            blob = f.read()
    except Exception as e:
        rep.unknown("--expect-key %s could not be read (%s)" % (path, e))
        return None
    for line in blob.decode("utf-8", "replace").splitlines():
        line = line.strip()
        if not line:
            continue
        field1 = line.split()[0]
        if not KEYISH.match(field1):
            rep.unknown("--expect-key %s: the first non-blank line's first field is not a key "
                        "(%r...)" % (path, field1[:24]))
            return None
        return field1
    rep.unknown("--expect-key %s is empty, so 'the image carries this key' would be a statement "
                "about the empty string." % path)
    return None


def hits_from_zip(path, rep):
    try:
        z = zipfile.ZipFile(path)
        infos = z.infolist()
    except Exception as e:
        rep.unknown("%s is not a readable zip (%s). Nothing was examined." % (path, e))
        return None

    members = [i for i in infos if not i.filename.endswith("/")]
    if not members:
        rep.unknown("%s lists ZERO entries. An empty listing is a broken read, never a clean "
                    "image." % path)
        return None

    names = [i.filename for i in members]
    looks_tf = any(n.startswith(TF_ROOTS) for n in names)
    if not looks_tf:
        imgs = [n for n in names if n.endswith(".img")]
        if imgs:
            rep.unknown("%s is a factory/install zip (%d .img files, no partition directories)."
                        % (path, len(imgs)))
            rep.note("product/etc/security/adb_keys lives INSIDE super.img, which a zip listing")
            rep.note("cannot see. Absence of the name here would prove nothing at all.")
            rep.note("Run this against the target_files.zip this artefact was built from.")
        else:
            rep.unknown("%s does not look like a target_files package: none of its %d entries is "
                        "under %s" % (path, len(names), "/, ".join(TF_ROOTS[:6]) + "/, ..."))
            rep.note("This checker answers questions about an image's staged files. Point it at")
            rep.note("the target_files.zip, or at an out/target/product/<device> tree.")
        return None

    hits = []
    for i in members:
        if os.path.basename(i.filename) != "adb_keys":
            continue
        mode = i.external_attr >> 16
        # mode 0 means the zip recorded no unix permissions; a symlink then counts as a file.
        kind = "symlink" if (mode and stat.S_ISLNK(mode)) else "file"
        lines = None
        if kind == "file":
            try:
                lines = z.read(i.filename).decode("utf-8", "replace").splitlines()
            except Exception as e:
                rep.unknown("%s: %s is present but could not be extracted (%s)"
                            % (path, i.filename, e))
                return None
        hits.append(Hit(i.filename, kind, i.file_size, lines))
    rep.note("%s: %d entries examined" % (os.path.basename(path), len(members)))
    return hits


def hits_from_tree(root, rep):
    try:
        entries = os.listdir(root)
    except Exception as e:
        rep.unknown("%s could not be listed (%s). Nothing was examined." % (root, e))
        return None
    low = [e.lower() for e in entries]
    if not (any(d in low for d in TREE_DIRS) or any(e.upper().rstrip("/") + "/" in TF_ROOTS
                                                    for e in entries)):
        rep.unknown("%s has no partition directory in it (looked for %s). This is not a build "
                    "tree or an extracted target_files." % (root, ", ".join(TREE_DIRS[:5])))
        return None

    hits = []
    n_files = 0
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        for fn in filenames:
            n_files += 1
            if fn != "adb_keys":
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, root)
            if os.path.islink(full):
                hits.append(Hit(rel, "symlink", 0, None))
                continue
            try:
                with open(full, "rb") as f:
                    lines = f.read().decode("utf-8", "replace").splitlines()
                size = os.path.getsize(full)
            except Exception as e:
                rep.unknown("%s is present but could not be read (%s)" % (rel, e))
                return None
            hits.append(Hit(rel, "file", size, lines))
    if n_files == 0:
        rep.unknown("%s contains no files at all. An empty tree is a broken read." % root)
        return None
    rep.note("%s: %d files examined" % (root, n_files))
    return hits


def key_fields(hit):
    out = []
    for line in (hit.lines or []):
        line = line.strip()
        if line:
            out.append(line.split()[0])
    return out


def check_public(hits, rep):
    files = [h for h in hits if h.kind == "file"]
    links = [h for h in hits if h.kind == "symlink"]
    for h in links:
        rep.note("%s is a symlink (create_root_structure.mk makes it in every build, public "
                 "included). Not a key; not counted." % h.path)
    if not files:
        rep.ok("no adb_keys file anywhere in this artefact (%d symlink(s) ignored)" % len(links))
        return
    rep.fail("this PUBLIC artefact carries %d pre-authorised adb key file(s):" % len(files))
    for h in files:
        n = len(key_fields(h))
        rep.note("  %s  (%d bytes, %d key line(s))" % (h.path, h.size, n))
        for k in key_fields(h)[:4]:
            rep.note("      %.40s..." % k)
    rep.note("Every handset flashed with this image would grant an adb shell, with no")
    rep.note("on-device authorisation dialog, to whoever holds the matching private key --")
    rep.note("and that key's owner is whoever downloaded the image. This is not a leak of")
    rep.note("private data, it is an authentication backdoor on every handset, and nothing short of")
    rep.note("a new image on every device removes it.")
    rep.note("Rebuild with RIST_PUBLIC_BUILD=true (image/scripts/sign_public.sh sets it) so")
    rep.note("aosp/rist.mk's `ifneq ($(RIST_PUBLIC_BUILD),true)` skips the copy. DO NOT just")
    rep.note("delete the file from the artefact: the same build produced the images, and a")
    rep.note("target_files edited after signing is not what was signed.")


def check_private(hits, want, rep):
    files = [h for h in hits if h.kind == "file"]
    links = [h for h in hits if h.kind == "symlink"]
    if not files:
        rep.fail("this PRIVATE artefact carries NO adb_keys file (%d symlink(s) found)."
                 % len(links))
        rep.note("The private/debuggable variant is DEFINED by baking that key in; without it")
        rep.note("you have a public image built by the private path, and adb will prompt on a")
        rep.note("device that has no way to answer the prompt while it is in kiosk lock task.")
        rep.note("Check that RIST_PUBLIC_BUILD is unset and that aosp/rist.mk still copies")
        rep.note("aosp/adb_keys to product/etc/security/adb_keys.")
        return

    real = []
    for h in files:
        ks = [k for k in key_fields(h) if KEYISH.match(k)]
        rep.note("%s  (%d bytes, %d line(s), %d of them a plausible key)"
                 % (h.path, h.size, len(h.lines or []), len(ks)))
        if ks:
            real.append((h, ks))
    if not real:
        rep.fail("an adb_keys file is present but not one line in it is a key.")
        rep.note("A file that exists is not a key. Nothing in this image would authorise adb.")
        return

    if want is None:
        rep.ok("adb_keys present with %d key line(s). WHICH key was not checked -- pass "
               "--expect-key aosp/adb_keys to assert identity." % sum(len(k) for _, k in real))
        return

    for h, ks in real:
        if want in ks:
            rep.ok("%s carries exactly the key from --expect-key (%.24s...)" % (h.path, want))
            return
    rep.fail("adb_keys is present, but NONE of its key lines is the expected key.")
    rep.note("expected (first 24 chars): %.24s..." % want)
    for h, ks in real:
        for k in ks[:4]:
            rep.note("found in %s:            %.24s..." % (h.path, k))
    rep.note("This is a DIFFERENT failure from the key being absent: somebody else's key is")
    rep.note("staged into a build you were about to flash and trust.")


def run(target, variant, expect_key, quiet=False):
    rep = Report(quiet)
    if not os.path.exists(target):
        rep.unknown("no such path: %s" % target)
        return rep.verdict()

    want = None
    if expect_key is not None:
        if variant != "private":
            rep.unknown("--expect-key only means something for --private: a public image is "
                        "supposed to carry no key at all.")
            return rep.verdict()
        want = read_expect_key(expect_key, rep)
        if want is None:
            return rep.verdict()

    if os.path.isdir(target):
        hits = hits_from_tree(target, rep)
    else:
        hits = hits_from_zip(target, rep)
    if hits is None:
        return rep.verdict()

    if variant == "public":
        check_public(hits, rep)
    else:
        check_private(hits, want, rep)
    return rep.verdict()


KEY_A = ("QAAAAI" + "Bx" * 180 + "== builder@workstation")
KEY_B = ("QAAAAI" + "Cy" * 180 + "== stranger@laptop")


def _zip(path, members):
    with zipfile.ZipFile(path, "w") as z:
        for name, data in members:
            if isinstance(data, tuple):        # (content, unix_mode)
                content, mode = data
                zi = zipfile.ZipInfo(name)
                zi.external_attr = mode << 16
                z.writestr(zi, content)
            else:
                z.writestr(name, data)
    return path


def selftest():
    tmp = tempfile.mkdtemp(prefix="adbkeys-selftest-")
    cases = []
    try:
        base = [("META/misc_info.txt", "recovery_api_version=3\n"),
                ("SYSTEM/build.prop", "ro.build.id=RIST\n"),
                ("PRODUCT/etc/permissions/x.xml", "<permissions></permissions>\n")]
        link = ("ROOT/adb_keys", ("/product/etc/security/adb_keys", stat.S_IFLNK | 0o777))

        clean = _zip(os.path.join(tmp, "clean-target_files.zip"), base + [link])
        keyed = _zip(os.path.join(tmp, "keyed-target_files.zip"),
                     base + [link, ("PRODUCT/etc/security/adb_keys", KEY_A + "\n")])
        keyed_sys = _zip(os.path.join(tmp, "keyed-system-target_files.zip"),
                         base + [("SYSTEM/etc/security/adb_keys", KEY_A + "\n")])
        keyed_root = _zip(os.path.join(tmp, "keyed-root-target_files.zip"),
                          base + [("ROOT/adb_keys", KEY_A + "\n")])
        empty_key = _zip(os.path.join(tmp, "emptykey-target_files.zip"),
                         base + [("PRODUCT/etc/security/adb_keys", "")])
        junk_key = _zip(os.path.join(tmp, "junkkey-target_files.zip"),
                        base + [("PRODUCT/etc/security/adb_keys", "not a key\n")])
        wrong_key = _zip(os.path.join(tmp, "wrongkey-target_files.zip"),
                         base + [("PRODUCT/etc/security/adb_keys", KEY_B + "\n")])
        empty_zip = _zip(os.path.join(tmp, "empty.zip"), [])
        img_zip = _zip(os.path.join(tmp, "stallion-install.zip"),
                       [("stallion/super_1.img", "x"), ("stallion/vbmeta.img", "y"),
                        ("stallion/flash-all.sh", "#!/bin/sh\n")])
        notzip = os.path.join(tmp, "notazip.zip")
        with open(notzip, "w") as f:
            f.write("this is not a zip")

        keyfile = os.path.join(tmp, "adb_keys.src")
        with open(keyfile, "w") as f:
            f.write(KEY_A + "\n")
        emptykeyfile = os.path.join(tmp, "adb_keys.empty")
        open(emptykeyfile, "w").close()

        tree = os.path.join(tmp, "product-out")
        os.makedirs(os.path.join(tree, "product", "etc", "security"))
        os.makedirs(os.path.join(tree, "system", "priv-app"))
        with open(os.path.join(tree, "product", "etc", "security", "adb_keys"), "w") as f:
            f.write(KEY_A + "\n")
        os.symlink("product/etc/security/adb_keys", os.path.join(tree, "adb_keys"))
        clean_tree = os.path.join(tmp, "product-out-public")
        os.makedirs(os.path.join(clean_tree, "product", "etc"))
        os.makedirs(os.path.join(clean_tree, "system"))
        with open(os.path.join(clean_tree, "system", "build.prop"), "w") as f:
            f.write("ro.build.id=RIST\n")
        os.symlink("product/etc/security/adb_keys", os.path.join(clean_tree, "adb_keys"))
        notatree = os.path.join(tmp, "just-a-dir")
        os.makedirs(notatree)
        with open(os.path.join(notatree, "readme.txt"), "w") as f:
            f.write("hello\n")

        # (name, target, variant, expect_key, expected exit code)
        cases = [
            ("public + key at PRODUCT/etc/security", keyed, "public", None, 1),
            ("public + key at SYSTEM/etc/security", keyed_sys, "public", None, 1),
            ("public + key loose at ROOT/adb_keys", keyed_root, "public", None, 1),
            ("public + zero-byte adb_keys file", empty_key, "public", None, 1),
            ("public + adb_keys of junk", junk_key, "public", None, 1),
            ("private + no key at all", clean, "private", None, 1),
            ("private + key file with no key in it", junk_key, "private", keyfile, 1),
            ("private + SOMEBODY ELSE'S key", wrong_key, "private", keyfile, 1),
            ("private tree + no key", clean_tree, "private", keyfile, 1),
            ("factory/install zip (key is in super.img)", img_zip, "public", None, 2),
            ("not a zip at all", notzip, "public", None, 2),
            ("zip with zero entries", empty_zip, "public", None, 2),
            ("path does not exist", os.path.join(tmp, "nope.zip"), "public", None, 2),
            ("directory that is not a build tree", notatree, "public", None, 2),
            ("private + --expect-key file missing", keyed, "private",
             os.path.join(tmp, "nokey"), 2),
            ("private + --expect-key file empty", keyed, "private", emptykeyfile, 2),
            ("--expect-key given with --public", clean, "public", keyfile, 2),
            ("public + clean target_files", clean, "public", None, 0),
            ("private + the right key", keyed, "private", keyfile, 0),
            ("private + right key, identity unasserted", keyed, "private", None, 0),
            ("private tree + the right key", tree, "private", keyfile, 0),
        ]

        bad = 0
        for name, target, variant, ek, want_rc in cases:
            print("--- selftest: %s" % name)
            got = run(target, variant, ek, quiet=True)
            verdict = "ok" if got == want_rc else "SELFTEST FAIL"
            if got != want_rc:
                bad += 1
            print("    %-14s exit %d, expected %d\n" % (verdict, got, want_rc))
        print("SELFTEST %s (%d case(s))"
              % ("PASS" if bad == 0 else "FAIL -- %d case(s) wrong" % bad, len(cases)))
        return 0 if bad == 0 else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def usage(msg=None):
    if msg:
        sys.stderr.write("check_adb_keys.py: %s\n" % msg)
    sys.stderr.write(
        "usage: tools/check_adb_keys.py <target_files.zip|tree> --public|--private "
        "[--expect-key FILE]\n"
        "       tools/check_adb_keys.py --selftest\n"
        "\n"
        "The variant is REQUIRED and has no default. A public image and a private image differ\n"
        "by exactly the presence of adb_keys, so inferring the variant from the artefact would\n"
        "make this check a tautology that passes on any input.\n")
    return 2


def main(argv):
    if "--selftest" in argv:
        return selftest()
    target = None
    variant = None
    expect = None
    quiet = False
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--public" or a == "--private":
            if variant is not None and variant != a[2:]:
                return usage("--public and --private are mutually exclusive")
            variant = a[2:]
        elif a == "--expect-key":
            i += 1
            if i >= len(argv):
                return usage("--expect-key needs a file")
            expect = argv[i]
        elif a == "--quiet":
            quiet = True
        elif a.startswith("-"):
            return usage("unknown option %s" % a)
        elif target is None:
            target = a
        else:
            return usage("more than one artefact given (%s and %s)" % (target, a))
        i += 1
    if target is None:
        return usage("no artefact given")
    if variant is None:
        return usage("no variant declared -- pass --public or --private")
    return run(target, variant, expect, quiet)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
