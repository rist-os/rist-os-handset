#!/usr/bin/env python3
"""Decide whether a BUILT RistOS artefact still carries GrapheneOS branding."""

import argparse
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

BOOTLOGO_DIR = os.path.join(REPO, "aosp", "bootlogo")
BOOTANIM_ZIP = os.path.join(REPO, "aosp", "bootanimation", "bootanimation.zip")

FRAMEWORK_RES = "SYSTEM/framework/framework-res.apk"
LOGO_ENTRIES = ("assets/images/android-logo-mask.png",
                "assets/images/android-logo-shine.png")
BOOTANIM_NAMES = ("PRODUCT/media/bootanimation.zip",
                  "PRODUCT/media/bootanimation-dark.zip")

RECOVERY_MUST_NOT = (b"GrapheneOS Recovery", b"GrapheneOS Fastboot")
RECOVERY_MUST_HAVE = (b"RistOS Recovery", b"RistOS Fastboot")

GRAPHENEOS_PKG_KEEPLIST = (
    "app.grapheneos.camera",
    "app.grapheneos.networklocation",
    "app.grapheneos.carrierconfig2",
    "app.grapheneos.appcompatconfig",
    "app.grapheneos.speechservices",
    "app.grapheneos.gmscompat.lib",
    "overlay.grapheneos",
    "init.pixel.grapheneos.rc",
    "init.zumapro.grapheneos.rc",
    "default-permissions_app.grapheneos.backup.contacts.xml",
)


class Result:
    """One check's outcome. `state` is 'pass', 'fail' or 'unchecked'."""

    def __init__(self, name, state, lines=()):
        self.name = name
        self.state = state
        self.lines = list(lines)


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class Artefact:
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
                "%s is neither a directory nor a readable zip. A truncated "
                "target_files package looks exactly like this." % path)

    def names(self):
        if self.zf is not None:
            return self.zf.namelist()
        out = []
        for dirpath, _dirs, files in os.walk(self.root):
            for f in files:
                full = os.path.join(dirpath, f)
                out.append(os.path.relpath(full, self.root))
        return out

    def has(self, name):
        try:
            self.read(name)
            return True
        except KeyError:
            return False

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

    def close(self):
        if self.zf is not None:
            self.zf.close()


def check_boot_logo(art):
    lines = []
    for f in ("android-logo-mask.png", "android-logo-shine.png"):
        if not os.path.isfile(os.path.join(BOOTLOGO_DIR, f)):
            return Result("boot logo", "unchecked", [
                "aosp/bootlogo/%s is missing from this checkout, so there is" % f,
                "nothing to compare the artefact against. Run aosp/bootlogo/make_boot_logo.py.",
            ])

    if not art.has(FRAMEWORK_RES):
        return Result("boot logo", "unchecked", [
            "no %s in %s." % (FRAMEWORK_RES, art.path),
            "Whether the GrapheneOS logo is in the image is UNKNOWN. This is not a pass.",
        ])

    tmp = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
    try:
        tmp.write(art.read(FRAMEWORK_RES))
        tmp.close()
        try:
            fw = zipfile.ZipFile(tmp.name)
        except zipfile.BadZipFile as e:
            return Result("boot logo", "unchecked", [
                "%s could not be read as a zip (%s)." % (FRAMEWORK_RES, e),
                "This is not 'no logo found'. It is 'nobody looked'.",
            ])
        with fw:
            present = set(fw.namelist())
            state = "pass"
            for entry, srcname in zip(LOGO_ENTRIES,
                                      ("android-logo-mask.png", "android-logo-shine.png")):
                ours = sha256_file(os.path.join(BOOTLOGO_DIR, srcname))
                if entry not in present:
                    other = sorted(n for n in present if "android-logo" in n)
                    lines.append("UNCHECKED %s is not in framework-res.apk." % entry)
                    lines.append("          Nearby entries: %s" % (other or "none"))
                    lines.append("          The fallback artwork may have moved. Read")
                    lines.append("          BootAnimation.cpp before calling this clean.")
                    if state != "fail":
                        state = "unchecked"
                    continue
                built = sha256_bytes(fw.read(entry))
                if built == ours:
                    lines.append("ok        %s is ours" % entry)
                else:
                    lines.append("FAIL      %s is NOT ours -- GrapheneOS artwork is still in" % entry)
                    lines.append("          the image and bootanimation falls back to it.")
                    lines.append("          built = %s" % built)
                    lines.append("          ours  = %s" % ours)
                    lines.append("          Fix: image/scripts/debrand_boot_logo.sh install "
                                 "$ANDROID_BUILD_TOP, then rebuild.")
                    state = "fail"
            return Result("boot logo", state, lines)
    finally:
        os.unlink(tmp.name)


