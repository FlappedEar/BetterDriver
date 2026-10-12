#!/usr/bin/env bash
# The UI-process kill test (FBBD-72), on the emulator emulator-smoke.sh runs, after the smoke tests
# and with the APKs they built. UiKillTest runs in two phases with the UI process SIGKILLed between
# them, as Android does to free memory:
#   1. startRecording: START with Demo GNSS; the session records in the recorder's process.
#   2. The app is opened on the track screen, then its UI process is killed. The recorder process
#      must live on, and the session keeps recording with no UI for a while.
#   3. reconnectAndStop: the app opens on the running session, stops it, and checks that every fix
#      is there and the VBO is the one of an uninterrupted recording.
# --no-restart keeps `am instrument` from force-stopping the app (and so the recorder) when a phase
# starts or ends.
set -uo pipefail
app=com.flappedear.betterdriver
runner=$app.test/androidx.test.runner.AndroidJUnitRunner
test=$app.uikill.UiKillTest
noUiSeconds=10

fail() {
  echo "::error::UI-process kill test: $*"
  exit 1
}

phase() {
  echo "== UiKillTest#$1"
  out=$(adb shell am instrument -w --no-restart -e class "$test#$1" "$runner" 2>&1)
  echo "$out"
  echo "$out" | grep -q "^OK (1 test)" || fail "phase $1 failed"
}

pids() {
  adb shell pidof "$1" 2>/dev/null | tr -d '\r'
}

# Kills a process as root: the emulator's google_apis image has su; adb root is the fallback.
kill9() {
  adb shell su 0 kill -9 "$1" 2>/dev/null && return 0
  adb root >/dev/null && adb wait-for-device && adb shell kill -9 "$1"
}

adb install -r -g app/build/outputs/apk/debug/app-debug.apk || fail "app not installed"
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || fail "test APK not installed"

phase startRecording

# The UI on the track screen, as a driver would have it, then killed.
adb shell am start -W -n "$app/.MainActivity" >/dev/null
sleep 3
ui=$(pids "$app")
recorder=$(pids "$app:recorder")
[ -n "$ui" ] || fail "no UI process"
[ -n "$recorder" ] || fail "no recorder process"
echo "Killing the UI process $ui; the recorder is $recorder."
kill9 "$ui" || fail "could not kill the UI process"
# Home, so Android does not bring the UI straight back; anything it restarted is killed too, so the
# next phase starts the UI afresh.
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

phase reconnectAndStop
echo "UI-process kill test passed."
