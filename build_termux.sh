#!/data/data/com.termux/files/usr/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# build_termux.sh  –  Build BeirusSampler on a Pixel 9 Pro XL via Termux
#
# Prerequisites (run once):
#   pkg update && pkg install openjdk-21 gradle aapt2 cmake android-tools
#
# Usage:
#   chmod +x build_termux.sh
#   ./build_termux.sh           # debug APK
#   ./build_termux.sh release   # release APK (needs keystore)
#   ./build_termux.sh install   # debug APK + adb install
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

# ── Sanity checks ─────────────────────────────────────────────────────────
command -v aapt2  >/dev/null 2>&1 || { echo "ERROR: aapt2 not found. Run: pkg install aapt2"; exit 1; }
command -v cmake  >/dev/null 2>&1 || { echo "ERROR: cmake not found. Run: pkg install cmake"; exit 1; }
command -v java   >/dev/null 2>&1 || { echo "ERROR: java not found. Run: pkg install openjdk-21"; exit 1; }

# ── Environment setup ─────────────────────────────────────────────────────
# Tell AGP where to find the Termux-native binaries.
# These env-vars are read by app/build.gradle.kts and gradle.properties.
export TERMUX_AAPT2="$(command -v aapt2)"
export TERMUX_CMAKE="$(command -v cmake)"

# ANDROID_HOME must point to a real Android SDK.
# Default Termux SDK location (install with: pkg install android-sdk).
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
if [ ! -d "$ANDROID_HOME" ]; then
    echo "ERROR: Android SDK not found at $ANDROID_HOME"
    echo "Set ANDROID_HOME or install with: pkg install android-sdk"
    exit 1
fi

# ── Choose build variant ──────────────────────────────────────────────────
MODE="${1:-debug}"
case "$MODE" in
    debug)   TASK="assembleDebug"   ;;
    release) TASK="assembleRelease" ;;
    install) TASK="assembleDebug"   ; DO_INSTALL=1 ;;
    *)       echo "Usage: $0 [debug|release|install]"; exit 1 ;;
esac

# ── Run Gradle ────────────────────────────────────────────────────────────
# --no-daemon  : avoids the long-lived daemon process that Android's
#                low-memory killer frequently kills inside Termux.
# --stacktrace : print full stack on error so you can diagnose problems.
echo "▶ Building ($TASK) …"
./gradlew "$TASK" \
    --no-daemon \
    --stacktrace \
    -Pandroid.aapt2.path="$TERMUX_AAPT2" \
    -Pandroid.cmake.dir="$(dirname "$TERMUX_CMAKE")"

# ── Output ────────────────────────────────────────────────────────────────
APK_DIR="app/build/outputs/apk/${MODE}"
APK="$(find "$APK_DIR" -name '*.apk' 2>/dev/null | head -1)"
if [ -z "$APK" ]; then
    echo "ERROR: APK not found in $APK_DIR"
    exit 1
fi
echo "✓ APK ready: $APK"

# ── Optional install via ADB (USB debugging must be on) ───────────────────
if [ "${DO_INSTALL:-0}" = "1" ]; then
    command -v adb >/dev/null 2>&1 || { echo "WARNING: adb not found, skipping install"; exit 0; }
    echo "▶ Installing on device …"
    adb install -r "$APK"
    echo "✓ Installed."
fi
