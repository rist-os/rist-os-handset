#!/usr/bin/env python3
"""The gate a RistOS release has to get through before anything is uploaded."""

import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

AVB_MAGIC = b"AVB0"
# avbtool's AvbVBMetaImageHeader FORMAT_STRING.
AVB_HEADER_FMT = ">4sII QQ I QQQQ QQQQ QQ Q I I 48s 80s"
AVB_ROLLBACK_INDEX_FIELD = 16

_TOOL_STUBS = {}


class Report(object):
    def __init__(self):
        self.findings = []
        self.unknowns = []

    def ok(self, msg):
        print("ok         %s" % msg)

    def note(self, msg):
        print("           %s" % msg)

    def fail(self, msg):
        self.findings.append(msg)
        print("FAIL       %s" % msg)

    def unknown(self, msg):
        self.unknowns.append(msg)
        print("UNCHECKED  %s" % msg)

    def verdict(self):
        print("")
        if self.unknowns:
            print("RELEASE GATE UNCHECKED -- %d question(s) were not answered:"
                  % len(self.unknowns))
            for u in self.unknowns:
                print("    - %s" % u)
            if self.findings:
                print("  ...and %d finding(s) as well." % len(self.findings))
            print("Nothing here clears this release. UNCHECKED is not a pass.")
            return 2
        if self.findings:
            print("RELEASE GATE FAIL -- %d finding(s). Do not sign, do not upload."
                  % len(self.findings))
            return 1
        print("RELEASE GATE PASS")
        return 0


def _matches(root, pattern, recursive):
    out = []
    if not root or not os.path.isdir(root):
        return out
    if recursive:
        for dirpath, dirnames, filenames in os.walk(root):
            for fn in sorted(filenames):
                if re.search(pattern, fn):
                    out.append(os.path.join(dirpath, fn))
    else:
        for fn in sorted(os.listdir(root)):
            p = os.path.join(root, fn)
            if os.path.isfile(p) and re.search(pattern, fn):
                out.append(p)
    return out


def find_artefact(pattern, reldir, label, rep):
    if not reldir:
        return None
    here = _matches(reldir, pattern, recursive=True)
    if len(here) > 1:
        rep.unknown("%s is ambiguous: %d files under %s match /%s/"
                    % (label, len(here), reldir, pattern))
        for p in here:
            rep.note("  %s" % p)
        rep.note("Name the one you mean explicitly. Picking one would be a guess, and the two")
        rep.note("candidates here are usually the pre-sign and post-sign packages, which are")
        rep.note("different files that answer this gate's questions differently.")
        return False
    if here:
        rep.note("%s: %s" % (label, here[0]))
        return here[0]
    parent = os.path.dirname(os.path.abspath(reldir))
    up = _matches(parent, pattern, recursive=False)
    if len(up) > 1:
        rep.unknown("%s is ambiguous: %d files directly in %s match /%s/"
                    % (label, len(up), parent, pattern))
        return False
    if up:
        rep.note("%s: %s (beside the release directory)" % (label, up[0]))
        return up[0]
    return None


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def spl_from_vbmeta(path):
    need = struct.calcsize(AVB_HEADER_FMT)
    with open(path, "rb") as f:
        data = f.read(need)
    if len(data) < need:
        raise ValueError("%s is %d bytes, too short to be a vbmeta image" % (path, len(data)))
    if data[:4] != AVB_MAGIC:
        raise ValueError("%s is not a vbmeta image (magic %r, not %r)"
                         % (path, data[:4], AVB_MAGIC))
    # The index is PLATFORM_SECURITY_PATCH_TIMESTAMP: the SPL date at midnight UTC.
    idx = struct.unpack(AVB_HEADER_FMT, data)[AVB_ROLLBACK_INDEX_FIELD]
    if not 946684800 <= idx <= 4102444800:          # 2000-01-01 .. 2100-01-01
        raise ValueError("rollback index %d is not a plausible SPL timestamp" % idx)
    if idx % 86400 != 0:
        raise ValueError("rollback index %d is not a midnight UTC, so it is not "
                         "PLATFORM_SECURITY_PATCH_TIMESTAMP" % idx)
    d = datetime.datetime(1970, 1, 1) + datetime.timedelta(seconds=idx)
    return idx, d.strftime("%Y-%m-%d")


def spl_from_target_files(path):
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        cands = [n for n in names
                 if os.path.basename(n) == "build.prop" and n.startswith(("SYSTEM/", "PRODUCT/"))]
        for n in sorted(cands):
            text = z.read(n).decode("utf-8", "replace")
            m = re.search(r"^ro\.build\.version\.security_patch=(\S+)", text, re.M)
            if m:
                return m.group(1), n
    return None, None