def check_bootanimation(art):
    lines = []
    if not os.path.isfile(BOOTANIM_ZIP):
        return Result("boot animation", "unchecked", [
            "aosp/bootanimation/bootanimation.zip is missing from this checkout.",
        ])
    ours = sha256_file(BOOTANIM_ZIP)
    state = "pass"

    for name in BOOTANIM_NAMES:
        if not art.has(name):
            lines.append("FAIL      %s is missing." % name)
            lines.append("          A dark-themed device misses every zip path and lands on the")
            lines.append("          framework-res fallback logo.")
            state = "fail"
            continue

        blob = art.read(name)
        clean = True

        got = sha256_bytes(blob)
        if got != ours:
            lines.append("FAIL      %s is not the animation in this repo." % name)
            lines.append("          built = %s" % got)
            lines.append("          ours  = %s" % ours)
            lines.append("          A duplicate PRODUCT_COPY_FILES destination is NOT a build")
            lines.append("          error: the FIRST destination wins and ours is only logged.")
            state = "fail"
            clean = False

        try:
            with zipfile.ZipFile(io.BytesIO(blob)) as bz:
                infos = bz.infolist()
                deflated = [i.filename for i in infos
                            if i.compress_type != zipfile.ZIP_STORED]
                if deflated:
                    lines.append("FAIL      %s has %d non-STORED entr%s (e.g. %s)."
                                 % (name, len(deflated),
                                    "y" if len(deflated) == 1 else "ies", deflated[0]))
                    lines.append("          preloadZip skips those silently; the animation would")
                    lines.append("          play zero frames and the fallback logo would show.")
                    state = "fail"
                    clean = False
                if not any(i.filename.endswith("desc.txt") for i in infos):
                    lines.append("FAIL      %s has no desc.txt -- bootanimation cannot play it."
                                 % name)
                    state = "fail"
                    clean = False
        except zipfile.BadZipFile as e:
            lines.append("FAIL      %s is not a readable zip (%s)." % (name, e))
            state = "fail"
            clean = False

        if clean:
            lines.append("ok        %s is ours, STORED, and has a desc.txt" % name)

    return Result("boot animation", state, lines)


def _find_recovery_binaries(art):
    hits = []
    for n in art.names():
        norm = n.replace("\\", "/")
        if norm.endswith("/bin/recovery") or norm.endswith("/sbin/recovery"):
            hits.append(n)
    return sorted(hits)


