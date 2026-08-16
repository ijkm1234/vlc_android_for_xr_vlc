#!/usr/bin/env bash
set -euo pipefail

vlc_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
local_properties="$vlc_root/local.properties"
aar_path="$vlc_root/application/vlc-android/build/outputs/aar/vlc-android-debug.aar"

read_property() {
    sed -n "s/^$1=//p" "$local_properties" | head -n 1
}

if [[ ! -f "$local_properties" ]]; then
    echo "Missing $local_properties. Configure the Android SDK and NDK first." >&2
    exit 1
fi

if [[ -z "${ANDROID_SDK:-}" ]]; then
    export ANDROID_SDK="$(read_property 'sdk\.dir')"
fi
if [[ -z "${ANDROID_NDK:-}" ]]; then
    export ANDROID_NDK="$(read_property 'android\.ndkPath')"
fi
if [[ ! -d "$ANDROID_SDK" || ! -d "$ANDROID_NDK" ]]; then
    echo "local.properties must contain existing sdk.dir and android.ndkPath values." >&2
    exit 1
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "A usable Java 17 home is required for the Gradle build." >&2
    exit 1
fi

export VLC_LIBJNI_PATH="${VLC_LIBJNI_PATH:-$vlc_root/libvlcjni}"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.java.home=$JAVA_HOME"

cd "$vlc_root"

# Missing pinned sources are downloaded once by compile.sh. Existing Git
# checkouts, contrib tarballs, and native build outputs are reused.
./buildsystem/compile.sh -a arm64-v8a -l

GRADLE_ABI=arm64-v8a ./gradlew \
    -PxrFatAar=true \
    :application:vlc-android:clean \
    :application:vlc-android:assembleDebug

if [[ ! -f "$aar_path" ]]; then
    echo "AAR output was not generated at $aar_path" >&2
    exit 1
fi

aar_entries="$(unzip -Z1 "$aar_path")"
for native_lib in libvlc.so libvlcjni.so libc++_shared.so; do
    grep -Fqx "jni/arm64-v8a/$native_lib" <<<"$aar_entries" || {
        echo "Missing $native_lib in $aar_path" >&2
        exit 1
    }
done

echo "Built $aar_path"