def tags_from_target_files(path):
    with zipfile.ZipFile(path) as z:
        cands = [n for n in z.namelist()
                 if os.path.basename(n) == "build.prop" and n.startswith(("SYSTEM/", "PRODUCT/"))]
        for n in sorted(cands):
            text = z.read(n).decode("utf-8", "replace")
            m = re.search(r"^ro\.build\.tags=(\S+)", text, re.M)
            if m:
                return m.group(1), n
    return None, None


def spl_from_ota(path):
    with zipfile.ZipFile(path) as z:
        text = z.read("META-INF/com/android/metadata").decode("utf-8", "replace")
    for line in text.splitlines():
        if line.startswith("post-security-patch-level="):
            return line.split("=", 1)[1].strip(), "META-INF/com/android/metadata"
    return None, None


def run_tool(script, argv, rep):
    if script in _TOOL_STUBS:
        return _TOOL_STUBS[script]
    path = os.path.join(HERE, script)
    if not os.path.isfile(path):
        return 127, "%s is not in this checkout" % path
    try:
        p = subprocess.run([sys.executable, path] + argv,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    except Exception as e:
        return 127, "could not run %s (%s)" % (path, e)
    return p.returncode, p.stdout.decode("utf-8", "replace")


def indent(text):
    for line in text.rstrip("\n").splitlines():
        print("           | %s" % line)


def adopt(rep, script, rc, out, what):
    indent(out)
    if rc == 0:
        rep.ok("%s: %s" % (script, what))
    elif rc == 1:
        rep.fail("%s reported a finding (see above). %s" % (script, what))
    elif rc == 127:
        rep.unknown("%s could not be run: %s" % (script, out.strip().splitlines()[0]
                                                 if out.strip() else "no output"))
    else:
        rep.unknown("%s could not reach a verdict (exit %d). Its output above is the reason, and "
                    "it is the thing to fix -- nothing here overrides it." % (script, rc))


def load_ledger(path, first_release, rep):
    if not os.path.exists(path):
        if first_release:
            rep.note("no ledger at %s; --first-release given, so this is release 1." % path)
            return {"releases": []}, None
        rep.unknown("no release ledger at %s." % path)
        rep.note("This gate cannot compare an SPL month against a previous release it has no")
        rep.note("record of, and 'the ledger is missing' looks identical to 'there has never")
        rep.note("been a release'. Point --ledger at the real one, or pass --first-release if")
        rep.note("this genuinely is the first. Do not create an empty one to get past this:")
        rep.note("an empty ledger silently turns every SPL change into an unchecked one.")
        return None, None
    try:
        with open(path) as f:
            led = json.load(f)
    except Exception as e:
        rep.unknown("release ledger %s could not be parsed (%s)" % (path, e))
        return None, None
    rels = led.get("releases")
    if not isinstance(rels, list):
        rep.unknown("release ledger %s has no 'releases' list" % path)
        return None, None
    if not rels:
        if first_release:
            rep.note("ledger %s is empty; --first-release given." % path)
            return led, None
        rep.unknown("release ledger %s exists but records no releases." % path)
        rep.note("Pass --first-release if that is the truth. An empty ledger is otherwise")
        rep.note("indistinguishable from one that lost its history.")
        return None, None
    return led, rels[-1]


def check_target_files(tf, rep, pre_signing=False):
    if tf is None:
        rep.fail("no signed target_files package for this release.")
        rep.note("It is the file every future incremental OTA is diffed against, the only way")
        rep.note("to prove after the fact that this build shipped no pre-authorised adb key,")
        rep.note("and the only way to repack an app-only change without another cold build.")
        rep.note("Once the build tree is gone it cannot be regenerated. Pull it back from the")
        rep.note("build machine (sign_public.sh stages it as releases/$BN/<device>-target_files.zip)")
        rep.note("before you release anything.")
        return None
    try:
        with zipfile.ZipFile(tf) as z:
            n = len([x for x in z.namelist() if not x.endswith("/")])
    except Exception as e:
        rep.unknown("target_files %s is not a readable zip (%s)" % (tf, e))
        return None
    if n == 0:
        rep.fail("target_files %s lists ZERO entries -- it is not a target_files package." % tf)
        return None
    try:
        tags, where = tags_from_target_files(tf)
    except Exception as e:
        tags, where = None, str(e)
    if tags is None:
        rep.unknown("no ro.build.tags in %s, so it cannot be shown to be the SIGNED package "
                    "rather than the unsigned intermediate beside it." % os.path.basename(tf))
    elif tags != "release-keys" and pre_signing:
        rep.ok("target_files is the unsigned intermediate (ro.build.tags=%s), as expected before "
               "signing" % tags)
        rep.note("--pre-signing was given, so this is the gate that runs BEFORE the signer.")
        rep.note("The signed package is checked again, without that flag, at publication time.")
    elif tags != "release-keys":
        rep.fail("target_files %s carries ro.build.tags=%s (%s)."
                 % (os.path.basename(tf), tags, where))
        rep.note("That is the UNSIGNED intermediate, not the package that ships. Both files share")
        rep.note("the basename <device>-target_files.zip; the signed one is inside")
        rep.note("releases/$BN/release-<device>-<BN>/.")
        rep.note("Everything measured against this file -- the adb-key scan, the SPL, and the")
        rep.note("hash every future incremental OTA is diffed against -- would be a statement")
        rep.note("about a package nobody ever received.")
        return None
    else:
        rep.ok("target_files is the signed package (ro.build.tags=release-keys)")

    size = os.path.getsize(tf)
    digest = sha256_file(tf)
    rep.ok("target_files retained: %s (%d entries, %d bytes)" % (os.path.basename(tf), n, size))
    rep.note("sha256 %s" % digest)
    rep.note("Recorded in the ledger so a later run can prove this exact file is still the one")
    rep.note("kept. Keep it with the release; nothing regenerates it.")
    return {"path": os.path.abspath(tf), "sha256": digest, "bytes": size, "entries": n}


def _previous_lost(prev, ack, rep, how):
    build = prev.get("build", "?")
    if ack and ack == build:
        rep.ok("previous release (%s) target_files is NOT retained -- ACKNOWLEDGED "
               "(--ack-previous-lost %s)." % (build, build))
        rep.note("%s" % how)
        rep.note("Every update from %s onwards must be a FULL package; no incremental OTA can" % build)
        rep.note("be built from it again, and nothing can now prove what that image contained.")
        rep.note("Recorded in the ledger against this release.")
        return True
    if ack:
        rep.fail("--ack-previous-lost %s was given, but the previous release is %s." % (ack, build))
        rep.note("Name the build actually being acknowledged. This is not a blanket override.")
        return True
    return False


def check_previous_retention(prev, override, rehash, rep, ack=None):
    if prev is None:
        return
    rec = prev.get("target_files")
    if not isinstance(rec, dict) or not rec.get("path"):
        if _previous_lost(prev, ack, rep,
                          "The ledger holds a record for it but no path, so it was never kept."):
            return
        rep.unknown("the previous release (%s) has no target_files record in the ledger, so "
                    "whether it was kept cannot be established." % prev.get("build", "?"))
        rep.note("If it is genuinely gone, acknowledge it by name: --ack-previous-lost %s"
                 % prev.get("build", "?"))
        return
    path = override or rec["path"]
    if not os.path.isfile(path):
        if _previous_lost(prev, ack, rep, "Recorded at %s, which does not exist." % path):
            return
        rep.fail("the PREVIOUS release's target_files is gone: %s" % path)
        rep.note("No incremental OTA can ever be built from %s again -- every update from that"
                 % prev.get("build", "the previous build"))
        rep.note("build onwards has to be a full ~1.6 GB package, and nothing can now prove")
        rep.note("what that image contained. If it merely moved, pass")
        rep.note("--previous-target-files <path> and this will verify it is the same file.")
        return
    size = os.path.getsize(path)
    if rec.get("bytes") is not None and size != rec["bytes"]:
        rep.fail("the previous release's target_files at %s is %d bytes; the ledger recorded %d."
                 % (path, size, rec["bytes"]))
        rep.note("This is not the file that was released. A truncated or replaced target_files")
        rep.note("produces incremental OTAs that no device will accept.")
        return
    if rehash:
        got = sha256_file(path)
        if got != rec.get("sha256"):
            rep.fail("the previous release's target_files hashes to %s; the ledger recorded %s."
                     % (got, rec.get("sha256")))
            return
        rep.ok("previous release (%s) target_files retained and re-hashed: it is the same file"
               % prev.get("build", "?"))
        return
    rep.ok("previous release (%s) target_files retained: %s, %d bytes as recorded"
           % (prev.get("build", "?"), os.path.basename(path), size))
    rep.note("Size matched the ledger; its content was not re-hashed -- pass")
    rep.note("--rehash-previous to prove byte-for-byte that it is the same file.")


def collect_spl(tf, vbmeta, ota, rep):
    seen = []          # (source, value)
    idx = None
    if vbmeta:
        try:
            idx, d = spl_from_vbmeta(vbmeta)
            seen.append(("vbmeta.img rollback_index=%d" % idx, d))
        except Exception as e:
            rep.unknown("could not read the rollback index from %s: %s" % (vbmeta, e))
    if tf:
        try:
            v, where = spl_from_target_files(tf)
            if v:
                seen.append(("target_files %s" % where, v))
        except Exception as e:
            rep.unknown("could not read build.prop out of %s: %s" % (tf, e))
    if ota:
        try:
            v, where = spl_from_ota(ota)
            if v:
                seen.append(("OTA %s" % where, v))
        except Exception as e:
            rep.unknown("could not read the OTA metadata out of %s: %s" % (ota, e))

    if not seen:
        rep.unknown("no security patch level could be read from this release.")
        rep.note("Supply --vbmeta (the rollback index is the authoritative number) or a")
        rep.note("target_files whose build.prop carries ro.build.version.security_patch.")
        rep.note("Without it the one-way rollback question cannot be asked at all.")
        return None, None

    for src, val in seen:
        rep.note("SPL %s  <- %s" % (val, src))
    months = set(v[:7] for _, v in seen)
    if len(months) > 1:
        rep.fail("the sources disagree about this release's SPL month: %s"
                 % ", ".join(sorted(months)))
        rep.note("The bootloader's rollback index, the OS's own build.prop and the OTA metadata")
        rep.note("are written by different build steps and only agree on an intact release.")
        return None, idx
    return sorted(v for _, v in seen)[-1], idx


def check_spl_change(spl, idx, prev, ack, rep):
    if spl is None:
        return
    month = spl[:7]
    rep.note("BOARD_AVB_ROLLBACK_INDEX := PLATFORM_SECURITY_PATCH_TIMESTAMP, so the index tracks")
    rep.note("the SPL MONTH and not the build: every build inside %s shares one index and is"
             % month)
    rep.note("interchangeable with its siblings as far as the bootloader is concerned.")

    if prev is None:
        rep.ok("SPL %s. No previous release to compare against (--first-release)." % spl)
        rep.note("Nothing can be rolled back past release 1, so there is nothing to acknowledge")
        rep.note("here. Every later run compares against this one.")
        if ack:
            rep.fail("--ack-spl-change %s was given, but there is no previous release to have "
                     "changed from." % ack)
        return

    pmonth = (prev.get("spl") or "")[:7]
    if not re.fullmatch(r"\d{4}-\d{2}", pmonth):
        rep.unknown("the previous release (%s) recorded no usable SPL (%r), so whether this one "
                    "crosses a month cannot be established."
                    % (prev.get("build", "?"), prev.get("spl")))
        return

    if month == pmonth:
        rep.ok("SPL month unchanged: %s, same as release %s. The rollback index does not move, "
               "so this build and that one are mutually flashable."
               % (month, prev.get("build", "?")))
        if ack:
            rep.fail("--ack-spl-change %s was given, but this release does NOT change the SPL "
                     "month (still %s)." % (ack, month))
            rep.note("You acknowledged something that is not happening. Either you are looking")
            rep.note("at a different artefact than the one you meant to ship, or the build did")
            rep.note("not pick up the SPL you thought it did. Both are worth stopping for.")
        return

    if month < pmonth:
        rep.fail("this release GOES BACKWARDS: SPL %s, against %s in release %s."
                 % (month, pmonth, prev.get("build", "?")))
        rep.note("There is no acknowledgement for this and --ack-spl-change will not clear it.")
        rep.note("Any handset that already booted the %s image has that rollback index burned"
                 % pmonth)
        rep.note("into hardware-backed storage; it will refuse this image at the bootloader and")
        rep.note("there is no fastboot flag, recovery mode or unlock that puts it back. Devices")
        rep.note("that have not yet taken %s would accept this one and silently lose the" % pmonth)
        rep.note("intervening patches. Rebuild against at least %s." % pmonth)
        return

    if ack == month:
        rep.ok("SPL month change %s -> %s, ACKNOWLEDGED (--ack-spl-change %s)."
               % (pmonth, month, ack))
        rep.note("rollback index moves to %s. Every handset that boots this image can never"
                 % (idx if idx is not None else "the new value"))
        rep.note("go back to a %s image. That is now a deliberate, recorded decision." % pmonth)
        return

    banner = ("#  SPL MONTH CHANGE: %s -> %s   THIS IS ONE-WAY AND CANNOT BE UNDONE  #"
              % (pmonth, month))
    print("")
    print("  " + "#" * len(banner))
    print("  " + banner)
    print("  " + "#" * len(banner))
    rep.fail("this release crosses from SPL month %s into %s and nobody acknowledged it."
             % (pmonth, month))
    rep.note("BOARD_AVB_ROLLBACK_INDEX moves to %s. The first time a handset boots this"
             % (idx if idx is not None else "a higher value"))
    rep.note("image, its bootloader writes that index into hardware-backed anti-rollback")
    rep.note("storage. From that moment the device will REFUSE every image built in %s or"
             % pmonth)
    rep.note("earlier -- permanently. Not a setting, not a fastboot flag, not recoverable by")
    rep.note("unlocking, not recoverable by us. If this build turns out to be bad, the last")
    rep.note("known-good image (release %s) can no longer be put back on any device that"
             % prev.get("build", "?"))
    rep.note("took it.")
    rep.note("")
    rep.note("Builds WITHIN one SPL month are free to respin -- they share an index. This is")
    rep.note("the one release in the month that is not.")
    rep.note("")
    rep.note("If that is intended, say so explicitly:")
    rep.note("    --ack-spl-change %s" % month)


def gate(args, rep):
    tf = args.target_files
    vbmeta = args.vbmeta
    ota = args.ota
    reldir = args.release_dir

    if reldir and not os.path.isdir(reldir):
        rep.unknown("no such release directory: %s" % reldir)
        return
    if tf is None:
        tf = find_artefact(r"target_files.*\.zip$", reldir, "target_files", rep)
    if vbmeta is None:
        vbmeta = find_artefact(r"^vbmeta\.img$", reldir, "vbmeta", rep)
    if ota is None:
        ota = find_artefact(r"ota_update.*\.zip$", reldir, "OTA package", rep)
    if tf is False or vbmeta is False or ota is False:
        return
    tf = tf or None
    vbmeta = vbmeta or None
    ota = ota or None

    for label, p in (("--target-files", tf), ("--vbmeta", vbmeta), ("--ota", ota)):
        if p is not None and not os.path.isfile(p):
            rep.unknown("%s %s is not a file" % (label, p))
            return

    print("=== 1. adb key ===")
    if tf is None:
        rep.unknown("no target_files, so the adb-key question cannot be asked of this release.")
        rep.note("The key lives inside super.img; a factory zip cannot answer it. See")
        rep.note("tools/check_adb_keys.py.")
    else:
        argv = [tf, "--" + args.variant]
        if args.expect_key:
            argv += ["--expect-key", args.expect_key]
        rc, out = run_tool("check_adb_keys.py", argv, rep)
        adopt(rep, "check_adb_keys.py", rc, out,
              "no pre-authorised adb key in this %s image" % args.variant
              if args.variant == "public" else
              "the %s image carries the expected adb key" % args.variant)

    print("")
    print("=== 2. OTA certificates ===")
    if tf is None:
        rep.unknown("no target_files, so the otacerts trust store cannot be examined.")
    else:
        rc, out = run_tool("check_otacerts.py", [tf], rep)
        adopt(rep, "check_otacerts.py", rc, out,
              "the image's otacerts.zip carries the reserve certificate beside the primary")

    print("")
    print("=== 3. target_files retention ===")
    tf_rec = check_target_files(tf, rep, args.pre_signing)

    print("")
    print("=== 4. SPL month / rollback index ===")
    led, prev = load_ledger(args.ledger, args.first_release, rep)
    spl, idx = collect_spl(tf, vbmeta, ota, rep)
    if led is None:
        rep.note("(no ledger, so no comparison was made)")
    else:
        check_previous_retention(prev, args.previous_target_files, args.rehash_previous, rep,
                                args.ack_previous_lost)
        check_spl_change(spl, idx, prev, args.ack_spl_change, rep)

    return led, tf_rec, spl, idx


def record(args, led, tf_rec, spl, idx, rep):
    build = args.build
    if build is None and tf_rec:
        m = re.search(r"(\d{8,})", os.path.basename(os.path.dirname(tf_rec["path"])))
        build = m.group(1) if m else None
    entry = {
        "build": build,
        "variant": args.variant,
        "recorded_utc": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
        "spl": spl,
        "spl_month": spl[:7] if spl else None,
        "rollback_index": idx,
        "target_files": tf_rec,
        "acknowledged_spl_change": args.ack_spl_change,
        "acknowledged_previous_lost": args.ack_previous_lost,
    }
    led.setdefault("releases", []).append(entry)
    d = os.path.dirname(os.path.abspath(args.ledger))
    if d and not os.path.isdir(d):
        os.makedirs(d)
    tmp = args.ledger + ".tmp"
    with open(tmp, "w") as f:
        json.dump(led, f, indent=2, sort_keys=True)
        f.write("\n")
    os.replace(tmp, args.ledger)
    print("recorded release %s (SPL %s) in %s" % (build, spl, args.ledger))


def build_parser():
    p = argparse.ArgumentParser(add_help=True, description=__doc__.splitlines()[0])
    p.add_argument("release_dir", nargs="?")
    p.add_argument("--variant", choices=("public", "private"))
    p.add_argument("--target-files")
    p.add_argument("--vbmeta")
    p.add_argument("--ota")
    p.add_argument("--expect-key")
    p.add_argument("--ledger",
                   default=os.environ.get("RIST_RELEASE_LEDGER",
                                          os.path.join(REPO, "releases", "release-ledger.json")))
    p.add_argument("--first-release", action="store_true")
    p.add_argument("--ack-spl-change")
    p.add_argument("--ack-previous-lost")
    p.add_argument("--pre-signing", action="store_true")
    p.add_argument("--previous-target-files")
    p.add_argument("--rehash-previous", action="store_true")
    p.add_argument("--record", action="store_true")
    p.add_argument("--build")
    p.add_argument("--selftest", action="store_true")
    return p


def main(argv):
    args = build_parser().parse_args(argv)
    if args.selftest:
        return selftest()
    if not args.release_dir and not args.target_files:
        sys.stderr.write("give a release directory, or --target-files <zip>\n")
        return 2
    if not args.variant:
        sys.stderr.write("--variant public|private is required (it decides what 'correct' means "
                         "for the adb key)\n")
        return 2
    if args.ack_spl_change and not re.fullmatch(r"\d{4}-\d{2}", args.ack_spl_change):
        sys.stderr.write("--ack-spl-change takes the NEW SPL month as YYYY-MM, spelled out, so "
                         "that acknowledging one month cannot silently acknowledge another\n")
        return 2

    rep = Report()
    result = gate(args, rep)
    rc = rep.verdict()
    if args.record:
        if rc != 0:
            print("NOT recorded: the gate did not pass, and the ledger is the record of what was "
                  "released.")
        elif result:
            led, tf_rec, spl, idx = result
            record(args, led, tf_rec, spl, idx, rep)
    return rc


def _mk_tf(path, adb_key=None, spl="2026-08-05", tags="release-keys"):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("META/misc_info.txt", "recovery_api_version=3\n")
        z.writestr("SYSTEM/build.prop",
                   "ro.build.id=RIST\nro.build.version.security_patch=%s\nro.build.tags=%s\n"
                   % (spl, tags))
        z.writestr("PRODUCT/etc/permissions/x.xml", "<permissions></permissions>\n")
        if adb_key:
            z.writestr("PRODUCT/etc/security/adb_keys", adb_key)
    return path


def _mk_vbmeta(path, spl="2026-08-05"):
    d = datetime.datetime.strptime(spl, "%Y-%m-%d")
    idx = int((d - datetime.datetime(1970, 1, 1)).total_seconds())
    header = struct.pack(AVB_HEADER_FMT, AVB_MAGIC, 1, 0, 0, 0, 2,
                         0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                         idx, 0, 0, b"avbtool 1.4.0", b"")
    with open(path, "wb") as f:
        f.write(header + b"\0" * 1024)
    return path


def _ledger(path, build, spl, tf_path):
    exists = os.path.isfile(tf_path)
    entry = {"build": build, "variant": "public", "spl": spl, "spl_month": spl[:7],
             "target_files": {"path": tf_path,
                              "bytes": os.path.getsize(tf_path) if exists else 4242,
                              "sha256": sha256_file(tf_path) if exists else "00" * 32,
                              "entries": 3}}
    with open(path, "w") as f:
        json.dump({"releases": [entry]}, f, indent=2)
    return path


def _ledger_nopath(path, build, spl):
    entry = {"build": build, "variant": "public", "spl": spl, "spl_month": spl[:7],
             "target_files": {"bytes": 4242, "sha256": "00" * 32, "entries": 3}}
    with open(path, "w") as f:
        json.dump({"releases": [entry]}, f, indent=2)
    return path


def selftest():
    import io
    import contextlib

    tmp = tempfile.mkdtemp(prefix="relgate-selftest-")
    KEY = "QAAAA" + "B" * 300 + "== builder@workstation\n"
    bad = 0
    try:
        rel = os.path.join(tmp, "release-stallion-2026083001")
        os.makedirs(rel)
        tf_clean = _mk_tf(os.path.join(rel, "stallion-target_files.zip"))
        vb_aug = _mk_vbmeta(os.path.join(rel, "vbmeta.img"))

        rel2 = os.path.join(tmp, "release-keyed")
        os.makedirs(rel2)
        _mk_tf(os.path.join(rel2, "stallion-target_files.zip"), adb_key=KEY)
        _mk_vbmeta(os.path.join(rel2, "vbmeta.img"))

        rel_sep = os.path.join(tmp, "release-september")
        os.makedirs(rel_sep)
        _mk_tf(os.path.join(rel_sep, "stallion-target_files.zip"), spl="2026-09-05")
        _mk_vbmeta(os.path.join(rel_sep, "vbmeta.img"), spl="2026-09-05")

        rel_back = os.path.join(tmp, "release-july")
        os.makedirs(rel_back)
        _mk_tf(os.path.join(rel_back, "stallion-target_files.zip"), spl="2026-07-05")
        _mk_vbmeta(os.path.join(rel_back, "vbmeta.img"), spl="2026-07-05")

        rel_disagree = os.path.join(tmp, "release-disagree")
        os.makedirs(rel_disagree)
        _mk_tf(os.path.join(rel_disagree, "stallion-target_files.zip"), spl="2026-09-05")
        _mk_vbmeta(os.path.join(rel_disagree, "vbmeta.img"), spl="2026-08-05")

        rel_notf = os.path.join(tmp, "release-no-target-files")
        os.makedirs(rel_notf)
        _mk_vbmeta(os.path.join(rel_notf, "vbmeta.img"))

        rel_two = os.path.join(tmp, "release-ambiguous")
        os.makedirs(os.path.join(rel_two, "signed"))
        _mk_tf(os.path.join(rel_two, "stallion-target_files.zip"))
        _mk_tf(os.path.join(rel_two, "signed", "stallion-target_files.zip"))
        _mk_vbmeta(os.path.join(rel_two, "vbmeta.img"))

        rel_unsigned = os.path.join(tmp, "release-unsigned")
        os.makedirs(rel_unsigned)
        _mk_tf(os.path.join(rel_unsigned, "stallion-target_files.zip"), tags="test-keys")
        _mk_vbmeta(os.path.join(rel_unsigned, "vbmeta.img"))

        kept = os.path.join(tmp, "prev-kept")
        os.makedirs(kept)
        tf_prev = _mk_tf(os.path.join(kept, "stallion-target_files.zip"))
        led_aug = _ledger(os.path.join(tmp, "ledger-aug.json"), "2026082904", "2026-08-05",
                          tf_prev)
        led_sep = _ledger(os.path.join(tmp, "ledger-sep.json"), "2026090101", "2026-09-05",
                          tf_prev)
        led_gone = _ledger(os.path.join(tmp, "ledger-gone.json"), "2026082904", "2026-08-05",
                           os.path.join(tmp, "deleted", "stallion-target_files.zip"))
        led_nopath = _ledger_nopath(os.path.join(tmp, "ledger-nopath.json"),
                                    "2026083110", "2026-08-05")
        led_bad = os.path.join(tmp, "ledger-corrupt.json")
        with open(led_bad, "w") as f:
            f.write("{ this is not json")
        led_empty = os.path.join(tmp, "ledger-empty.json")
        with open(led_empty, "w") as f:
            json.dump({"releases": []}, f)

        OK = (0, "OTACERTS PASS (stub)\n")
        NOCERT = (2, "CANNOT TELL: no otacerts.zip (stub)\n")

        # (name, argv, otacerts stub, expected rc, expected substring)
        cases = [
            ("clean release, same SPL month",
             [rel, "--variant", "public", "--ledger", led_aug], OK, 0,
             "SPL month unchanged"),

            ("UNSIGNED target_files (test-keys) is REFUSED",
             [rel_unsigned, "--variant", "public", "--ledger", led_aug], OK, 1,
             "ro.build.tags=test-keys"),

            ("--pre-signing accepts the unsigned intermediate",
             [rel_unsigned, "--variant", "public", "--ledger", led_aug, "--pre-signing"], OK, 0,
             "as expected before signing"),

            ("adb key in a PUBLIC release fires",
             [rel2, "--variant", "public", "--ledger", led_aug], OK, 1,
             "check_adb_keys.py reported a finding"),

            ("same artefact is FINE as the private variant",
             [rel2, "--variant", "private", "--ledger", led_aug], OK, 0,
             "RELEASE GATE PASS"),

            ("SPL month change without acknowledgement fires",
             [rel_sep, "--variant", "public", "--ledger", led_aug], OK, 1,
             "crosses from SPL month 2026-08 into 2026-09 and nobody acknowledged"),

            ("SPL month change WITH acknowledgement passes",
             [rel_sep, "--variant", "public", "--ledger", led_aug,
              "--ack-spl-change", "2026-09"], OK, 0,
             "ACKNOWLEDGED"),

            ("acknowledging the WRONG month does not clear it",
             [rel_sep, "--variant", "public", "--ledger", led_aug,
              "--ack-spl-change", "2026-10"], OK, 1,
             "nobody acknowledged it"),

            ("acknowledging a change that is not happening fires",
             [rel, "--variant", "public", "--ledger", led_aug,
              "--ack-spl-change", "2026-08"], OK, 1,
             "does NOT change the SPL month"),

            ("going BACKWARDS an SPL month fires, ack or not",
             [rel_back, "--variant", "public", "--ledger", led_sep,
              "--ack-spl-change", "2026-07"], OK, 1,
             "GOES BACKWARDS"),

            ("sources disagreeing about the SPL fires",
             [rel_disagree, "--variant", "public", "--ledger", led_aug], OK, 1,
             "disagree about this release's SPL month"),

            ("previous release with NO target_files path is UNCHECKED without an ack",
             [rel, "--variant", "public", "--ledger", led_nopath], OK, 2,
             "has no target_files record in the ledger"),

            ("--ack-previous-lost, named correctly, clears it",
             [rel, "--variant", "public", "--ledger", led_nopath,
              "--ack-previous-lost", "2026083110"], OK, 0,
             "ACKNOWLEDGED (--ack-previous-lost 2026083110)"),

            ("--ack-previous-lost naming the WRONG build is refused",
             [rel, "--variant", "public", "--ledger", led_nopath,
              "--ack-previous-lost", "2026083109"], OK, 1,
             "but the previous release is 2026083110"),

            ("previous release's target_files deleted fires",
             [rel, "--variant", "public", "--ledger", led_gone], OK, 1,
             "PREVIOUS release's target_files is gone"),

            ("no target_files for this release fires (and no sibling is borrowed)",
             [rel_notf, "--variant", "public", "--ledger", led_aug], OK, 2,
             "no signed target_files package"),

            ("two target_files in one release is UNCHECKED, not a guess",
             [rel_two, "--variant", "public", "--ledger", led_aug], OK, 2,
             "target_files is ambiguous"),

            ("missing ledger is UNCHECKED, not a pass",
             [rel, "--variant", "public", "--ledger", os.path.join(tmp, "nope.json")], OK, 2,
             "no release ledger at"),

            ("corrupt ledger is UNCHECKED, not a pass",
             [rel, "--variant", "public", "--ledger", led_bad], OK, 2,
             "could not be parsed"),

            ("empty ledger is UNCHECKED without --first-release",
             [rel, "--variant", "public", "--ledger", led_empty], OK, 2,
             "records no releases"),

            ("--first-release accepts having no history",
             [rel, "--variant", "public", "--ledger", os.path.join(tmp, "nope.json"),
              "--first-release"], OK, 0,
             "No previous release to compare against"),

            ("otacerts UNCHECKED is UNCHECKED here too, never a pass",
             [rel, "--variant", "public", "--ledger", led_aug], NOCERT, 2,
             "could not reach a verdict"),

            ("otacerts FINDING is a finding here",
             [rel, "--variant", "public", "--ledger", led_aug],
             (1, "OTACERTS FAIL (stub)\n"), 1,
             "check_otacerts.py reported a finding"),

            ("release directory does not exist",
             [os.path.join(tmp, "nothing-here"), "--variant", "public", "--ledger", led_aug],
             OK, 2, "no such release directory"),

            ("REAL check_otacerts.py subprocess, fixture with no otacerts.zip",
             [rel, "--variant", "public", "--ledger", led_aug], None, 2,
             "contains no otacerts.zip"),
        ]

        for name, argv, stub, want_rc, want_txt in cases:
            _TOOL_STUBS.clear()
            if stub is not None:
                _TOOL_STUBS["check_otacerts.py"] = stub
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                got = main(argv)
            out = buf.getvalue()
            problems = []
            if got != want_rc:
                problems.append("exit %d, expected %d" % (got, want_rc))
            if want_txt not in out:
                problems.append('output never said "%s"' % want_txt)
            if problems:
                bad += 1
                print("SELFTEST FAIL  %s: %s" % (name, "; ".join(problems)))
                for line in out.splitlines():
                    print("               | %s" % line)
            else:
                print("SELFTEST ok    %-58s exit %d" % (name, got))

        _TOOL_STUBS.clear()
        _TOOL_STUBS["check_otacerts.py"] = OK
        led_rec = os.path.join(tmp, "ledger-record.json")
        shutil.copy(led_aug, led_rec)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = main([rel, "--variant", "public", "--ledger", led_rec, "--record"])
        n = len(json.load(open(led_rec))["releases"])
        if rc == 0 and n == 2:
            print("SELFTEST ok    %-58s exit 0" % "--record appends on a pass")
        else:
            bad += 1
            print("SELFTEST FAIL  --record on a pass: exit %d, ledger has %d entries" % (rc, n))

        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = main([rel2, "--variant", "public", "--ledger", led_rec, "--record"])
        n2 = len(json.load(open(led_rec))["releases"])
        if rc == 1 and n2 == 2 and "NOT recorded" in buf.getvalue():
            print("SELFTEST ok    %-58s exit 1" % "--record refuses on a failure")
        else:
            bad += 1
            print("SELFTEST FAIL  --record on a failure: exit %d, ledger has %d entries"
                  % (rc, n2))

        print("SELFTEST %s" % ("PASS" if bad == 0 else "FAIL -- %d case(s) wrong" % bad))
        return 0 if bad == 0 else 1
    finally:
        _TOOL_STUBS.clear()
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
