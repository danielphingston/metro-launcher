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
adb install -r -t "$APP" || exit 1
adb install -r -t "$TESTS" || exit 1
adb shell am instrument -w -r "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT" 2>&1
STATUS=$?
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
