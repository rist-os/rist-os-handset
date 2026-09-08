#!/usr/bin/env python3
"""Assert the two things about the bundled Organic Maps APK that an image build gets wrong silently.

    tools/check_organicmaps_apk.py <stallion-target_files.zip | OrganicMaps.apk> [--cert-sha256 HEX]
    tools/check_organicmaps_apk.py --selftest
"""

import os
import re
import shutil
import subprocess
import sys
import struct
import tempfile
import zipfile

APK_IN_TF = "PRODUCT/app/OrganicMaps/OrganicMaps.apk"
APKCERTS = "META/apkcerts.txt"
PAGE = 16384  # 16 KB page size
BANNED_CERTS = ("platform", "shared", "media", "networkstack", "nfc", "bluetooth", "sdk_sandbox")

findings = []
notes = []


def data_offset(zf, info):
    with open(zf.filename, "rb") as f:
        f.seek(info.header_offset)
        head = f.read(30)
        namelen, extralen = struct.unpack("<HH", head[26:30])
        return info.header_offset + 30 + namelen + extralen


def check_jni(apk_path):
    with zipfile.ZipFile(apk_path) as z:
        libs = [i for i in z.infolist()
                if i.filename.startswith("lib/") and i.filename.endswith(".so")]
        if not libs:
            findings.append("no lib/**/*.so in the APK at all — wrong file, or upstream changed")
            return
        arm64 = [i for i in libs if i.filename.startswith("lib/arm64-v8a/")]
        if not arm64:
            findings.append("no lib/arm64-v8a/*.so — the device's only usable ABI is missing")
        before = len(findings)
        for i in libs:
            if i.compress_type != zipfile.ZIP_STORED:
                findings.append(
                    "%s is Deflated, must be Stored. Something made Soong copy the APK verbatim "
                    "(presigned/preprocessed?); the app will throw UnsatisfiedLinkError."
                    % i.filename)
                continue
            off = data_offset(z, i)
            if i.filename.startswith("lib/arm64-v8a/") and off % PAGE:
                findings.append(
                    "%s payload at offset %d is not %d-aligned (mod=%d); bionic cannot mmap it "
                    "in place on a 16 KB-page device." % (i.filename, off, PAGE, off % PAGE))
        if len(findings) == before:
            notes.append("%d JNI libs, all Stored; arm64 payloads %d-aligned" % (len(libs), PAGE))


def check_apkcerts(tf_path):
    with zipfile.ZipFile(tf_path) as z:
        if APKCERTS not in z.namelist():
            findings.append("no %s — is this a target_files zip?" % APKCERTS)
            return
        line = None
        for raw in z.read(APKCERTS).decode("utf-8", "replace").splitlines():
            if 'name="OrganicMaps.apk"' in raw:
                line = raw.strip()
                break
        if line is None:
            findings.append('no OrganicMaps.apk entry in %s — the module did not build, or it is '
                            'no longer in PRODUCT_PACKAGES' % APKCERTS)
            return
        m = re.search(r'certificate="([^"]*)"', line)
        cert = m.group(1) if m else ""
        base = os.path.basename(cert).replace(".x509.pem", "")
        if base in BANNED_CERTS:
            findings.append('apkcerts says certificate="%s". A third-party APK must not carry a '
                            'platform-class identity — the module should be default_dev_cert.' % cert)
        elif cert == "PRESIGNED":
            findings.append("apkcerts says PRESIGNED. That means Soong copied the APK verbatim and "
                            "the JNI libs are still compressed — see the JNI check.")
        elif base in ("testkey", "releasekey"):
            notes.append('apkcerts certificate="%s" (maps to keys/<device>/releasekey)' % cert)
        else:
            notes.append('apkcerts certificate="%s" — unrecognised, check it by hand' % cert)


