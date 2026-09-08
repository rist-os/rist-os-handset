#!/usr/bin/env python3
"""Decide whether aosp/patches/ actually reached a BUILT RistOS artefact."""

import argparse
import io
import os
import re
import sys
import zipfile


FRAMEWORK_RES = "SYSTEM/framework/framework-res.apk"
FRAMEWORK_JAR = "SYSTEM/framework/framework.jar"

PATCHES = [
    {
        "id": "0001",
        "title": "VOICE_ASSIST hold-to-talk key handler (frameworks/base)",
        "expect": "skipped",
        "scope": "framework-res",
        "anchor": [b"android.intent.action.DYNAMIC_SENSOR_CHANGED"],
        "applied_if_present": [b"watch.rist.assistant.action.RECORD_DOWN"],
        "applied_if_absent": [],
        "raw_mode": "impossible",
        "raw_reason": (
            "watch.rist.assistant.action.RECORD_DOWN is also in the Rist app's own manifest -- "
            "it is present in builds where 0001 was NOT applied -- "
            "so a whole-image scan cannot attribute it to framework-res."
        ),
        "fix": (
            "aosp/rist.mk defers this one. If it is now wanted:\n"
            "  image/scripts/apply_patches.sh --with-0001 \"$ANDROID_BUILD_TOP\""
        ),
    },
    {
        "id": "0002",
        "title": "network location endpoint -> loc.ristos.org (packages/apps/NetworkLocation)",
        "expect": "applied",
        "scope": "networklocation",
        "anchor": [b"/clls/wloc"],
        "applied_if_present": [b"loc.ristos.org"],
        "applied_if_absent": [b"gs-loc.apple.grapheneos.org"],
        "raw_mode": "ok",
        "raw_reason": "",
        "fix": (
            "image/scripts/apply_patches.sh --only 0002 \"$ANDROID_BUILD_TOP\", then REBUILD.\n"
            "aosp/rist.mk:44-60 warns that patch 0002, the RistSettingsStrings RRO and the\n"
            "NetworkLocationPromptActivity wording must ship in the SAME image: RistSettingsStrings\n"
            "is in PRODUCT_PACKAGES unconditionally, so without 0002 the Settings label and the\n"
            "consent screen name a Rist relay the endpoint does not use."
        ),
    },
    {
        "id": "0003",
        "title": "recovery + fastbootd title lines (bootable/recovery)",
        "expect": "applied",
        "scope": "recovery",
        "anchor": [],
        "applied_if_present": [b"RistOS Recovery", b"RistOS Fastboot"],
        "applied_if_absent": [b"GrapheneOS Recovery", b"GrapheneOS Fastboot"],
        "raw_mode": "ok",
        "raw_reason": "",
        "fix": (
            "image/scripts/apply_patches.sh --only 0003 \"$ANDROID_BUILD_TOP\", then REBUILD the\n"
            "recovery ramdisk. Both literals live in the one /system/bin/recovery; a source grep of\n"
            "recovery.cpp alone reports the job done while fastbootd stays branded."
        ),
    },
    {
        "id": "0004",
        "title": "framework-res 32-bit roadmap sentence (frameworks/base)",
        "expect": "applied",
        "scope": "framework-res",
        "anchor": [b"deprecated_abi_message_grapheneos"],
        "applied_if_present": [b"switch to a build for modern devices."],
        "applied_if_absent": [b"GrapheneOS plans to phase out 32-bit app support"],
        "raw_mode": "ok",
        "raw_reason": "",
        "fix": (
            "image/scripts/apply_patches.sh --only 0004 \"$ANDROID_BUILD_TOP\", then REBUILD.\n"
            "NOTE: `git am` CANNOT apply 0004 -- its hunk has zero context lines and git apply\n"
            "refuses those without --unidiff-zero, which git am has no way to forward. Every\n"
            "patch header says `git am`; apply_patches.sh handles it."
        ),
    },
    {
        "id": "0005",
        "title": "second HTTPS time source, fallback only (frameworks/base)",
        "expect": "applied",
        "scope": "framework-res",
        "anchor": [b"https://time.grapheneos.org/generate_204"],
        "applied_if_present": [b"https://cp.cloudflare.com/generate_204"],
        "applied_if_absent": [],
        "raw_mode": "ok",
        "raw_reason": "",
        "fix": (
            "image/scripts/apply_patches.sh --only 0005 \"$ANDROID_BUILD_TOP\", then REBUILD.\n"
            "Adds a fallback HTTPS time source after time.grapheneos.org."
        ),
    },
    {
        "id": "0006",
        "title": "KnownSystemPackage RIST -> watch.rist.assistant (frameworks/base)",
        "expect": "applied",
        "scope": "framework-jar",
        "anchor": [b"app.grapheneos.setupwizard", b"com.android.settings"],
        "applied_if_present": [b"watch.rist.assistant"],
        "applied_if_absent": [],
        "raw_mode": "impossible",
        "raw_reason": (
            "watch.rist.assistant is the Rist app's own package name. In the 2026083002 "
            "target_files -- a build where 0006 is NOT applied -- it occurs in six members "
            "(RistAssistant.apk, rist-privapp-permissions-*.xml, rist-provision-do.sh and .rc, "
            "system_ext_seapp_contexts, META/filesystem_config.txt). A byte scan of super_*.img "
            "hits every one of them and cannot tell them from the framework, so raw mode would "
            "report a PASS on an image that does not carry the patch."
        ),
        "fix": (
            "image/scripts/apply_patches.sh --only 0006 \"$ANDROID_BUILD_TOP\", then REBUILD.\n"
            "Without this row, SettingsProvider.checkProtectedSettingAccess compares the caller's\n"
            "package name for STRING EQUALITY against the KnownSystemPackage ids in each\n"
            "protected setting's @Protected(readWrite = ...) list, and watch.rist.assistant is in\n"
            "neither network_location's nor geocoder's. There is no permission to hold and no flag\n"
            "to set: WRITE_SECURE_SETTINGS is granted and is not sufficient on a GrapheneOS base.\n"
            "The app writes both keys, reads them back, disagrees, and reports\n"
            "NetworkLocationConsent.Outcome.REFUSED -- a correctly-reported dead feature."
        ),
    },
]