def check_recovery_text(art):
    bins = _find_recovery_binaries(art)
    if not bins:
        imgs = sorted(n for n in art.names()
                      if n.startswith("IMAGES/")
                      and ("recovery" in n or "vendor_boot" in n or n.endswith("boot.img")))
        return Result("recovery text", "unchecked", [
            "no plain */bin/recovery member in %s." % art.path,
            "The binary is inside a compressed ramdisk, which this tool does not unpack,",
            "so whether the recovery screen still says GrapheneOS is UNKNOWN.",
            "Boot images present: %s" % (imgs or "none"),
            "Check it by hand:  unpack_bootimg / lz4 -d the ramdisk, then",
            "  strings system/bin/recovery | grep -i 'graphene\\|RistOS'",
        ])

    lines = []
    state = "pass"
    for b in bins:
        blob = art.read(b)
        bad = [s for s in RECOVERY_MUST_NOT if s in blob]
        if bad:
            lines.append("FAIL      %s still contains %s"
                         % (b, ", ".join(repr(s.decode()) for s in bad)))
            lines.append("          Apply aosp/patches/0003-rist-recovery-debrand.patch to")
            lines.append("          bootable/recovery and REBUILD the recovery ramdisk. An")
            lines.append("          incremental build that did not touch that project reuses")
            lines.append("          the old image, so a source-tree grep proves nothing.")
            state = "fail"
            continue
        missing = [s for s in RECOVERY_MUST_HAVE if s not in blob]
        if missing:
            lines.append("UNCHECKED %s carries no GrapheneOS string, but also none of %s."
                         % (b, ", ".join(repr(s.decode()) for s in missing)))
            lines.append("          The absence of the branded string has not been shown to mean")
            lines.append("          the patch applied. Confirm by hand before shipping.")
            if state != "fail":
                state = "unchecked"
            continue
        lines.append("ok        %s says RistOS, not GrapheneOS" % b)
    return Result("recovery text", state, lines)


def check_bundled_apps(art):
    found = []
    for n in art.names():
        low = n.lower()
        if "app.grapheneos." not in low and "grapheneos" not in low:
            continue
        if any(k in low for k in GRAPHENEOS_PKG_KEEPLIST):
            continue
        found.append(n)
    if not found:
        return Result("bundled apps", "pass",
                      ["ok        no unexpected GrapheneOS package files in the image"])
    lines = ["FAIL      %d file(s) named for a GrapheneOS package that should be gone:"
             % len(found)]
    for n in found[:25]:
        lines.append("            %s" % n)
    if len(found) > 25:
        lines.append("            ... and %d more" % (len(found) - 25))
    lines.append("          The ETC.RistAssistant.OVERRIDES suppression in aosp/rist.mk did not")
    lines.append("          take. Module names, not package names, are what overrides match.")
    lines.append("          If one of these is a deliberate keep, add it to")
    lines.append("          GRAPHENEOS_PKG_KEEPLIST here WITH the reason -- do not delete the check.")
    return Result("bundled apps", "fail", lines)


PROP_FILES = ("SYSTEM/build.prop", "PRODUCT/etc/build.prop",
              "SYSTEM_EXT/etc/build.prop", "VENDOR/build.prop",
              "SYSTEM/system_ext/etc/build.prop")


def check_props(art):
    seen_any = False
    hits = []
    for pf in PROP_FILES:
        if not art.has(pf):
            continue
        seen_any = True
        try:
            text = art.read(pf).decode("utf-8", "replace")
        except Exception:
            continue
        for line in text.splitlines():
            s = line.strip()
            if not s or s.startswith("#") or "=" not in s:
                continue
            key, _, value = s.partition("=")
            if "grapheneos" in value.lower():
                hits.append("%s: %s" % (pf, s))
    if not seen_any:
        return Result("build props", "unchecked", [
            "no build.prop found in %s -- nothing was read." % art.path,
        ])
    if not hits:
        return Result("build props", "pass",
                      ["ok        no build property VALUE names grapheneos"])
    lines = ["FAIL      %d build propert%s point at GrapheneOS:"
             % (len(hits), "y" if len(hits) == 1 else "ies")]
    for h in hits[:20]:
        lines.append("            %s" % h)
    lines.append("          These are endpoints or branding baked into the image. Read")
    lines.append("          the list of hosts this handset still contacts")
    lines.append("          before deciding. If a value is a deliberate, documented keep, add it")
    lines.append("          to an allowlist here with the reason -- do not delete the check.")
    return Result("build props", "fail", lines)


