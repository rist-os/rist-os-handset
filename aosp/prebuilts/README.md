# Vendored transport prebuilts (okhttp3 + okio)

RistAssistant's transport layer (`Uploader.kt` streaming chunked POST,
`PushService.kt` persistent WebSocket) is written against **okhttp3 4.x** and
**okio 3.x**. AOSP's platform tree does **not** provide these:

| App import | Platform Soong module | What it actually is | Satisfies app? |
|---|---|---|---|
| `okhttp3.*` (incl. `okhttp3.WebSocket`) | `okhttp` / `okhttp-norepackage` | okhttp **2.x** (`com.squareup.okhttp` / `com.android.okhttp`), no `okhttp3` pkg, no WebSocket | ❌ |
| `okio.Pipe`, `okio.BufferedSink`, `okio.ByteString` | `okio-lib` | okio **1.x** | ❌ |

The exact Maven Central artifacts the Gradle build resolves are therefore vendored here and
wired as Soong `java_import` prebuilts (see `Android.bp`). This keeps the app
**source-identical** across the Gradle (Phase-0 APK) and Soong (OS) builds.

## Artifacts in this tree

| File | Maven coordinate | Licence | SHA-256 |
|---|---|---|---|
| `okhttp-4.12.0.jar` | `com.squareup.okhttp3:okhttp:4.12.0` | Apache-2.0 | `b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0` |
| `okio-jvm-3.6.0.jar` | `com.squareup.okio:okio-jvm:3.6.0` | Apache-2.0 | `67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412` |
| `localbroadcastmanager/localbroadcastmanager-1.1.0.aar` | `androidx.localbroadcastmanager:localbroadcastmanager:1.1.0` | Apache-2.0 | `a22b94a77789f3b34becd24082231300524011ccc83f026142c49ad60131379b` |
| `media3/media3-common-1.4.1.aar` | `androidx.media3:media3-common:1.4.1` | Apache-2.0 | `973e5e7b0ecf8e6c8cd825cab35145b84c56020af7c3ec52815e595ef71baa16` |
| `media3/media3-container-1.4.1.aar` | `androidx.media3:media3-container:1.4.1` | Apache-2.0 | `65b6d22c96dfb5ce76754b08f5b004ee0dbdbe60793c5eab9f95c1f54f95f4b0` |
| `media3/media3-database-1.4.1.aar` | `androidx.media3:media3-database:1.4.1` | Apache-2.0 | `2170ae6448cd499fc570dafa81e5687ad813995b7c2b7ff6801acaaa9a88bb4a` |
| `media3/media3-datasource-1.4.1.aar` | `androidx.media3:media3-datasource:1.4.1` | Apache-2.0 | `1c0bb41ee88a6bfaf24720bbfe2d29a033abad21c30e0921efbdce6c1fa28842` |
| `media3/media3-decoder-1.4.1.aar` | `androidx.media3:media3-decoder:1.4.1` | Apache-2.0 | `f7d97c5a39dca3c3c21bc8a70145d931d8633ed0dae89198c370e7b7e3c3f183` |
| `media3/media3-exoplayer-1.4.1.aar` | `androidx.media3:media3-exoplayer:1.4.1` | Apache-2.0 | `66cd0f99209191660823d32c8fc329e383a8a7ff33e0d676632387361abcba70` |
| `media3/media3-extractor-1.4.1.aar` | `androidx.media3:media3-extractor:1.4.1` | Apache-2.0 | `1f51e4633e1e6137f0a4b80552afc399e269208b700352f96543d4513be6bf28` |
| `media3/media3-session-1.4.1.aar` | `androidx.media3:media3-session:1.4.1` | Apache-2.0 | `b619b200405e237136e7daaaec79eb694348f0562390c8b8b545a4294e3641d4` |
| `security/security-crypto-1.1.0-alpha06.aar` | `androidx.security:security-crypto:1.1.0-alpha06` | Apache-2.0 | `976111170b3f13d2f0f9457d9f9f579347143cb65c054265906d33c7618a4c2a` |
| `security/tink-android-1.8.0.jar` | `com.google.crypto.tink:tink-android:1.8.0` | Apache-2.0 | `5efe8f1cb6e75814ae06793d5ff8d9b01e6f88ef97ecc062d14ebc27027dbf78` |

Paths are relative to this directory. The `androidx.*` artefacts come from
`dl.google.com/dl/android/maven2`; the rest from Maven Central. Each
subdirectory's `Android.bp` declares its own Soong modules.

`okhttp:4.12.0` declares `com.squareup.okio:okio:3.6.0` as its transitive
dependency; on the JVM that resolves to the `okio-jvm` artifact (the `okio`
artifact is a Gradle-metadata alias with no classes of its own). Versions match
`app/build.gradle.kts` exactly (`com.squareup.okhttp3:okhttp:4.12.0`).

Both are plain JVM JARs (no Android resources / no `AndroidManifest.xml`), which
is why `Android.bp` uses `java_import`, not `android_library_import`.

## Soong module names (referenced from `app/Android.bp` `static_libs`)

- `rist-okhttp3-prebuilt` — okhttp; `static_libs`-re-exports okio, so this single
  entry is all the app needs.
- `rist-okio3-prebuilt` — okio (pulled in transitively; listed for completeness).

kotlin-stdlib (required by both, since okhttp/okio 4.x/3.x are Kotlin) is
auto-linked by Soong for any Kotlin `android_app`, so it is not declared here.

## How to obtain / refresh the artifacts

They come straight from Maven Central (or your Gradle cache after a normal build):

```
# from Maven Central:
BASE=https://repo1.maven.org/maven2
curl -LO $BASE/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar
curl -LO $BASE/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar

# or copy from the Gradle cache after ./gradlew :app:assembleDebug:
find ~/.gradle/caches -name 'okhttp-4.12.0.jar' -o -name 'okio-jvm-3.6.0.jar'
```

Verify against the SHA-256 table above before committing. If you bump the okhttp
version in `app/build.gradle.kts`, refresh both JARs here (okhttp and its matching
okio), update the filenames in `Android.bp` + the table above, and re-run the
Gradle build so both builds stay on the same versions.