SKIP_EXT = (".png", ".webp", ".jpg", ".jpeg", ".gif", ".ttf", ".otf", ".mp3", ".ogg", ".wav")


def encodings_of(needle):
    return [needle, needle.decode("utf-8").encode("utf-16-le")]


class Result:
    def __init__(self, name, state, lines=(), detail=""):
        self.name = name
        self.state = state          # 'pass' | 'fail' | 'unchecked'
        self.lines = list(lines)
        self.detail = detail


class Artefact:

    TF_MARKERS = ("SYSTEM/", "META/", "IMAGES/", "PRODUCT/", "RECOVERY/", "BOOT/",
                  "SYSTEM_EXT/", "VENDOR/")

    def __init__(self, path):
        self.path = path
        self.zf = None
        self.root = None
        if os.path.isdir(path):
            self.root = path
        elif zipfile.is_zipfile(path):
            self.zf = zipfile.ZipFile(path)
        else:
            raise ValueError(
                "%s is neither a directory nor a readable zip. A truncated package looks "
                "exactly like this." % path)

        names = self.names()
        looks_tf = any(n.replace("\\", "/").startswith(self.TF_MARKERS) for n in names)
        has_imgs = any(n.lower().endswith(".img") for n in names)
        if looks_tf:
            self.kind = "target_files"
        elif has_imgs:
            self.kind = "images"
        else:
            raise ValueError(
                "%s holds neither target_files partition directories (SYSTEM/, IMAGES/, ...) "
                "nor any *.img. Nothing here can be checked." % path)

    def names(self):
        if self._cached_names is not None:
            return self._cached_names
        if self.zf is not None:
            out = self.zf.namelist()
        else:
            out = []
            for dirpath, _dirs, files in os.walk(self.root):
                for f in files:
                    out.append(os.path.relpath(os.path.join(dirpath, f), self.root))
        self._cached_names = out
        return out

    _cached_names = None

    def read(self, name):
        if self.zf is not None:
            try:
                return self.zf.read(name)
            except KeyError:
                raise KeyError(name)
        full = os.path.join(self.root, name)
        if not os.path.isfile(full):
            raise KeyError(name)
        with open(full, "rb") as fh:
            return fh.read()

    def open(self, name):
        if self.zf is not None:
            return self.zf.open(name)
        return open(os.path.join(self.root, name), "rb")

    def size(self, name):
        if self.zf is not None:
            return self.zf.getinfo(name).file_size
        return os.path.getsize(os.path.join(self.root, name))

    def close(self):
        if self.zf is not None:
            self.zf.close()


