#!/usr/bin/env bash
# The UI-process kill test (FBBD-72), on the emulator emulator-smoke.sh runs, after the smoke tests
# and with the APKs they built. UiKillTest runs in phases with the UI process SIGKILLed between
# them, as Android does to free memory:
#   1. seed: Demo GNSS as the receiver, setup done.
#   2. startRecording: the app is opened, then START with Demo GNSS; the session records in the
#      recorder's process.
#   3. The app is opened on the track screen, then its UI process is killed. The recorder process
#      must live on, and the session keeps recording with no UI for a while.
#   4. reconnectAndStop: the app is opened again; it must show the running session. It is stopped,
#      and every fix must be there and the VBO the one of an uninterrupted recording.
# The phases after seed attach to the running app with --no-restart: a plain `am instrument`
# force-stops the app, and so the recorder, when it starts and ends.
set -uo pipefail
app=com.flappedear.betterdriver
runner=$app.test/androidx.test.runner.AndroidJUnitRunner
test=$app.uikill.UiKillTest
noUiSeconds=10

fail() {
  echo "::error title=UI-process kill test::$*"
  exit 1
}

# phase NAME [--no-restart]
phase() {
  echo "== UiKillTest#$1"
  out=$(adb shell am instrument -w ${2:-} -e class "$test#$1" "$runner" 2>&1)
  echo "$out"
  echo "$out" | grep -q "^OK (1 test)" || fail "phase $1 failed: $(echo "$out" | grep -m3 -E 'Error|Exception|expected' | tr '\n' ' ')"
}

pids() {
  adb shell pidof "$1" 2>/dev/null | tr -d '\r'
}

open_app() {
  adb shell am start -W -n "$app/.MainActivity" >/dev/null || fail "the app did not open"
  sleep 3
}

# Kills a process as root: the emulator's google_apis image has su; adb root is the fallback.
kill9() {
  adb shell su 0 kill -9 "$1" 2>/dev/null && return 0
  adb root >/dev/null && adb wait-for-device && adb shell kill -9 "$1"
}

adb install -r -g app/build/outputs/apk/debug/app-debug.apk || fail "app not installed"
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || fail "test APK not installed"

phase seed
open_app
phase startRecording --no-restart

# The UI on the track screen, as a driver would have it, then killed.
open_app
ui=$(pids "$app")
recorder=$(pids "$app:recorder")
[ -n "$ui" ] || fail "no UI process"
[ -n "$recorder" ] || fail "no recorder process"
echo "Killing the UI process $ui; the recorder is $recorder."
kill9 "$ui" || fail "could not kill the UI process"
# Home, so Android does not bring the UI straight back; anything it restarted is killed too, so the
# app is opened afresh below.
adb shell input keyevent KEYCODE_HOME
sleep 2
for _ in 1 2 3; do
  again=$(pids "$app")
  [ -z "$again" ] && break
  [ "$again" = "$ui" ] && fail "the UI process $ui survived the kill"
  kill9 "$again"
  sleep 1
done

echo "Recording with no UI for $noUiSeconds s."
sleep "$noUiSeconds"
[ "$(pids "$app:recorder")" = "$recorder" ] || fail "the recorder process did not survive the UI kill"

open_app
phase reconnectAndStop --no-restart
echo "UI-process kill test passed."
