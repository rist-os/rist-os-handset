#!/bin/bash
# Fetch the pinned Organic Maps release APK into the overlay prebuilt dir (never committed).
set -euo pipefail
VER="2026.07.15-11-android"
FILE="OrganicMaps-26071511-web-release.apk"
SHA256="d33d3dc19b05fc97c95075c94421c1635178c240f644f308404a98cf2037ecab"
DEST="$(cd "$(dirname "$0")/../.." && pwd)/aosp/prebuilts/apps/OrganicMaps"
mkdir -p "$DEST"
curl -fSL "https://github.com/organicmaps/organicmaps/releases/download/$VER/$FILE" -o "$DEST/OrganicMaps.apk"

if command -v sha256sum >/dev/null 2>&1; then
    GOT="$(sha256sum "$DEST/OrganicMaps.apk" | cut -d' ' -f1)"
elif command -v shasum >/dev/null 2>&1; then
    GOT="$(shasum -a 256 "$DEST/OrganicMaps.apk" | cut -d' ' -f1)"
else
    echo "ERROR: no sha256sum/shasum available -- cannot verify the APK against the pin" >&2
    exit 1
fi
if [ "$GOT" != "$SHA256" ]; then
    echo "FATAL: $FILE does not match the pinned hash." >&2
    echo "  expected $SHA256" >&2
    echo "  got      $GOT" >&2
    echo "Do not build this. Either the download is corrupt or the release moved under its tag." >&2
    rm -f "$DEST/OrganicMaps.apk"
    exit 1
fi
echo "fetched $FILE ($(du -h "$DEST/OrganicMaps.apk" | cut -f1)), sha256 verified -> $DEST/OrganicMaps.apk"