def search_apk(blob, needles):
    found = set()
    todo = {}
    for n in needles:
        for enc in encodings_of(n):
            todo[enc] = n

    def sweep(data):
        for enc, orig in list(todo.items()):
            if enc in data:
                found.add(orig)

    sweep(blob)
    if found == set(needles):
        return found

    try:
        zf = zipfile.ZipFile(io.BytesIO(blob))
    except zipfile.BadZipFile:
        return found
    try:
        for info in zf.infolist():
            if info.is_dir():
                continue
            if info.filename.lower().endswith(SKIP_EXT):
                continue
            try:
                sweep(zf.read(info))
            except (zipfile.BadZipFile, RuntimeError, EOFError, OSError):
                continue
            if found == set(needles):
                break
    finally:
        zf.close()
    return found


def search_stream(fh, needles, chunk=16 << 20):
    pats = {}
    for n in needles:
        for enc in encodings_of(n):
            pats[enc] = n
    if not pats:
        return set()
    rx = re.compile(b"|".join(re.escape(p) for p in sorted(pats, key=len, reverse=True)))
    overlap = max(len(p) for p in pats) - 1

    found = set()
    tail = b""
    while True:
        buf = fh.read(chunk)
        if not buf:
            break
        data = tail + buf
        for m in rx.finditer(data):
            found.add(pats[m.group(0)])
        if found == set(needles):
            return found
        tail = data[-overlap:] if overlap > 0 else b""
    return found


def find_framework_res(art):
    if FRAMEWORK_RES in art.names():
        return FRAMEWORK_RES
    for n in art.names():
        if n.replace("\\", "/").endswith("/framework/framework-res.apk"):
            return n
    return None


def find_framework_jar(art):
    if FRAMEWORK_JAR in art.names():
        return FRAMEWORK_JAR
    for n in art.names():
        if n.replace("\\", "/").endswith("/framework/framework.jar"):
            return n
    return None


def find_networklocation_apk(art):
    hits = []
    for n in art.names():
        norm = n.replace("\\", "/").lower()
        if norm.endswith(".apk") and "networklocation" in norm:
            hits.append(n)
    return sorted(hits)


def find_recovery_binaries(art):
    hits = []
    for n in art.names():
        norm = n.replace("\\", "/")
        if norm.endswith("/bin/recovery") or norm.endswith("/sbin/recovery"):
            hits.append(n)
    return sorted(hits)


def decide(spec, anchor_ok, present, where, extra=()):
    lines = list(extra)
    pid = spec["id"]

    if spec["anchor"] and not anchor_ok:
        lines.append("UNCHECKED %s: none of the anchor strings %s were found."
                     % (where, ", ".join(repr(a.decode()) for a in spec["anchor"])))
        lines.append("          That means this is not the file we think it is, or it is packed")
        lines.append("          in a form this tool cannot read. Nothing has been established")
        lines.append("          about patch %s. This is NOT a pass." % pid)
        return Result(pid, "unchecked", lines)

    bad = [s for s in spec["applied_if_absent"] if s in present]
    good_missing = [s for s in spec["applied_if_present"] if s not in present]

    if bad:
        applied = False
        why = "%s still contains %s" % (where, ", ".join(repr(s.decode()) for s in bad))
    elif not good_missing:
        applied = True
        why = "%s carries %s and none of the pre-patch strings" % (
            where, ", ".join(repr(s.decode()) for s in spec["applied_if_present"]))
    elif not spec["applied_if_absent"]:
        applied = False
        why = "%s does not carry %s (the anchor confirms this is the right file)" % (
            where, ", ".join(repr(s.decode()) for s in good_missing))
    else:
        lines.append("UNCHECKED %s: neither the pre-patch strings nor %s were found."
                     % (where, ", ".join(repr(s.decode()) for s in good_missing)))
        lines.append("          The absence of the old string has not been shown to mean the")
        lines.append("          patch applied. Confirm by hand before shipping.")
        return Result(pid, "unchecked", lines)

    want_applied = spec["expect"] == "applied"
    if applied == want_applied:
        if applied:
            lines.append("ok        applied: %s" % why)
        else:
            lines.append("ok        NOT applied, as intended: %s" % why)
            lines.append("          aosp/rist.mk defers this one. Skipping it is a decision, and")
            lines.append("          this line is printed every run so it never passes for silence.")
        return Result(pid, "pass", lines)

    detail = "missing" if want_applied else "present, but deferred"
    if want_applied:
        lines.append("FAIL      NOT in this artefact: %s" % why)
        lines.append("          The patch may well be applied in the source tree. That is not the")
        lines.append("          question -- an incremental build that did not touch the project")
        lines.append("          reuses its old output, so the tree can be right and the image wrong.")
    else:
        lines.append("FAIL      PRESENT, but aosp/rist.mk records this patch as deferred: %s" % why)
        lines.append("          Either the deferral ended and this table is stale, or something")
        lines.append("          applied a patch nobody meant to ship. Resolve it before signing.")
        lines.append("          If the decision changed, re-run with --expect %s=applied." % pid)
    for ln in spec["fix"].splitlines():
        lines.append("          %s" % ln)
    return Result(pid, "fail", lines, detail=detail)


