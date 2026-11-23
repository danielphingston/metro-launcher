#!/usr/bin/env bash
# Runs the instrumented tests (app/src/androidTest) on a running emulator or device: installs the
# debug app and its test APK, runs them, then uninstalls both so a later install (the release
# build in the smoke test) starts clean. Fails when any test fails or the run doesn't finish.
# Usage: tools/device-tests.sh <directory holding app-debug.apk and app-debug-androidTest.apk>
set -u
APP=$(find "$1" -name app-debug.apk | head -n 1)
TESTS=$(find "$1" -name app-debug-androidTest.apk | head -n 1)
if [ -z "$APP" ] || [ -z "$TESTS" ]; then
  echo "::error::No app-debug.apk and app-debug-androidTest.apk under $1"
  exit 1
fi
PKG=io.github.danielphingston.metro
OUT=device-tests.txt
LOG=device-tests-logcat.txt
adb install -r -t "$APP" || exit 1
adb install -r -t "$TESTS" || exit 1
adb logcat -c
adb shell am instrument -w -r "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT" 2>&1
STATUS=$?
# The tests' own log, kept before the smoke test clears it: crashes and system errors during the tests show
# here. (The picture code skips a picture it can't use without logging; the tests' assertions check those paths.)
adb logcat -d > "$LOG"
if adb logcat -d -b crash | grep -q "Process: $PKG"; then
  echo "::error::The app crashed during the instrumented tests:"
  adb logcat -d -b crash
  STATUS=1
fi
adb uninstall "$PKG.test" > /dev/null 2>&1
adb uninstall "$PKG" > /dev/null 2>&1
grep -E "^INSTRUMENTATION_STATUS: (class|test)=|^INSTRUMENTATION_STATUS_CODE: -2" "$OUT" | tail -n 40
# "OK (n tests)" only when every test passed; a crash or a failure prints something else.
if [ $STATUS -ne 0 ] || ! grep -q "^OK (" "$OUT"; then
  echo "::error::Instrumented tests failed:"
  cat "$OUT"
  exit 1
fi
grep "^OK (" "$OUT"
