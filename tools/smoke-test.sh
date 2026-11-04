#!/usr/bin/env bash
# Installs a release APK on a running emulator/device, opens the launcher, sends random input
# and fails if the app crashed. Usage: tools/smoke-test.sh app-release.apk
set -u
APK="$1"
PKG=io.github.danielphingston.metro
LOG=smoke-logcat.txt

adb install -r "$APK" || exit 1
adb logcat -c
adb shell am start -W -n "$PKG/.ui.MainActivity" || exit 1
sleep 10
adb shell input keyevent KEYCODE_HOME
sleep 2
# Swipe up (search) and down, then random taps and swipes inside the app only.
adb shell input swipe 540 1800 540 600 300
sleep 2
adb shell input keyevent KEYCODE_BACK
adb shell monkey -p "$PKG" -s 42 --throttle 150 --pct-syskeys 0 --pct-appswitch 0 \
  --ignore-security-exceptions -v 600 > /dev/null || true
sleep 5
adb shell am start -W -n "$PKG/.ui.settings.SettingsActivity"
sleep 5
adb shell monkey -p "$PKG" -s 7 --throttle 150 --pct-syskeys 0 --pct-appswitch 0 \
  --ignore-security-exceptions -v 300 > /dev/null || true
adb shell am start -W -n "$PKG/.ui.MainActivity"
sleep 5

adb logcat -d > "$LOG"
if adb logcat -d -b crash | grep -q "Process: $PKG"; then
  echo "::error::The release build crashed:"
  adb logcat -d -b crash
  exit 1
fi
if grep -q "ANR in $PKG" "$LOG"; then
  echo "::error::The release build stopped responding:"
  grep -A 20 "ANR in $PKG" "$LOG"
  exit 1
fi
echo "Smoke test passed: no crash or ANR."