def check_target_files(art, spec):
    pid = spec["id"]
    scope = spec["scope"]
    needles = spec["anchor"] + spec["applied_if_present"] + spec["applied_if_absent"]

    if scope == "framework-res":
        member = find_framework_res(art)
        if member is None:
            return Result(pid, "unchecked", [
                "UNCHECKED no framework-res.apk in %s (looked for %s)." % (art.path, FRAMEWORK_RES),
                "          Without it nothing can be said about patch %s." % pid,
            ])
        blob = art.read(member)
        present = search_apk(blob, needles)
        anchor_ok = all(a in present for a in spec["anchor"])
        return decide(spec, anchor_ok, present, member,
                      ["          (%s, %d bytes)" % (member, len(blob))])

    if scope == "framework-jar":
        member = find_framework_jar(art)
        if member is None:
            return Result(pid, "unchecked", [
                "UNCHECKED no framework.jar in %s (looked for %s)." % (art.path, FRAMEWORK_JAR),
                "          Nothing can be said about patch %s. Do NOT fall back to grepping the" % pid,
                "          whole image for 'watch.rist.assistant': that is the Rist app's own",
                "          package name and is in a RistOS image either way.",
            ])
        blob = art.read(member)
        present = search_apk(blob, needles)
        anchor_ok = all(a in present for a in spec["anchor"])
        r = decide(spec, anchor_ok, present, member,
                   ["          (%s, %d bytes)" % (member, len(blob))])
        if r.state == "unchecked":
            r.lines.append("          Most likely cause: this framework.jar carries no classes*.dex")
            r.lines.append("          (a dexpreopt setup that strips it), so the literals are in the")
            r.lines.append("          boot image instead. By hand:")
            r.lines.append("            strings SYSTEM/framework/*/boot-framework.oat \\")
            r.lines.append("              | grep -e watch.rist.assistant -e app.grapheneos.setupwizard")
        return r

    if scope == "networklocation":
        members = find_networklocation_apk(art)
        if not members:
            return Result(pid, "unchecked", [
                "UNCHECKED no *NetworkLocation*.apk anywhere in %s." % art.path,
                "          app.grapheneos.networklocation is a KEPT package (see",
                "          check_debrand.py's keeplist), so its absence is",
                "          itself surprising -- but it means patch %s cannot be judged." % pid,
            ])
        results = []
        for m in members:
            blob = art.read(m)
            present = search_apk(blob, needles)
            anchor_ok = all(a in present for a in spec["anchor"])
            results.append(decide(spec, anchor_ok, present, m,
                                  ["          (%s, %d bytes)" % (m, len(blob))]))
        return fold(pid, results)

    if scope == "recovery":
        bins = find_recovery_binaries(art)
        if not bins:
            imgs = sorted(n for n in art.names()
                          if n.startswith("IMAGES/")
                          and ("recovery" in n or "vendor_boot" in n or n.endswith("boot.img")))
            return Result(pid, "unchecked", [
                "UNCHECKED no plain */bin/recovery member in %s." % art.path,
                "          The binary is inside a compressed ramdisk this tool does not unpack,",
                "          so whether the recovery and fastbootd titles were debranded is UNKNOWN.",
                "          Boot images present: %s" % (imgs or "none"),
                "          By hand:  unpack_bootimg / lz4 -d the ramdisk, then",
                "            strings system/bin/recovery | grep -i 'graphene\\|RistOS'",
            ])
        results = []
        for b in bins:
            blob = art.read(b)
            present = set(s for s in needles if any(e in blob for e in encodings_of(s)))
            results.append(decide(spec, True, present, b))
        return fold(pid, results)

    return Result(pid, "unchecked", ["UNCHECKED unknown scope %r -- this is a bug in the table." % scope])