CAMERA_EXT_FORBIDDEN = (
    "PixelCameraServices",
    "camerax.extensions",
    "androidx.camera.extensions.impl.xml",
    "preinstalled-packages-camera-services-base.xml",
)

CAMERA_KEEP = (
    ("PersistentBackgroundCameraServices (com.google.pixel.camera.services)",
     "SYSTEM_EXT", "app/PersistentBackgroundCameraServices/PersistentBackgroundCameraServices.apk"),
    ("the Lyric camera HAL apex",
     "VENDOR", "apex/com.google.pixel.camera.hal.apex"),
    ("ICameraIdRemapper",
     "SYSTEM_EXT", "framework/com.google.pixel.camera.services.cameraidremapper.jar"),
    ("ILyricConfigProvider",
     "SYSTEM_EXT", "framework/com.google.pixel.camera.services.lyricconfigprovider.jar"),
)

_PART_VARIANTS = {
    "SYSTEM_EXT": ("SYSTEM_EXT/", "SYSTEM/system_ext/"),
    "PRODUCT": ("PRODUCT/", "SYSTEM/product/"),
    "VENDOR": ("VENDOR/",),
}


def check_camera_extensions(art):
    names = [n.replace("\\", "/") for n in art.names()]
    if not names:
        return Result("camera extensions", "unchecked",
                      ["%s lists no members at all -- nothing was read." % art.path])

    found = sorted(n for n in names
                   if any(k in n for k in CAMERA_EXT_FORBIDDEN))

    missing = []
    for label, part, rel in CAMERA_KEEP:
        cands = [pre + rel for pre in _PART_VARIANTS[part]]
        if not any(n in cands for n in names):
            missing.append((label, cands[0]))

    lines = []
    state = "pass"

    if found:
        state = "fail"
        lines.append("FAIL      %d file(s) of the removed CameraX extensions chain are still here:"
                     % len(found))
        for n in found[:20]:
            lines.append("            %s" % n)
        if len(found) > 20:
            lines.append("            ... and %d more" % (len(found) - 20))
        lines.append("          If these are APK/jar paths, the ETC.RistAssistant.OVERRIDES entries")
        lines.append("          in aosp/rist.mk did not match -- overrides match MODULE names.")
        lines.append("          If they are .xml paths, image/scripts/prune_camera_extensions.sh was")
        lines.append("          not run after 'adevtool generate-all' and before 'm'. Leaving the XML")
        lines.append("          behind is NOT harmless: it registers a shared library pointing at a")
        lines.append("          jar that is not in the image. Re-run the prune and rebuild.")
    else:
        lines.append("ok        no PixelCameraServices / camerax.extensions file in the image")

    if missing:
        state = "fail"
        lines.append("FAIL      %d part(s) of the camera stack we KEEP are missing:" % len(missing))
        for label, path in missing:
            lines.append("            %-24s  expected %s" % (label, path))
        lines.append("          These are DIFFERENT packages from the one that was removed, and they")
        lines.append("          are what actually takes a photograph on this device. Their absence")
        lines.append("          degrades ordinary capture. Read the de-Googling block in aosp/rist.mk")
        lines.append("          before shipping; do not 'fix' this by deleting the check.")
    else:
        lines.append("ok        the Lyric HAL, PersistentBackgroundCameraServices and both service")
        lines.append("          jars are still in the image")

    return Result("camera extensions", state, lines)


FLASH_SCRIPT_NAMES = ("flash-all.sh", "flash-all.bat")

FLASH_MUST_NOT = (
    b"OFFICIAL GRAPHENEOS INSTALL GUIDE",
    b"grapheneos.org/install/web",
)

FLASH_MUST_HAVE = (b"RistOS",)


def _is_target_files(art):
    names = art.names()
    return any(n.startswith("META/") or n.startswith("SYSTEM/") for n in names)


def _find_flash_scripts(art):
    out = []
    for n in art.names():
        if n.rsplit("/", 1)[-1] in FLASH_SCRIPT_NAMES:
            out.append(n)
    return sorted(out)


