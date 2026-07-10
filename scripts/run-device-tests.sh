#!/usr/bin/env bash
# Run JVM + connected instrumented tests on a USB device.
# Prerequisite: device unlocked; app may need Default SMS role for MMS insert tests.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "== adb devices =="
adb devices -l

echo "== unit tests =="
./gradlew test --stacktrace

echo "== install debug + connectedAndroidTest =="
./gradlew :app:installDebug :app:connectedDebugAndroidTest --stacktrace

echo "== launch app =="
adb shell am start -n com.ubermicrostudios.textimagecleaner/.MainActivity || true

echo "Done."