def fold(pid, results):
    lines = []
    for r in results:
        lines.extend(r.lines)
    for want in ("fail", "unchecked"):
        hit = [r for r in results if r.state == want]
        if hit:
            return Result(pid, want, lines, detail=hit[0].detail)
    return Result(pid, "pass", lines)


def check_images(art, spec):
    pid = spec["id"]
    if spec["raw_mode"] != "ok":
        return Result(pid, "unchecked", [
            "UNCHECKED not answerable from raw images.",
            "          %s" % spec["raw_reason"],
            "          Run this against a target_files package to get a verdict on %s." % pid,
        ])

    needles = spec["anchor"] + spec["applied_if_present"] + spec["applied_if_absent"]
    imgs = sorted(n for n in art.names() if n.lower().endswith(".img"))
    present = set()
    scanned = []
    for n in imgs:
        with art.open(n) as fh:
            hit = search_stream(fh, needles)
        if hit:
            scanned.append("%s: %s" % (n, ", ".join(sorted(repr(s.decode()) for s in hit))))
        present |= hit
    anchor_ok = all(a in present for a in spec["anchor"])

    extra = ["          raw scan of %d image file(s)" % len(imgs)]
    for s in scanned:
        extra.append("          %s" % s)
    if not spec["anchor"] and not present:
        return Result(pid, "unchecked", extra + [
            "UNCHECKED none of %s appear in the raw images."
            % ", ".join(repr(s.decode()) for s in needles),
            "          The recovery binary lives in a compressed ramdisk inside boot/vendor_boot,",
            "          which a byte scan cannot see into. Nothing has been established.",
            "          Use a target_files package, which keeps the ramdisk as plain files.",
        ])
    return decide(spec, anchor_ok, present, "the raw images", extra)


def run(path, only=None, expect_override=None):
    try:
        art = Artefact(path)
    except (ValueError, OSError) as e:
        print("FAIL  %s" % e, file=sys.stderr)
        return 2

    specs = []
    for s in PATCHES:
        s = dict(s)
        if expect_override and s["id"] in expect_override:
            s["expect"] = expect_override[s["id"]]
        if only and s["id"] not in only:
            continue
        specs.append(s)

    print("=== RistOS patch-presence check: %s ===" % path)
    print("    artefact kind : %s" % art.kind)
    if art.kind == "images":
        print("    NOTE: raw-image mode establishes strictly LESS than a target_files package.")
        print("          It cannot attribute a string to a particular APK and cannot see inside")
        print("          a compressed ramdisk. Prefer releases/$BN/$DEVICE-target_files.zip.")
    print("")

    results = []
    try:
        for s in specs:
            print("--- %s  %s   [expect: %s]" % (s["id"], s["title"], s["expect"]))
            r = check_target_files(art, s) if art.kind == "target_files" else check_images(art, s)
            for line in r.lines:
                print("    %s" % line)
            print("")
            results.append(r)
    finally:
        art.close()

    if not results:
        print("nothing was checked. That is not a pass.")
        return 2

    failed = [(r.name, r.detail) for r in results if r.state == "fail"]
    unchecked = [r.name for r in results if r.state == "unchecked"]

    print("=== verdict ===")
    if failed:
        print("PATCHES NOT IN THEIR EXPECTED STATE -- do not ship this artefact.")
        print("Not as expected: %s"
              % ", ".join("%s (%s)" % (n, d or "wrong state") for n, d in failed))
        if unchecked:
            print("Additionally could not be checked at all: %s" % ", ".join(unchecked))
        return 1
    if unchecked:
        print("INCONCLUSIVE -- this is NOT a pass.")
        print("Could not check: %s" % ", ".join(unchecked))
        print("Nothing has been established about those patches. The image is not cleared.")
        return 2
    print("PASS: every patch is in the state it is meant to be in for %s" % path)
    return 0


