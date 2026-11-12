# Metro Launcher

Android launcher (Kotlin, Jetpack Compose), package `io.github.danielphingston.metro`.
Almost all state lives in one Preferences DataStore (`metroDataStore`, name "metro"): settings, the tiles
JSON, weather, search keywords. Search history and purchases are SharedPreferences and are not backed up.

## Backups must keep importing

Settings export/import (`data/Backup.kt`, JSON, `Backup.FORMAT`) is a promise to users: a file
exported by any past release restores on every future release.

- Every build, PR and release must pass `BackupTest` (`everyShippedFormatStillImports` is the gate).
- Every `app/src/test/resources/backup/format-N.json` fixture is permanent. Never edit or delete one.
- When anything stored changes (new, renamed or removed DataStore key, tiles JSON shape, meaning of a
  value), either keep old backups readable as they are, or bump `Backup.FORMAT`, add one branch to
  `Backup.migrate`, and add a new fixture `format-N.json` exported from the new version, with
  assertions for it in `BackupTest`.
- Keys in `Backup.EXCLUDED_KEYS` (forecast cache, last assistant question) are never exported.
- Release builds must keep R8 working. v0.7.16 crashed on start: AGP 9 strict R8 stripped WorkManager's
  `WorkDatabase_Impl` constructor. See `app/proguard-rules.pro` and keep its rules when touching shrinking.

## Release builds must not crash

Unit tests run unminified code, so they never see what R8 removed. Every CI build therefore also runs:

- `tools/check-release-apk.py <release apk> <mapping.txt>`: fails when a class created by reflection
  (manifest components, startup initializers, Room databases, WorkManager workers) lost its constructor.
- `tools/smoke-test.sh <release apk>` in the `smoke` job: installs the release APK on an emulator,
  drives it with random input and fails on any crash or ANR. Releases publish only after it passes.

Both must pass for every new build. When you add a library that creates classes by reflection, add
a keep rule and, if the check can't see it yet, extend `check-release-apk.py`.

## Checks

```
export ANDROID_HOME=/opt/android-sdk
./gradlew --init-script tools/google-mirror.init.gradle testDebugUnitTest lintDebug
```

- Download dependencies through Google's mirror of Maven Central, which doesn't rate-limit the way
  Maven Central does (HTTP 429 before anything compiles): add
  `--init-script tools/google-mirror.init.gradle` to every local `./gradlew` command. The build
  files and CI are unchanged by it.
- No SDK at `/opt/android-sdk`? Install the command-line tools there, then
  `sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"`.
  The "Full Network" cloud environment's setup script does this at session start and sets `ANDROID_HOME`.
- Several Gradle builds at once (worktrees, subagents) run out of memory: take turns with
  `flock /tmp/metro-gradle.lock ./gradlew ...`.
- Roborazzi screenshot tests compare against goldens in `app/src/test/screenshots`. Do not re-record
  them unless the visual change is intended; a changed settings page fails its golden on purpose.
- Do not run `assembleDebug` just to check; unit tests and lint are the gate.

## Trying the app on an emulator (cloud sessions)

Cloud containers have no KVM, so the emulator runs in software: it works, but slowly. What makes it usable:

- Install on demand (about 1.5 GB): `sdkmanager "emulator" "system-images;android-33;default;x86_64"`, then
  `avdmanager create avd -n dog -k "system-images;android-33;default;x86_64" -d pixel_5`.
- Start: `emulator -avd dog -no-window -accel off -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot -memory 4096 -cores 3`.
  Boot takes 10 to 15 minutes; wait for `adb shell getprop sys.boot_completed` to print 1, then another minute.
- Stop the Gradle and Kotlin daemons first (`./gradlew --stop`). With them holding 6 GB the emulator's
  system_server starves and shows "System UI isn't responding" forever; a reboot does not cure it while they run.
  Never start a second emulator on the same AVD.
- After `adb install`, run `adb shell cmd package compile -m speed -f io.github.danielphingston.metro`.
  The unoptimised debug build otherwise takes minutes to draw its first frame.
- `adb shell cmd package set-home-activity` fails there and `cmd role add-role-holder` can time out; start
  `.ui.MainActivity` with `am start` instead.
- Drive it with touches, not keys. `input swipe` needs 1200 ms or more or it lands as a tap; use the on-screen
  nav bar (back about 260,2270, home 540,2270 on 1080x2340) rather than `input keyevent`. Key events and
  `input text` put the window in keyboard mode, where focus moves to the app list's search box and the pager
  follows it (a real bug for hardware keyboards, but not what a touch user sees).
- Each step takes 10 to 60 seconds: batch several actions per command, and read `uiautomator dump` text for
  layout instead of a screenshot when that is enough.
- No network reaches the emulator (weather and city search cannot be tried) and there is no photo to pick.
- A fresh look at first run needs `adb shell pm clear io.github.danielphingston.metro`.

## Release flow

1. Open a PR; CI must pass.
2. Merge with a merge commit (not squash).
3. To release, label the PR `release` (next patch) or `release:minor` (next minor) before merging.
   The merge's own build is tested, smoke-tested and published as is; nothing is rebuilt.
4. Forgot the label? Run `build.yml` by hand on master: it publishes the passed build of master's
   latest commit (version already baked in). Unlabelled merges never publish.
