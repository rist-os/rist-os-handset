#!/usr/bin/env python3
"""Sign an OTA manifest with the RistOS release key: tools/ota_sign.py <manifest.json>"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time

DEFAULT_SECRET_KEY = os.path.expanduser("~/.config/rist/keys/ristos-release.key")
DEFAULT_PUBLIC_KEY = os.path.expanduser("~/.config/rist/keys/ristos-release.pub")

# Must agree with OtaSignature.MAX_LIFETIME_SECONDS (30 days).
MAX_EXPIRES_DAYS = 30


def die(msg):
    raise SystemExit("ota_sign: %s" % msg)


def refresh_expiry(path, days):
    # indent=2, sort_keys=True must match tools/ota_manifest.py byte for byte: the signature is over the octets.
    with open(path) as fh:
        manifest = json.load(fh)
    manifest["expires"] = int(time.time()) + days * 86400
    with open(path, "w") as fh:
        json.dump(manifest, fh, indent=2, sort_keys=True)
        fh.write("\n")
    return manifest


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest", help="the manifest JSON written by tools/ota_manifest.py")
    ap.add_argument("-s", "--secret-key", default=DEFAULT_SECRET_KEY,
                    help="PATH to the minisign secret key (never read by this script)")
    ap.add_argument("-p", "--public-key", default=DEFAULT_PUBLIC_KEY,
                    help="PATH to the matching public key, used only to verify our own output")
    ap.add_argument("--refresh", type=int, metavar="DAYS",
                    help="rewrite `expires` to DAYS from now before signing (the heartbeat)")
    args = ap.parse_args()

    if not shutil.which("minisign"):
        die("minisign is not on PATH. brew install minisign")
    if not os.path.exists(args.manifest):
        die("no such manifest: %s" % args.manifest)
    if not os.path.exists(args.secret_key):
        die("no secret key at %s.\n"
            "        Generate one ONCE, on the release machine, and keep the passphrase out of every\n"
            "        tool including this one:\n"
            "            mkdir -p ~/.config/rist/keys\n"
            "            minisign -G -p ~/.config/rist/keys/ristos-release.pub \\\n"
            "                        -s ~/.config/rist/keys/ristos-release.key\n"
            "            chmod 600 ~/.config/rist/keys/ristos-release.key\n"
            "        Then paste line 2 of the .pub into OtaSignature.PUBLIC_KEY and rebuild the\n"
            "        image. Until an image carries it, no handset can verify anything."
            % args.secret_key)

    if args.refresh is not None:
        if not 1 <= args.refresh <= MAX_EXPIRES_DAYS:
            die("--refresh must be 1..%d days (OtaSignature.MAX_LIFETIME_SECONDS)" % MAX_EXPIRES_DAYS)
        refresh_expiry(args.manifest, args.refresh)

    with open(args.manifest) as fh:
        manifest = json.load(fh)

    expires = manifest.get("expires")
    if not isinstance(expires, int) or expires <= 0:
        die("manifest has no usable `expires`; regenerate with tools/ota_manifest.py, "
            "or re-sign with --refresh DAYS")
    remaining = expires - int(time.time())
    if remaining <= 0:
        die("`expires` is already in the past. Re-sign with --refresh DAYS.")
    if remaining > MAX_EXPIRES_DAYS * 86400:
        die("`expires` is %d days out; the device caps it at %d "
            "(OtaSignature.MAX_LIFETIME_SECONDS) and would refuse this manifest"
            % (remaining // 86400, MAX_EXPIRES_DAYS))

    sig_path = args.manifest + ".minisig"

    trusted = "ristos-ota channel=%s device=%s build=%s expires=%d" % (
        manifest.get("channel", "?"), manifest.get("device", "?"),
        manifest.get("build", "?"), expires)

    print("signing %s" % args.manifest, file=sys.stderr)
    print("  channel=%s build=%s  expires in %d days"
          % (manifest.get("channel"), manifest.get("build"), remaining // 86400), file=sys.stderr)
    print("  minisign will prompt for the passphrase if the key has one; it is typed to minisign, "
          "never to this script.", file=sys.stderr)

    # No capture or stdin redirection: minisign must own the terminal to prompt for the passphrase.
    rc = subprocess.call([
        "minisign", "-S",
        "-s", args.secret_key,
        "-m", args.manifest,
        "-x", sig_path,
        "-c", "RistOS OTA manifest",
        "-t", trusted,
    ])
    if rc != 0:
        die("minisign -S failed (exit %d); no signature written" % rc)

    if os.path.exists(args.public_key):
        rc = subprocess.call(["minisign", "-V", "-p", args.public_key, "-m", args.manifest])
        if rc != 0:
            os.unlink(sig_path)
            die("the signature does not verify against %s. Signature deleted; nothing published."
                % args.public_key)
    else:
        print("WARNING: no public key at %s, so the signature was NOT verified. "
              "It may have been made with the wrong key." % args.public_key, file=sys.stderr)

    print("wrote %s" % sig_path, file=sys.stderr)
    print("  publish it to  <base>/v1/ota/%s/%s.minisig"
          % (manifest.get("device", "<device>"), manifest.get("channel", "<channel>")),
          file=sys.stderr)
    print("  AFTER the package, and beside the manifest. tools/ota_publish.sh writes the manifest "
          "last on purpose; the signature must land with it, not after it -- a manifest served "
          "without its sidecar is refused by every device.", file=sys.stderr)


if __name__ == "__main__":
    main()