def check_flash_scripts(art):
    found = _find_flash_scripts(art)
    if not found and _is_target_files(art):
        return Result("flash scripts", "skipped", [
            "%s is a target_files package, which carries no flash scripts." % art.path,
            "They are generated into the FACTORY zip afterwards. THIS RUN HAS SAID",
            "NOTHING about them -- re-run against <device>-factory-<build>.zip.",
        ])
    if not found:
        return Result("flash scripts", "unchecked", [
            "no flash-all.sh / flash-all.bat member in %s." % art.path,
            "These are generated into the FACTORY zip by",
            "device/common/generate-factory-images-common.sh, so a target_files",
            "package cannot answer this. Re-run this check against",
            "  <device>-factory-<build>.zip",
            "before publishing. Build 2026090600 shipped the GrapheneOS banner",
            "precisely because nothing looked at that artefact.",
        ])

    lines = []
    state = "pass"
    for name in found:
        blob = art.read(name)
        bad = [s for s in FLASH_MUST_NOT if s in blob]
        if bad:
            lines.append("FAIL      %s still contains %s"
                         % (name, ", ".join(repr(s.decode()) for s in bad)))
            lines.append("          Apply aosp/patches/0007-rist-debrand-factory-flash-scripts.patch")
            lines.append("          to device/common and REGENERATE the factory zip. The banner")
            lines.append("          tells the reader to go to grapheneos.org and start over, which")
            lines.append("          for someone holding a RistOS image is actively wrong advice.")
            state = "fail"
            continue
        missing = [s for s in FLASH_MUST_HAVE if s not in blob]
        if missing:
            lines.append("UNCHECKED %s carries no GrapheneOS banner, but also no %s."
                         % (name, ", ".join(repr(s.decode()) for s in missing)))
            lines.append("          Absence of the branded text has not been shown to mean patch")
            lines.append("          0007 applied -- the heredoc may simply have moved. Confirm by")
            lines.append("          hand before shipping.")
            if state != "fail":
                state = "unchecked"
            continue
        lines.append("ok        %s names RistOS and carries no GrapheneOS banner" % name)
    return Result("flash scripts", state, lines)


CHECKS = (check_boot_logo, check_bootanimation, check_recovery_text,
          check_bundled_apps, check_props, check_camera_extensions,
          check_flash_scripts)


def run(path, only=None):
    try:
        art = Artefact(path)
    except (ValueError, OSError) as e:
        print("FAIL  %s" % e, file=sys.stderr)
        return 2

    results = []
    try:
        for fn in CHECKS:
            r = fn(art)
            if only and r.name not in only:
                continue
            results.append(r)
    finally:
        art.close()

    print("=== RistOS debranding check: %s ===" % path)
    for r in results:
        print("--- %s" % r.name)
        for line in r.lines:
            print("    %s" % line)

    failed = [r.name for r in results if r.state == "fail"]
    unchecked = [r.name for r in results if r.state == "unchecked"]
    skipped = [r.name for r in results if r.state == "skipped"]

    print("")
    if failed:
        print("DEBRANDING FAILED -- do not ship this image.")
        print("Dirty: %s" % ", ".join(failed))
        if unchecked:
            print("Additionally could not be checked at all: %s" % ", ".join(unchecked))
        return 1
    if unchecked:
        print("INCONCLUSIVE -- this is NOT a pass.")
        print("Could not check: %s" % ", ".join(unchecked))
        print("Nothing has been established about those. The image is not cleared to ship.")
        return 2
    print("PASS: no GrapheneOS branding, and the camera-extensions removal is complete,")
    print("      in %s" % path)
    if skipped:
        print("")
        print("NOT COVERED BY THIS RUN: %s" % ", ".join(skipped))
        print("This artefact cannot carry them. The PASS above does not include them.")
    return 0


