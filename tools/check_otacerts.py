#!/usr/bin/env python3
"""Assert that the reserve OTA certificate is in the image's otacerts.zip, beside the primary.

    tools/check_otacerts.py <target_files.zip | otacerts.zip | *-ota_update-*.zip> [options]
"""

import base64
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

# sha256 of the DER, i.e. the openssl -sha256 fingerprint.
RESERVE_SHA256 = "07c61378348dfd3f3b038b7a4eb79c75a1cc3719de8d87b35233d23831e36046"

PRIMARY_SHA256 = None

PEM_BEGIN = b"-----BEGIN CERTIFICATE-----"
PEM_END = b"-----END CERTIFICATE-----"

findings = []
notes = []


def norm(fp):
    return fp.replace(":", "").replace(" ", "").lower()


def der_from_member(name, blob):
    out = []
    if PEM_BEGIN in blob:
        rest = blob
        while PEM_BEGIN in rest:
            _, rest = rest.split(PEM_BEGIN, 1)
            if PEM_END not in rest:
                raise ValueError("%s: BEGIN CERTIFICATE with no END" % name)
            body, rest = rest.split(PEM_END, 1)
            try:
                out.append(base64.b64decode(b"".join(body.split())))
            except Exception as e:
                raise ValueError("%s: PEM body is not base64 (%s)" % (name, e))
    elif blob[:1] == b"\x30":
        out.append(blob)          # bare DER
    else:
        raise ValueError("%s: neither PEM nor DER -- first bytes %r" % (name, blob[:8]))

    for der in out:
        if der[:1] != b"\x30" or len(der) < 8 or der[1] < 0x81:
            raise ValueError("%s: not a DER SEQUENCE with a long-form length" % name)
        nlen = der[1] & 0x7F
        if nlen > 4:
            raise ValueError("%s: implausible DER length-of-length %d" % (name, nlen))
        blen = int.from_bytes(der[2:2 + nlen], "big")
        if 2 + nlen + blen != len(der):
            raise ValueError("%s: DER length %d does not match %d bytes present"
                             % (name, 2 + nlen + blen, len(der)))
    if not out:
        raise ValueError("%s: no certificate in it" % name)
    return out


def describe(der):
    if not shutil.which("openssl"):
        return ""
    p = subprocess.run(["openssl", "x509", "-inform", "DER", "-noout",
                        "-subject", "-enddate", "-fingerprint", "-sha256"],
                       input=der, capture_output=True)
    if p.returncode:
        return ""
    fields = {}
    for line in p.stdout.decode("utf-8", "replace").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            fields[k.strip()] = v.strip()
    ossl_fp = norm(fields.get("sha256 Fingerprint", ""))
    if ossl_fp and ossl_fp != norm(hashlib.sha256(der).hexdigest()):
        findings.append("openssl and this script disagree on a fingerprint (%s vs %s) -- "
                        "do not trust either until that is understood"
                        % (ossl_fp, hashlib.sha256(der).hexdigest()))
    bits = [v for k, v in fields.items() if k in ("subject", "notAfter")]
    return "  [%s]" % ", ".join(bits) if bits else ""


def check_store(label, blob, revocation):
    try:
        z = zipfile.ZipFile(io.BytesIO(blob))
        names = [n for n in z.namelist() if not n.endswith("/")]
    except Exception as e:
        findings.append("%s is not a readable zip (%s)" % (label, e))
        return 0
    if not names:
        findings.append("%s is an EMPTY zip -- the image would trust nothing and could never "
                        "be updated again" % label)
        return 0

    seen = {}
    for n in names:
        try:
            ders = der_from_member(n, z.read(n))
        except Exception as e:
            findings.append("%s: %s" % (label, e))
            continue
        for der in ders:
            seen[hashlib.sha256(der).hexdigest()] = (n, der)

    if not seen:
        findings.append("%s: %d member(s) and NOT ONE parsed as a certificate"
                        % (label, len(names)))
        return 0

    print("  %s -- %d certificate(s):" % (label, len(seen)))
    for fp, (n, der) in sorted(seen.items(), key=lambda kv: kv[1][0]):
        if fp == RESERVE_SHA256:
            role = "RESERVE"
        elif PRIMARY_SHA256 and fp == PRIMARY_SHA256:
            role = "primary"
        else:
            role = "other  "
        print("      %s  %s  %s%s" % (role, fp, n, describe(der)))

    if RESERVE_SHA256 not in seen:
        findings.append("%s does NOT contain the reserve certificate (%s). If this is the "
                        "post-sign target_files, the PRODUCT_EXTRA_OTA_KEYS line did not survive "
                        "ReplaceOtaKeys." % (label, RESERVE_SHA256))
    others = [fp for fp in seen if fp != RESERVE_SHA256]
    if PRIMARY_SHA256 and PRIMARY_SHA256 not in seen and not revocation:
        findings.append("%s does not contain the pinned primary certificate (%s)"
                        % (label, PRIMARY_SHA256))
    elif not others and not revocation:
        findings.append("%s contains ONLY the reserve certificate. The primary is gone: no "
                        "package signed with keys/<device>/releasekey would be accepted. Pass "
                        "--revocation if that is deliberate." % label)
    if revocation and others:
        findings.append("%s still contains %d non-reserve certificate(s); a revocation build must "
                        "carry the reserve ALONE or it revokes nothing" % (label, len(others)))
    return len(seen)