def _apk(entries):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries.items():
            z.writestr(name, data)
    return buf.getvalue()


CLEAN_ABI = (b'<string name="deprecated_abi_message_grapheneos">This app only supports 32-bit '
             b'and therefore isn\'t compatible with more recent devices. If the app is still '
             b'developed, switch to a build for modern devices.</string>')
DIRTY_ABI = (CLEAN_ABI[:-len(b"</string>")]
             + b' GrapheneOS plans to phase out 32-bit app support on older devices for '
               b'improved security.</string>')

CLEAN_TIME = (b"config_httpsTimeUrls\x00https://time.grapheneos.org/generate_204\x00"
              b"https://cp.cloudflare.com/generate_204\x00")
DIRTY_TIME = CLEAN_TIME.replace(b"https://cp.cloudflare.com/generate_204\x00", b"")

CLEAN_KSP = (b"com.android.providers.contacts\x00com.android.launcher3\x00"
             b"com.android.providers.media.module\x00com.android.permissioncontroller\x00"
             b"watch.rist.assistant\x00com.android.settings\x00app.grapheneos.setupwizard\x00"
             b"com.android.shell\x00")
DIRTY_KSP = CLEAN_KSP.replace(b"watch.rist.assistant\x00", b"")

RIST_APK = _apk({
    "AndroidManifest.xml": "watch.rist.assistant".encode("utf-16-le"),
    "classes.dex": b"watch.rist.assistant.action.RECORD_DOWN",
})


def _fw_res(abi=None, time_urls=None, manifest=None):
    return _apk({
        "resources.arsc": (CLEAN_ABI if abi is None else abi) + b"\n"
                          + (CLEAN_TIME if time_urls is None else time_urls),
        "AndroidManifest.xml": (b"...android.intent.action.DYNAMIC_SENSOR_CHANGED..."
                                if manifest is None else manifest),
    })


def _fw_jar(ksp=None):
    return _apk({"classes.dex": CLEAN_KSP if ksp is None else ksp})