def _mkzip(entries, compress=zipfile.ZIP_STORED):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compress) as z:
        for name, data in entries:
            z.writestr(name, data)
    return buf.getvalue()


def _clean_tree(root):
    def put(rel, data):
        full = os.path.join(root, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as fh:
            fh.write(data)

    with open(os.path.join(BOOTLOGO_DIR, "android-logo-mask.png"), "rb") as fh:
        mask = fh.read()
    with open(os.path.join(BOOTLOGO_DIR, "android-logo-shine.png"), "rb") as fh:
        shine = fh.read()
    put(FRAMEWORK_RES, _mkzip([(LOGO_ENTRIES[0], mask), (LOGO_ENTRIES[1], shine)]))

    with open(BOOTANIM_ZIP, "rb") as fh:
        anim = fh.read()
    for n in BOOTANIM_NAMES:
        put(n, anim)

    put("RECOVERY/RAMDISK/system/bin/recovery",
        b"\x7fELF stub RistOS Recovery ... RistOS Fastboot ...")
    put("SYSTEM/build.prop", b"ro.build.fingerprint=google/stallion/stallion:17/CP2A/x\n")

    for _label, _part, _rel in CAMERA_KEEP:
        put(_PART_VARIANTS[_part][0] + _rel, b"PK\x03\x04")
    return root


def selftest():
    failures = 0

    def case(name, want_rc, want_txt, mutate=None, only=None):
        nonlocal failures
        tmp = tempfile.mkdtemp(prefix="debrandtest.")
        try:
            root = os.path.join(tmp, "tf")
            os.makedirs(root)
            _clean_tree(root)
            if mutate:
                mutate(root)
            out = io.StringIO()
            keep = sys.stdout
            sys.stdout = out
            try:
                rc = run(root, only=only)
            finally:
                sys.stdout = keep
            text = out.getvalue()
            ok = (rc == want_rc) and (want_txt in text)
            print("%-46s rc=%d (want %d) %s"
                  % (name, rc, want_rc, "OK" if ok else "*** MISMATCH ***"))
            if not ok:
                failures += 1
                print("    wanted substring: %r" % want_txt)
                for line in text.splitlines():
                    print("    | %s" % line)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def w(root, rel, data):
        full = os.path.join(root, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as fh:
            fh.write(data)

    case("clean tree passes", 0, "PASS: no GrapheneOS branding")

    case("framework-res carries THEIR logo", 1, "is NOT ours",
         lambda r: w(r, FRAMEWORK_RES, _mkzip([
             (LOGO_ENTRIES[0], b"gos hexagon bytes"),
             (LOGO_ENTRIES[1], b"gos shine bytes")])))
    case("framework-res logo entry vanished", 2, "is not in framework-res.apk",
         lambda r: w(r, FRAMEWORK_RES, _mkzip([("assets/images/other.png", b"x")])))
    case("framework-res unreadable", 2, "could not be read as a zip",
         lambda r: w(r, FRAMEWORK_RES, b"not a zip at all"))
    case("no framework-res at all", 2, "no SYSTEM/framework/framework-res.apk",
         lambda r: os.unlink(os.path.join(r, FRAMEWORK_RES)))
    case("only the shine was replaced", 1, "android-logo-mask.png is NOT ours",
         lambda r: w(r, FRAMEWORK_RES, _mkzip([
             (LOGO_ENTRIES[0], b"gos hexagon bytes"),
             (LOGO_ENTRIES[1], open(os.path.join(BOOTLOGO_DIR,
                                                 "android-logo-shine.png"), "rb").read())])))

    case("dark bootanimation missing", 1, "bootanimation-dark.zip is missing",
         lambda r: os.unlink(os.path.join(r, BOOTANIM_NAMES[1])))
    case("bootanimation is not ours", 1, "is not the animation in this repo",
         lambda r: w(r, BOOTANIM_NAMES[0],
                     _mkzip([("desc.txt", b"1080 2400 30\n")])))
    case("bootanimation deflated, not STORED", 1, "non-STORED",
         lambda r: w(r, BOOTANIM_NAMES[0],
                     _mkzip([("desc.txt", b"1080 2400 30\np 1 0 part0\n"),
                             ("part0/0001.png", b"\x89PNG" + b"\0" * 4000)],
                            compress=zipfile.ZIP_DEFLATED)))
    case("bootanimation not a zip", 1, "is not a readable zip",
         lambda r: w(r, BOOTANIM_NAMES[0], b"truncated"))

    case("recovery still says GrapheneOS", 1, "still contains 'GrapheneOS Recovery'",
         lambda r: w(r, "RECOVERY/RAMDISK/system/bin/recovery",
                     b"\x7fELF GrapheneOS Recovery GrapheneOS Fastboot"))
    case("fastbootd string missed, recovery done", 1, "GrapheneOS Fastboot",
         lambda r: w(r, "RECOVERY/RAMDISK/system/bin/recovery",
                     b"\x7fELF RistOS Recovery GrapheneOS Fastboot"))
    case("no recovery binary to read", 2, "no plain */bin/recovery member",
         lambda r: os.unlink(os.path.join(r, "RECOVERY/RAMDISK/system/bin/recovery")))
    case("recovery names neither brand", 2, "but also none of",
         lambda r: w(r, "RECOVERY/RAMDISK/system/bin/recovery", b"\x7fELF nothing here"))

    case("InfoApp survived the override", 1, "app.grapheneos.info",
         lambda r: w(r, "PRODUCT/app/InfoApp/app.grapheneos.info.apk", b"PK\x03\x04"))
    case("kept packages do not trip it", 0, "PASS: no GrapheneOS branding",
         lambda r: w(r, "PRODUCT/app/Camera/app.grapheneos.camera.apk", b"PK\x03\x04"))

    case("PixelCameraServices survived the override", 1,
         "PRODUCT/priv-app/PixelCameraServices/PixelCameraServices.apk",
         lambda r: w(r, "PRODUCT/priv-app/PixelCameraServices/PixelCameraServices.apk",
                     b"PK\x03\x04"))
    case("the shim jar survived the override", 1,
         "SYSTEM_EXT/framework/com.google.android.camerax.extensions.jar",
         lambda r: w(r, "SYSTEM_EXT/framework/com.google.android.camerax.extensions.jar",
                     b"dex\n035\x00"))
    case("the permission XML was not pruned", 1,
         "prune_camera_extensions.sh",
         lambda r: w(r, "PRODUCT/etc/permissions/androidx.camera.extensions.impl.xml",
                     b"<permissions/>"))
    case("the system_ext library XML was not pruned", 1,
         "SYSTEM_EXT/etc/permissions/com.google.android.camerax.extensions.xml",
         lambda r: w(r, "SYSTEM_EXT/etc/permissions/com.google.android.camerax.extensions.xml",
                     b"<permissions/>"))
    case("the preinstalled-packages XML was not pruned", 1,
         "preinstalled-packages-camera-services-base.xml",
         lambda r: w(r, "PRODUCT/etc/sysconfig/preinstalled-packages-camera-services-base.xml",
                     b"<config/>"))
    case("the shim jar's odex was not pruned", 1,
         "com.google.android.camerax.extensions.odex",
         lambda r: w(r, "SYSTEM_EXT/framework/oat/arm64/com.google.android.camerax.extensions.odex",
                     b"oat\n"))
    case("the Lyric HAL apex was removed too", 1, "the Lyric camera HAL apex",
         lambda r: os.unlink(os.path.join(r, "VENDOR/apex/com.google.pixel.camera.hal.apex")))
    case("PersistentBackgroundCameraServices was removed too", 1,
         "com.google.pixel.camera.services",
         lambda r: os.unlink(os.path.join(
             r, "SYSTEM_EXT/app/PersistentBackgroundCameraServices/"
                "PersistentBackgroundCameraServices.apk")))
    case("the kept camera libraries do not trip it", 0, "PASS: no GrapheneOS branding",
         lambda r: [w(r, "SYSTEM_EXT/framework/com.google.android.camera.extensions.jar", b"x"),
                    w(r, "SYSTEM_EXT/etc/permissions/com.google.android.camera.extensions.xml", b"x"),
                    w(r, "PRODUCT/etc/sysconfig/preinstalled-packages-camera-services-common.xml",
                      b"x"),
                    w(r, "PRODUCT/etc/permissions/privapp-permissions-google-p.xml", b"x")])
    case("system_ext under SYSTEM still satisfies the keeps", 0,
         "PASS: no GrapheneOS branding",
         lambda r: [os.rename(os.path.join(r, "SYSTEM_EXT"), os.path.join(r, "SYSTEM/system_ext"))])

    case("a prop points at grapheneos.org", 1, "build propert",
         lambda r: w(r, "SYSTEM/build.prop",
                     b"ro.build.fingerprint=google/stallion/x\n"
                     b"persist.sys.supl_host=supl.grapheneos.org\n"))
    case("no build.prop anywhere", 2, "no build.prop found",
         lambda r: os.unlink(os.path.join(r, "SYSTEM/build.prop")))

    case("target_files says it did NOT cover the flash scripts", 0,
         "NOT COVERED BY THIS RUN: flash scripts")

    _CLEAN_SH = (b"#!/bin/sh\n"
                 b"# DO NOT EDIT THIS FILE. THE RISTOS INSTALL INSTRUCTIONS WILL NEVER...\n"
                 b"# THIS FLASHES RistOS. IT IS NOT GrapheneOS.\n")
    _DIRTY_SH = (b"#!/bin/sh\n"
                 b"# DO NOT EDIT THIS FILE. THE OFFICIAL GRAPHENEOS INSTALL GUIDE WILL NEVER\n"
                 b"# RECOMMENDED https://grapheneos.org/install/web\n")

    def _factory(root, sh, bat=None):
        for junk in ("META", "SYSTEM", "SYSTEM_EXT", "PRODUCT", "VENDOR", "IMAGES"):
            shutil.rmtree(os.path.join(root, junk), ignore_errors=True)
        w(root, "stallion-factory-2026090600/flash-all.sh", sh)
        w(root, "stallion-factory-2026090600/flash-all.bat", bat if bat is not None else sh)

    case("a factory zip with the GrapheneOS banner is refused", 1,
         "still contains", lambda r: _factory(r, _DIRTY_SH), only={"flash scripts"})
    case("the .bat alone is enough to fail it", 1,
         "flash-all.bat", lambda r: _factory(r, _CLEAN_SH, _DIRTY_SH),
         only={"flash scripts"})
    case("a debranded factory zip passes", 0,
         "names RistOS and carries no GrapheneOS banner",
         lambda r: _factory(r, _CLEAN_SH), only={"flash scripts"})
    case("a flash script naming neither project is inconclusive", 2,
         "carries no GrapheneOS banner, but also no",
         lambda r: _factory(r, b"#!/bin/sh\necho hello\n"), only={"flash scripts"})

    print("")
    if failures:
        print("SELFTEST FAILED: %d case(s) did not behave as asserted." % failures)
        return 1
    print("SELFTEST PASS: every check above was shown to be able to fire AND to pass.")
    return 0


def main():
    ap = argparse.ArgumentParser(
        description="Fail a RistOS release that still carries GrapheneOS branding.")
    ap.add_argument("artefact", nargs="?",
                    help="<device>-target_files.zip, or an extracted copy of one")
    ap.add_argument("--selftest", action="store_true",
                    help="prove every check can fail and can pass; needs no artefact")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.artefact:
        ap.error("give a target_files zip/directory, or --selftest")
    return run(args.artefact)


if __name__ == "__main__":
    sys.exit(main())
