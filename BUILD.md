# Building RistOS

You do not need to build anything to use RistOS: install the signed image from the
[release page](https://github.com/rist-os/rist-os-handset/releases) as described in
[image/INSTALL.md](image/INSTALL.md), and updates arrive over the air.

## The app only

The assistant app builds on its own with a JDK 21 and the Android SDK with platform 35,
build-tools 35.0.0, NDK 26.3.11579264 and CMake 3.22.1 installed from the SDK Manager.
A minimal `local.properties` at the repo root:

```
sdk.dir=/path/to/android-sdk
rist.releaseVariant=public       # required for release builds once a backend URL is set
```

```
./gradlew testDebugUnitTest      # the unit tests
./gradlew :app:assembleDebug     # debug APK, no signing key needed
./push.sh                        # build, sign with your platform key, install over adb
```

`push.sh` needs `RIST_KEYS` pointing at a directory holding `platform.pk8` and
`platform.x509.pem`. The app is a system app, so an APK it signs installs only on a device
whose image was built with that same platform key. It also bumps `versionCode` in
`app/build.gradle.kts` and `app/src/main/AndroidManifest.xml`; revert that bump before
submitting a change.

## The whole image

RistOS is GrapheneOS for the Pixel 10a (`stallion`) with the Rist layer added. To build it:

1. Build GrapheneOS for `stallion` following [grapheneos.org/build](https://grapheneos.org/build).
2. Add the Rist layer: append `image/stallion-wiring.txt` to the device makefile, and apply the
   framework patches with `image/scripts/apply_patches.sh <tree>` (`--check` verifies them).
3. Sign with your own keys. The image is only as trustworthy as the keys that signed it, so a
   build you make is yours, not ours: see [TRADEMARKS.md](TRADEMARKS.md) for what it may be called.

A rebuild is not byte-for-byte identical to the published image; the release signature proves
where a download came from, not that it matches this tree.
