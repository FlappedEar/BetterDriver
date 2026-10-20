#!/usr/bin/env bash
# Fault injection during a recording (FBBD-34, FBBD-69, FBBD-43, FBBD-64, FBBD-63), on the emulator
# emulator-smoke.sh runs, after the smoke tests and with the APKs they built. FaultTest runs in phases
# attached to the running app (--no-restart), with the faults Android only takes from outside injected
# between them:
#   1. seed, startRecording: Demo GNSS records; the track screen keeps the screen on and hides the
#      system bars (FBBD-63).
#   2. The shortest screen timeout for a while: the screen must stay on (FBBD-63).
#   3. Memory pressure on every process of the app: the recorder must not be killed (FBBD-34, 69).
#   4. The screen off for a while (2 min here; the phone check is 20 min): the recorder keeps
#      recording, its notification stays up (FBBD-34, 69).
#   5. survivedPressureAndScreenOff: same session, notification, not one fix lost.
#   6. Cold start with the recording running, reported only (FBBD-41's 1.5 s is for a Pixel 6).
#   7. gnssLossAndReturn: Demo GNSS off and on 10 times (FBBD-43, 64).
#   8. recorderKill: the recorder process is killed; <= 2 s lost, recovered, valid VBO (FBBD-34, 69).
#   9. stopTenTimes: 10 short sessions in a row, each finished with a valid VBO (FBBD-34).
set -uo pipefail
app=com.flappedear.betterdriver
runner=$app.test/androidx.test.runner.AndroidJUnitRunner
test=$app.faults.FaultTest
keepOnSeconds=20
screenOffSeconds=${SCREEN_OFF_SECONDS:-120}
checkEverySeconds=30

# The screen as emulator-smoke.sh leaves it for the tests: on, unlocked, no timeout.
restore_screen() {
  adb shell svc power stayon true || true
  adb shell settings put system screen_off_timeout 2147483647 || true
  adb shell input keyevent KEYCODE_WAKEUP || true
  adb shell wm dismiss-keyguard || true
}

fail() {
  echo "::error title=Fault injection test::$*"
  # Why the app is gone or not in front, in the job log itself: crashes and ANRs since boot.
  adb logcat -b crash -d 2>/dev/null | tail -n 80 || true
  adb logcat -d 2>/dev/null | grep -E "AndroidRuntime|ANR in $app|Process $app.* has died|Force finishing" | tail -n 40 || true
  adb logcat -d 2>/dev/null | grep -E "ActivityTaskManager|WindowManager|ActivityManager" | grep -iE "$app|START u0|moveTask|finish|pause|stop" | tail -n 60 || true
  adb shell dumpsys activity activities 2>/dev/null | grep -E "Task\{|ActivityRecord|mResumedActivity|topResumedActivity" | head -n 30 || true
  restore_screen
  exit 1
}

# phase NAME [--no-restart]
phase() {
  echo "== FaultTest#$1"
  out=$(adb shell am instrument -w ${2:-} -e class "$test#$1" "$runner" 2>&1)
  echo "$out"
  echo "$out" | grep -q "^OK (1 test)" || fail "phase $1 failed: $(echo "$out" | grep -m3 -E 'Error|Exception|expected' | tr '\n' ' ')"
}

pids() {
  adb shell pidof "$1" 2>/dev/null | tr -d '\r'
}

open_app() {
  adb shell am start -W -n "$app/.MainActivity" | tr -d '\r' | grep -E "Status|LaunchState|Activity:|Error|Warning" | tr '\n' ' ' || fail "the app did not open"
  echo
  sleep 3
}

# Kills a process as root: the emulator's google_apis image has su; adb root is the fallback.
kill9() {
  adb shell su 0 kill -9 "$1" 2>/dev/null && return 0
  adb root >/dev/null && adb wait-for-device && adb shell kill -9 "$1"
}

wakefulness() {
  adb shell dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*' | tr -d '\r'
}

recording_notification() {
  adb shell dumpsys notification --noredact | grep "pkg=$app " | grep -q "channel=recording"
}

# ui-kill.sh installed both APKs; installed again only when this runs on its own.
if ! adb shell pm list packages | tr -d '\r' | grep -qx "package:$app.test"; then
  adb install -r -t -g app/build/outputs/apk/debug/app-debug.apk || fail "app not installed"
  adb install -r -t -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || fail "test APK not installed"
fi