def v1_signer_sha256(apk_path):
    if not shutil.which("openssl"):
        return None
    with zipfile.ZipFile(apk_path) as z:
        blocks = [n for n in z.namelist()
                  if n.startswith("META-INF/") and n.upper().endswith((".RSA", ".EC", ".DSA"))]
        if not blocks:
            return None
        der = z.read(blocks[0])
    p1 = subprocess.run(["openssl", "pkcs7", "-inform", "DER", "-print_certs"],
                        input=der, capture_output=True)
    if p1.returncode:
        return None
    p2 = subprocess.run(["openssl", "x509", "-noout", "-fingerprint", "-sha256"],
                        input=p1.stdout, capture_output=True)
    if p2.returncode:
        return None
    return p2.stdout.decode().strip().split("=")[-1].replace(":", "").lower()


def _mk_apk(path, mode="stored", abis=("arm64-v8a", "armeabi-v7a")):
    z = zipfile.ZipFile(path, "w")
    z.writestr("AndroidManifest.xml", "fixture manifest")
    z.writestr("classes.dex", "dex" * 100)
    for abi in abis:
        name = "lib/%s/liborganicmaps.so" % abi
        zi = zipfile.ZipInfo(name)
        zi.compress_type = zipfile.ZIP_DEFLATED if mode == "deflated" else zipfile.ZIP_STORED
        if mode == "stored":
            off = z.fp.tell()
            pad = (-(off + 30 + len(name.encode()))) % PAGE
            zi.extra = b"\x00" * pad
        elif mode == "misaligned":
            off = z.fp.tell()
            pad = ((-(off + 30 + len(name.encode()))) % PAGE + 7) % PAGE
            zi.extra = b"\x00" * pad
        z.writestr(zi, b"\x7fELF" + b"\0" * 4096)
    z.close()
    return path


TESTKEY = "build/make/target/product/security/testkey"


def _mk_tf(path, apk=None, cert=TESTKEY):
    z = zipfile.ZipFile(path, "w")
    z.writestr("META/misc_info.txt", "recovery_api_version=3\n")
    if cert is not False:
        line = ('name="talkback.apk" certificate="%s" private_key="%s.pk8" partition="product"\n'
                % (TESTKEY, TESTKEY))
        if cert is not None:
            line += ('name="OrganicMaps.apk" certificate="%s" private_key="%s.pk8" '
                     'partition="product"\n' % (cert, cert))
        z.writestr(APKCERTS, line)
    if apk:
        z.write(apk, APK_IN_TF)
    z.close()
    return path


