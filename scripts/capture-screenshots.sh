#!/usr/bin/env bash
# Capture app screenshots using DEMO MODE only (synthetic media — never real MMS).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

PKG=com.ubermicrostudios.textimagecleaner
ACTIVITY=$PKG/.MainActivity
OUT="$ROOT/docs/screenshots"
mkdir -p "$OUT"

echo "Building & installing debug APK..."
./gradlew :app:installDebug -q

shot() {
  local name="$1"
  local screen="${2:-cleaner}"
  echo "→ $name (demo_screen=$screen)"
  adb shell am force-stop "$PKG"
  sleep 0.8
  # -S forces stop before start; --activity-clear-task resets extras
  adb shell am start -S -n "$ACTIVITY" \
    --activity-clear-task \
    --ez demo_mode true \
    --es demo_screen "$screen" >/dev/null
  # Allow UI + demo media generation (bitmaps on disk)
  sleep 3.5
  adb shell screencap -p /sdcard/tic_shot.png
  adb pull /sdcard/tic_shot.png "$OUT/$name.png" >/dev/null
  adb shell rm /sdcard/tic_shot.png 2>/dev/null || true
  echo "  saved $OUT/$name.png ($(wc -c < "$OUT/$name.png") bytes)"
}

shot "01-cleaner-grid" "cleaner"
shot "02-selection" "selection"
shot "03-settings" "settings"
shot "04-trash" "trash"

# Date range: open cleaner then tap calendar is coordinate-fragile; skip unless we add intent later
echo
echo "Screenshots written to $OUT (synthetic demo media only):"
ls -lh "$OUT"
echo "Done."