phase seed
open_app
phase startRecording --no-restart

# The test runner finishes the activities a phase started, so the app is opened again; with the
# recording running it opens on the track screen.
# A slow emulator can leave the launcher focused after the start (on main too, 576dd59), so this
# waits up to 20 s and starts the app once more halfway.
open_app
for i in $(seq 1 20); do
  focus=$(adb shell dumpsys window | grep -m1 mCurrentFocus | tr -d '\r')
  echo "$focus" | grep -q "$app" && break
  [ "$i" = 10 ] && open_app
  sleep 1
done
echo "$focus" | grep -q "$app" || fail "the app is not in front for FBBD-63: $focus"

# FBBD-63: with the shortest screen timeout, the screen stays on and bright while recording.
adb shell settings put system screen_off_timeout 10000
adb shell svc power stayon false
echo "A 10 s screen timeout for $keepOnSeconds s."
sleep "$keepOnSeconds"
wake=$(wakefulness)
policy=$(adb shell dumpsys display | grep -m1 -o 'policy=[A-Z]*' | tr -d '\r')
restore_screen
[ "$wake" = "mWakefulness=Awake" ] || fail "FBBD-63: the screen went off during a recording ($wake) with a 10 s timeout"
case "$policy" in
  policy=BRIGHT) ;;
  policy=DIM) fail "FBBD-63: the screen dimmed during a recording with a 10 s timeout" ;;
  *) echo "::warning title=Fault injection test::FBBD-63: the display's power policy was not readable ($policy)" ;;
esac

# FBBD-34, FBBD-69: memory pressure on every process of the app; the recorder must not be killed.
recorder=$(pids "$app:recorder")
[ -n "$recorder" ] || fail "no recorder process"
for p in "$app" "$app:recorder" "$app:coach" "$app:camera"; do
  pid=$(pids "$p")
  [ -n "$pid" ] || continue
  for level in RUNNING_CRITICAL COMPLETE; do
    # COMPLETE is refused for a process in the foreground; the reply is logged, not judged.
    echo "send-trim-memory $p ($pid) $level: $(adb shell am send-trim-memory "$pid" "$level" 2>&1 | tr -d '\r')"
  done
done
sleep 5
[ "$(pids "$app:recorder")" = "$recorder" ] || fail "the recorder process did not survive memory pressure"

# FBBD-34, FBBD-69: the screen off; the recorder keeps recording and its notification stays up.
adb shell svc power stayon false
adb shell input keyevent KEYCODE_SLEEP
sleep 3
wake=$(wakefulness)
[ "$wake" != "mWakefulness=Awake" ] || fail "the screen did not go off ($wake)"
echo "Screen off ($wake) for $screenOffSeconds s."
off=0
while [ "$off" -lt "$screenOffSeconds" ]; do
  sleep "$checkEverySeconds"
  off=$((off + checkEverySeconds))
  [ "$(pids "$app:recorder")" = "$recorder" ] || fail "the recorder process ended with the screen off, after $off s"
  recording_notification || fail "no recording notification with the screen off, after $off s"
done
restore_screen
sleep 2
phase survivedPressureAndScreenOff --no-restart

# FBBD-41: a cold start with the recording running, reported only: the 1.5 s target is for a Pixel 6.
ui=$(pids "$app")
[ -n "$ui" ] && kill9 "$ui"
adb shell input keyevent KEYCODE_HOME
sleep 2
again=$(pids "$app")
[ -n "$again" ] && kill9 "$again"
sleep 1
launch=$(adb shell am start -W -n "$app/.MainActivity" | tr -d '\r')
echo "$launch"
total=$(echo "$launch" | grep -m1 -o 'TotalTime: [0-9]*' | grep -o '[0-9]*')
echo "::notice title=Cold start (FBBD-41)::${total:-?} ms from launch to the first frame (am start -W TotalTime), app process killed, recording running. The 1.5 s target is for a Pixel 6; not judged on the emulator."
sleep 3

phase gnssLossAndReturn --no-restart
phase recorderKill --no-restart
gap=$(adb logcat -d -s FaultTest:I | grep "recorder killed:" | tail -1 | sed 's/.*recorder killed: //' | tr -d '\r')
echo "::notice title=Recorder kill (FBBD-34, FBBD-69)::${gap:-not logged}"
phase stopTenTimes --no-restart
restore_screen
echo "Fault injection test passed."