def selftest():
    tmp = tempfile.mkdtemp(prefix="omselftest.")
    bad = 0

    def case(name, want_rc, want_txt, *argv):
        p = subprocess.run([sys.executable, os.path.abspath(__file__)] + list(argv),
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        out = p.stdout.decode("utf-8", "replace")
        if p.returncode != want_rc:
            print("SELFTEST FAIL  %s: exit %d, expected %d" % (name, p.returncode, want_rc))
            for line in out.splitlines():
                print("               | %s" % line)
            return 1
        if want_txt not in out:
            print('SELFTEST FAIL  %s: exit %d was right, but the output never said "%s"'
                  % (name, p.returncode, want_txt))
            for line in out.splitlines():
                print("               | %s" % line)
            return 1
        print('SELFTEST ok    %-52s exit %d, said "%s"' % (name, p.returncode, want_txt))
        return 0

    try:
        j = lambda n: os.path.join(tmp, n)

        good = _mk_apk(j("good.apk"), "stored")
        bad += case("a correct APK passes", 0, "ORGANICMAPS PASS", good)
        bad += case("a correct target_files passes", 0, "ORGANICMAPS PASS",
                    _mk_tf(j("good-tf.zip"), good))

        bad += case("Deflated JNI libs fire (presigned/preprocessed)", 1, "is Deflated, must be Stored",
                    _mk_apk(j("deflated.apk"), "deflated"))
        bad += case("a mis-aligned arm64 payload fires", 1, "is not 16384-aligned",
                    _mk_apk(j("misaligned.apk"), "misaligned"))
        bad += case("no arm64 lib fires", 1, "no lib/arm64-v8a/*.so",
                    _mk_apk(j("noarm64.apk"), "stored", abis=("armeabi-v7a",)))
        bad += case("an APK with no JNI at all fires", 1, "no lib/**/*.so in the APK at all",
                    _mk_apk(j("nolibs.apk"), "stored", abis=()))

        bad += case("certificate=platform fires (the defect that shipped)", 1,
                    "must not carry a platform-class identity",
                    _mk_tf(j("platform-tf.zip"), good,
                           cert="build/make/target/product/security/platform"))
        for banned in ("shared", "media", "networkstack", "nfc", "bluetooth", "sdk_sandbox"):
            bad += case("certificate=%s fires too" % banned, 1,
                        "must not carry a platform-class identity",
                        _mk_tf(j("%s-tf.zip" % banned), good,
                               cert="build/make/target/product/security/%s" % banned))
        bad += case("PRESIGNED fires", 1, "apkcerts says PRESIGNED",
                    _mk_tf(j("presigned-tf.zip"), good, cert="PRESIGNED"))
        bad += case("no OrganicMaps apkcerts entry fires", 1, "no OrganicMaps.apk entry",
                    _mk_tf(j("noentry-tf.zip"), good, cert=None))
        bad += case("no apkcerts.txt at all fires", 1, "is this a target_files zip",
                    _mk_tf(j("nocerts-tf.zip"), good, cert=False))

        bad += case("a target_files with no OrganicMaps.apk cannot tell", 2, "CANNOT TELL",
                    _mk_tf(j("noapk-tf.zip"), None))
        bad += case("a path that does not exist cannot tell", 2, "CANNOT TELL",
                    j("does-not-exist.zip"))

        bad += case("no v1 block is reported UNCHECKED, not matched", 0,
                    "signer fingerprint UNCHECKED", good, "--cert-sha256", "00" * 32)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("")
    if bad:
        print("SELFTEST FAILED -- %d case(s). The checks in this file are NOT proved." % bad)
        return 1
    print("SELFTEST PASS -- every check fired on a fixture carrying its defect, and the clean")
    print("                APK and target_files passed.")
    return 0


def main():
    args = [a for a in sys.argv[1:]]
    if "--selftest" in args:
        if len(args) != 1:
            sys.exit("--selftest takes no other arguments")
        return selftest()
    expect = None
    if "--cert-sha256" in args:
        i = args.index("--cert-sha256")
        try:
            expect = args.pop(i + 1).replace(":", "").lower()
        except IndexError:
            sys.exit("--cert-sha256 needs a value")
        args.pop(i)
    if len(args) != 1:
        sys.exit(__doc__.strip().splitlines()[2].strip())

    target = args[0]
    if not os.path.isfile(target):
        print("CANNOT TELL: no such file: %s" % target)
        return 2

    tmp = None
    try:
        if target.endswith(".apk"):
            apk = target
            notes.append("APK given directly; apkcerts not checked")
        else:
            with zipfile.ZipFile(target) as z:
                if APK_IN_TF not in z.namelist():
                    print("CANNOT TELL: %s holds no %s" % (target, APK_IN_TF))
                    return 2
                tmp = tempfile.mkdtemp(prefix="omchk.")
                apk = os.path.join(tmp, "OrganicMaps.apk")
                with z.open(APK_IN_TF) as src, open(apk, "wb") as dst:
                    shutil.copyfileobj(src, dst)
            check_apkcerts(target)

        check_jni(apk)

        fp = v1_signer_sha256(apk)
        if fp is None:
            notes.append("signer fingerprint UNCHECKED (no v1 block, or no openssl)")
        elif expect:
            if fp == expect:
                notes.append("v1 signer matches --cert-sha256")
            else:
                findings.append("v1 signer is %s, expected %s" % (fp, expect))
        else:
            notes.append("v1 signer sha256 = %s (compare against keys/<device>/releasekey)" % fp)
    finally:
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)

    for n in notes:
        print("  ok   %s" % n)
    for f in findings:
        print("  FAIL %s" % f)
    print("ORGANICMAPS %s" % ("PASS" if not findings else "FAIL"))
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