def selftest():
    def store(members):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            for n, d in members:
                z.writestr(n, d)
        return buf.getvalue()

    good = None
    if os.path.isfile(os.path.expanduser("~/.config/rist/keys/otareserve.x509.pem")):
        with open(os.path.expanduser("~/.config/rist/keys/otareserve.x509.pem"), "rb") as f:
            good = f.read()

    cases = [
        ("empty otacerts.zip", store([])),
        ("one non-certificate member", store([("keys/readme.txt", b"hello")])),
        ("truncated PEM", store([("keys/x.x509.pem", PEM_BEGIN + b"\nAAAA\n")])),
        ("0x30 blob that is not a cert", store([("keys/x.der", b"\x30\x82\xff\xff" + b"A" * 8)])),
        ("not a zip at all", b"this is not a zip"),
    ]
    if good is not None:
        cases.append(("reserve alone, without --revocation", store([("keys/otareserve.x509.pem", good)])))

    bad = 0
    for name, blob in cases:
        del findings[:]
        check_store("selftest/" + name, blob, revocation=False)
        verdict = "caught" if findings else "PASSED -- THE GATE IS BROKEN"
        print("  selftest: %-38s %s" % (name, verdict))
        if not findings:
            bad += 1
    del findings[:]

    if good is not None:
        check_store("selftest/reserve alone, --revocation",
                    store([("keys/otareserve.x509.pem", good)]), revocation=True)
        ok = not findings
        print("  selftest: %-38s %s" % ("reserve alone, --revocation",
                                        "accepted" if ok else "REJECTED -- false negative"))
        if not ok:
            bad += 1
        del findings[:]
    else:
        print("  selftest: reserve cert not on this machine; positive case not exercised")

    print("SELFTEST %s" % ("PASS" if bad == 0 else "FAIL (%d)" % bad))
    return 0 if bad == 0 else 1


def main():
    args = sys.argv[1:]
    revocation = "--revocation" in args
    args = [a for a in args if a != "--revocation"]
    if "--selftest" in args:
        return selftest()
    if len(args) != 1:
        sys.stderr.write(__doc__.strip().splitlines()[2].strip() + "\n")
        return 2

    target = args[0]
    if not os.path.isfile(target):
        print("CANNOT TELL: no such file: %s" % target)
        return 2
    try:
        outer = zipfile.ZipFile(target)
        names = outer.namelist()
    except Exception as e:
        print("CANNOT TELL: %s is not a readable zip (%s)" % (target, e))
        return 2

    stores = [n for n in names if n == "otacerts.zip" or n.endswith("/otacerts.zip")]

    if not stores:
        certish = [n for n in names
                   if not n.endswith("/") and n.lower().endswith((".pem", ".der", ".crt", ".x509"))]
        if certish and len(certish) == len([n for n in names if not n.endswith("/")]):
            with open(target, "rb") as f:
                n_certs = check_store(os.path.basename(target), f.read(), revocation)
            return report(1 if n_certs == 0 else 0)
        if any(n.endswith(".img") for n in names):
            print("CANNOT TELL: %s looks like a factory/img zip. otacerts.zip lives inside "
                  "system.img and this script does not open filesystem images. Run it against "
                  "the target_files.zip this was built from." % target)
            return 2
        if "META-INF/com/android/otacert" in names:
            der = outer.read("META-INF/com/android/otacert")
            try:
                fp = hashlib.sha256(der_from_member("otacert", der)[0]).hexdigest()
            except Exception as e:
                print("CANNOT TELL: %s carries an unparsable META-INF/com/android/otacert (%s)"
                      % (target, e))
                return 2
            who = "the RESERVE key" if fp == RESERVE_SHA256 else "a key that is NOT the reserve"
            print("CANNOT TELL: %s is an OTA package, not an image. It carries no otacerts.zip -- "
                  "otacerts.zip is what the RUNNING image trusts, and an OTA package cannot tell "
                  "you that." % target)
            print("  for information: this package was signed by %s (%s)" % (who, fp))
            print("  to check what the image trusts, run this against its target_files.zip")
            return 2
        print("CANNOT TELL: %s contains no otacerts.zip (%d entries examined)"
              % (target, len(names)))
        return 2

    total = 0
    for n in sorted(stores):
        total += check_store(n, outer.read(n), revocation)

    rec = [n for n in stores if n.startswith(("BOOT/", "RECOVERY/", "VENDOR_BOOT/"))]
    if rec:
        notes.append("%d recovery-side otacerts.zip present (%s) -- these are fed by "
                     "PRODUCT_EXTRA_RECOVERY_KEYS, not PRODUCT_EXTRA_OTA_KEYS"
                     % (len(rec), ", ".join(rec)))
    else:
        notes.append("no BOOT/ RECOVERY/ VENDOR_BOOT/ otacerts.zip in this target_files, so "
                     "PRODUCT_EXTRA_RECOVERY_KEYS had nothing to apply to here")

    if total == 0:
        findings.append("%d otacerts.zip found and not one certificate parsed out of any of them"
                        % len(stores))
    return report(0)


def report(extra):
    for n in notes:
        print("  note %s" % n)
    for f in findings:
        print("  FAIL %s" % f)
    ok = not findings and not extra
    print("OTACERTS %s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
