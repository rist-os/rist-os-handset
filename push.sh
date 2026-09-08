#!/bin/bash
# Usage: ./push.sh [debug]   (build + platform-sign + adb install; needs RIST_KEYS)
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ]; then
    for _jh in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
               /usr/lib/jvm/java-21-openjdk-amd64 \
               /usr/lib/jvm/java-21-openjdk; do
        [ -x "$_jh/bin/javac" ] && { export JAVA_HOME="$_jh"; break; }
    done
fi
[ -n "${JAVA_HOME:-}" ] || { echo "push.sh: set JAVA_HOME to a JDK 21" >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[ -d "$SDK" ] || { echo "push.sh: no Android SDK at '$SDK' — set ANDROID_HOME" >&2; exit 1; }
APKSIGNER="$SDK/build-tools/35.0.0/apksigner"
ADB="$SDK/platform-tools/adb"
: "${RIST_KEYS:?set RIST_KEYS to your signing key directory}"
KEYS="$RIST_KEYS"

VARIANT="${1:-release}"

VC=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')
NEW=$((VC+1))
# `sed -i` differs between BSD and GNU, so edit via a temp file.
_sed_inplace() {  # _sed_inplace <expr> <file>
    local _t; _t="$(mktemp)" || return 1
    sed "$1" "$2" > "$_t" && mv "$_t" "$2" || { rm -f "$_t"; return 1; }
}
_sed_inplace "s/versionCode = $VC/versionCode = $NEW/" app/build.gradle.kts
# Soong reads versionCode from AndroidManifest.xml, Gradle from build.gradle.kts; bump both.
_sed_inplace "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$NEW\"/" app/src/main/AndroidManifest.xml
echo ">> versionCode $VC -> $NEW (gradle + manifest)"

if [ "$VARIANT" = "release" ]; then ./gradlew :app:assembleRelease --no-daemon -q; SRC=app/build/outputs/apk/release/app-release-unsigned.apk
else ./gradlew :app:assembleDebug --no-daemon -q; SRC=app/build/outputs/apk/debug/app-debug.apk; fi

mkdir -p build-out; OUT="build-out/RistAssistant-$NEW.apk"; rm -f "$OUT" "$OUT.idsig"
# --key-pass only for an encrypted key: apksigner rejects it for a plaintext key.
# ${KEYPASS[@]+"${KEYPASS[@]}"} is required: bash 3.2 aborts on an empty array under set -u.
KEYPASS=()
if [ -n "${RIST_KEY_PASS_FILE:-}" ]; then
  [ -r "$RIST_KEY_PASS_FILE" ] || { echo "RIST_KEY_PASS_FILE is set but not readable: $RIST_KEY_PASS_FILE" >&2; exit 1; }
  KEYPASS=(--key-pass "file:$RIST_KEY_PASS_FILE")
fi
"$APKSIGNER" sign --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" \
  ${KEYPASS[@]+"${KEYPASS[@]}"} --v4-signing-enabled true --out "$OUT" "$SRC"

"$ADB" install-multiple --no-incremental "$OUT" "$OUT.idsig"
echo ">> pushed vc$NEW; installed: $("$ADB" shell dumpsys package watch.rist.assistant | grep -oE 'versionCode=[0-9]+' | head -1)"
