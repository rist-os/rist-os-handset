#!/usr/bin/env python3
"""Turn a signed A/B OTA zip into the manifest the update server serves."""
import argparse
import hashlib
import json
import os
import sys
import time
import zipfile

META = "META-INF/com/android/metadata"
PROPS = "payload_properties.txt"


def parse_metadata(text):
    """key=value lines, with trailing padding spaces on some values."""
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def payload_range(meta):
    """Comma-separated `name:offset:length` from ota-streaming-property-files."""
    field = meta.get("ota-streaming-property-files") or meta.get("ota-property-files")
    if not field:
        raise SystemExit("no ota-streaming-property-files in metadata; is this an A/B package?")
    for entry in field.split(","):
        parts = entry.strip().split(":")
        if len(parts) == 3 and parts[0] == "payload.bin":
            return int(parts[1]), int(parts[2])
    raise SystemExit("payload.bin not listed in ota-streaming-property-files")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("zip_path")
    ap.add_argument("--url", required=True,
                    help="public URL the device will stream from")
    ap.add_argument("--channel", default="stable")
    # The device caps this at OtaSignature.MAX_LIFETIME_SECONDS (30 days).
    ap.add_argument("--expires-days", type=int, default=14,
                    help="how long the signed manifest stays valid (default 14)")
    args = ap.parse_args()

    if args.expires_days < 1 or args.expires_days > 30:
        raise SystemExit("--expires-days must be 1..30; the device refuses a longer lifetime "
                         "(OtaSignature.MAX_LIFETIME_SECONDS)")

    if not zipfile.is_zipfile(args.zip_path):
        raise SystemExit("%s is not a zip" % args.zip_path)

    with zipfile.ZipFile(args.zip_path) as z:
        names = set(z.namelist())
        for required in (META, PROPS, "payload.bin"):
            if required not in names:
                raise SystemExit("%s is missing %s -- not a signed A/B OTA package" % (args.zip_path, required))
        meta = parse_metadata(z.read(META).decode("utf-8", "replace"))
        props = parse_metadata(z.read(PROPS).decode("utf-8", "replace"))

    if meta.get("ota-type") != "AB":
        raise SystemExit("ota-type=%r; only seamless A/B packages are supported" % meta.get("ota-type"))

    offset, length = payload_range(meta)

    incremental_from = meta.get("pre-build-incremental") or meta.get("pre-build")

    size = os.path.getsize(args.zip_path)
    sha = hashlib.sha256()
    with open(args.zip_path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            sha.update(chunk)

    manifest = {
        "channel": args.channel,
        "expires": int(time.time()) + args.expires_days * 86400,
        "device": meta.get("pre-device"),
        "build": meta.get("post-build-incremental"),
        "fingerprint": meta.get("post-build"),
        "security_patch": meta.get("post-security-patch-level"),
        "sdk_level": meta.get("post-sdk-level"),
        # Seconds since epoch; the device refuses anything not strictly newer than its own.
        "timestamp": int(meta["post-timestamp"]) if "post-timestamp" in meta else None,
        "incremental_from": incremental_from,
        "url": args.url,
        "filename": os.path.basename(args.zip_path),
        "zip_size": size,
        "zip_sha256": sha.hexdigest(),
        # What UpdateEngine.applyPayload() takes verbatim.
        "payload_offset": offset,
        "payload_size": length,
        "payload_properties": [
            "FILE_HASH=%s" % props.get("FILE_HASH", ""),
            "FILE_SIZE=%s" % props.get("FILE_SIZE", ""),
            "METADATA_HASH=%s" % props.get("METADATA_HASH", ""),
            "METADATA_SIZE=%s" % props.get("METADATA_SIZE", ""),
        ],
    }

    # FILE_SIZE in payload_properties must equal the length in the streaming field.
    if props.get("FILE_SIZE") and int(props["FILE_SIZE"]) != length:
        raise SystemExit("payload.bin length disagrees: metadata says %d, payload_properties says %s"
                         % (length, props["FILE_SIZE"]))

    missing = [k for k in ("device", "build", "fingerprint", "timestamp") if not manifest.get(k)]
    if missing:
        raise SystemExit("package metadata is missing: %s" % ", ".join(missing))

    json.dump(manifest, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