def _tree(root, framework_res=None, netloc=None, recovery=None, framework_jar=None):
    def put(rel, data):
        full = os.path.join(root, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as fh:
            fh.write(data)

    put("META/misc_info.txt", b"recovery_api_version=3\n")
    put(FRAMEWORK_RES, framework_res if framework_res is not None else _fw_res())
    put(FRAMEWORK_JAR, framework_jar if framework_jar is not None else _fw_jar())
    put("SYSTEM/priv-app/RistAssistant/RistAssistant.apk", RIST_APK)
    put("PRODUCT/app/NetworkLocation/NetworkLocation.apk",
        netloc if netloc is not None else _apk({
            "classes.dex": b'https://loc.ristos.org/clls/wloc https://gs-loc.apple.com/clls/wloc',
            "res/xml/network_security_config.xml": b"<domain>loc.ristos.org</domain>",
        }))
    put("RECOVERY/RAMDISK/system/bin/recovery",
        recovery if recovery is not None else
        b"\x7fELF...RistOS Recovery...RistOS Fastboot...")


def selftest():
    import shutil
    import tempfile
    failures = []

    def case(name, want_rc, want_txt, **kw):
        root = tempfile.mkdtemp(prefix="cp-selftest-")
        try:
            _tree(root, **kw)
            buf = io.StringIO()
            old = sys.stdout
            sys.stdout = buf
            try:
                rc = run(root)
            finally:
                sys.stdout = old
            out = buf.getvalue()
            ok = (rc == want_rc) and (want_txt in out)
            print("%-46s rc=%d %s" % (name, rc, "ok" if ok else "MISMATCH"))
            if not ok:
                failures.append(name)
                print("    wanted rc=%d and %r" % (want_rc, want_txt))
                print("    ---- output ----")
                for ln in out.splitlines():
                    print("    " + ln)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def raw_case(name, want_rc, want_txt, blob):
        root = tempfile.mkdtemp(prefix="cp-selftest-raw-")
        try:
            with open(os.path.join(root, "super_1.img"), "wb") as fh:
                fh.write(blob)
            buf = io.StringIO()
            old = sys.stdout
            sys.stdout = buf
            try:
                rc = run(root)
            finally:
                sys.stdout = old
            out = buf.getvalue()
            ok = (rc == want_rc) and (want_txt in out)
            print("%-46s rc=%d %s" % (name, rc, "ok" if ok else "MISMATCH"))
            if not ok:
                failures.append(name)
                print("    wanted rc=%d and %r" % (want_rc, want_txt))
                print("    ---- output ----")
                for ln in out.splitlines():
                    print("    " + ln)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    print("=== check_patches.py selftest ===")

    case("clean tree passes", 0, "PASS: every patch")

    case("0004 unpatched is caught", 1, "0004 (missing)",
         framework_res=_fw_res(abi=DIRTY_ABI))

    case("0005 unpatched is caught", 1, "0005 (missing)",
         framework_res=_fw_res(time_urls=DIRTY_TIME))

    case("0006 unpatched, Rist apk present, still caught", 1, "0006 (missing)",
         framework_jar=_fw_jar(ksp=DIRTY_KSP))

    case("0006 with an unreadable framework.jar is UNCHECKED", 2, "INCONCLUSIVE",
         framework_jar=_apk({"classes.dex": b"no string pool this tool can recognise"}))

    case("0002 unpatched is caught", 1, "0002 (missing)",
         netloc=_apk({"classes.dex": b"https://gs-loc.apple.grapheneos.org/clls/wloc"}))

    case("0003 unpatched is caught", 1, "0003 (missing)",
         recovery=b"\x7fELF...GrapheneOS Recovery...GrapheneOS Fastboot...")

    case("0003 half-done (fastbootd left branded)", 1, "0003 (missing)",
         recovery=b"\x7fELF...RistOS Recovery...GrapheneOS Fastboot...")

    case("0001 present when it should be deferred", 1, "0001 (present, but deferred)",
         framework_res=_fw_res(manifest=(b"...android.intent.action.DYNAMIC_SENSOR_CHANGED..."
                                         b"watch.rist.assistant.action.RECORD_DOWN")))

    case("no anchor is UNCHECKED, not a pass", 2, "INCONCLUSIVE",
         framework_res=_apk({"resources.arsc": b"nothing recognisable here"}))

    case("missing recovery binary is UNCHECKED", 2, "INCONCLUSIVE",
         recovery=b"")

    case("no NetworkLocation apk is UNCHECKED", 2, "INCONCLUSIVE",
         netloc=_apk({"classes.dex": b"nothing here"}))

    raw_case("0006 is UNCHECKED in raw mode, never a pass", 2,
             "Could not check: 0001, 0003, 0006",
             b"...loc.ristos.org/clls/wloc..." + CLEAN_ABI + CLEAN_TIME
             + b"...watch.rist.assistant...watch.rist.assistant.action.RECORD_DOWN...")

    print("")
    if failures:
        print("SELFTEST FAILED: %s" % ", ".join(failures))
        return 1
    print("selftest ok -- every check can both pass and fail")
    return 0


def main():
    ap = argparse.ArgumentParser(
        description="Is each aosp/patches/ patch actually in this built artefact?")
    ap.add_argument("artefact", nargs="?",
                    help="target_files zip, extracted target_files dir, or an image directory")
    ap.add_argument("--only", action="append", default=None, metavar="ID",
                    help="check just this patch id (0001..0006); repeatable")
    ap.add_argument("--expect", action="append", default=None, metavar="ID=STATE",
                    help="override the expected state, e.g. --expect 0001=applied")
    ap.add_argument("--selftest", action="store_true",
                    help="run the built-in fixtures and exit")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.artefact:
        ap.error("give an artefact, or --selftest")

    override = {}
    for item in (args.expect or []):
        if "=" not in item:
            ap.error("--expect wants ID=STATE, got %r" % item)
        k, v = item.split("=", 1)
        if v not in ("applied", "skipped"):
            ap.error("--expect state must be 'applied' or 'skipped', got %r" % v)
        override[k] = v

    known = set(p["id"] for p in PATCHES)
    for i in (args.only or []):
        if i not in known:
            ap.error("unknown patch id %r; known: %s" % (i, ", ".join(sorted(known))))
    for k in override:
        if k not in known:
            ap.error("unknown patch id %r in --expect" % k)

    return run(args.artefact, only=set(args.only) if args.only else None,
               expect_override=override or None)


if __name__ == "__main__":
    sys.exit(main())
