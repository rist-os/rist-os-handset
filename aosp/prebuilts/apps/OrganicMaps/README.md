# Organic Maps (bundled prebuilt)

Standalone offline OSM maps app, installed to `/product/app/OrganicMaps/` as a Soong
`android_app_import` (`PRODUCT_PACKAGES += OrganicMaps` in `rist.mk`) — **not** a
`PRODUCT_COPY_FILES` copy. It is a real build module: it appears in `META/apkcerts.txt`, it is
re-signed by the release step, and Soong rewrites the APK on the way in (see below).

- **APK is not committed** (~64 MB). Run `image/scripts/fetch-organicmaps.sh` to download the
  pinned official release into `OrganicMaps.apk` here before building.
- **Pinned version:** 2026.07.15-11-android — `OrganicMaps-26071511-web-release.apk`
  (package `app.organicmaps.web`, targetSdk 36), from github.com/organicmaps/organicmaps/releases.
- **License:** Apache-2.0 (distributed unmodified in content; the APK container is repacked and
  re-signed, see below). See the repo NOTICE.
- **Signed with the release key**, via `default_dev_cert: true`. Never `platform` — `Android.bp`
  explains why.
- **First run:** the user downloads their map region (e.g. "Seattle", ~108 MB) in-app; map data is
  too large and too region-specific to bake in. Location permission is auto-granted at boot by the
  Rist app (KioskManager), so it centres on the user without a prompt.

## Why the installed APK is 99.7 MB when the download is 64 MB

`android_app_import` runs `uncompressEmbeddedJniLibs` on every non-`preprocessed` import: the four
`lib/*/liborganicmaps.so` entries are re-stored uncompressed and page-aligned so the linker can mmap
them straight out of the APK. That is deliberate and it is the only reason the app's JNI resolves —
there is **no** `lib/` directory beside the APK, and `android_app_import` cannot create one. Do not
"fix" the missing `lib/` dir, and do not set `presigned`/`preprocessed` to preserve upstream's
signature: that skips the uncompress and the app then throws `UnsatisfiedLinkError` on launch.
Full reasoning in `Android.bp`.

Roughly 45 MB of the installed size is the `armeabi-v7a`, `x86` and `x86_64` copies of the native
library, none of which this device can execute. `strip_unused_jni_arch: true` would drop them.
It has not been through a build, so it is noted rather than applied.
