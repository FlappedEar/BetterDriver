#!/usr/bin/env bash
# Arek's Demo track drive, done by CI on the emulator that is already running (ReplayDriveTest): the
# Silesia Ring laps built into the app (his 27 Sep 2026 sessions) replayed through the whole app,
# coach at Full in $LANGUAGE (Polish unless set), for $REPLAY_MINUTES minutes in real time, enough for
# the coach's 3 learning laps and coached laps after them. Then the continuous log is checked as his
# shared logs are, and the report goes to the run's summary and build/replay-drive/.
set -uo pipefail
app=com.flappedear.betterdriver
runner=$app.test/androidx.test.runner.AndroidJUnitRunner
test=$app.replay.ReplayDriveTest
minutes=${REPLAY_MINUTES:-22}
language=${LANGUAGE:-Polish}
checkEverySeconds=60
out=build/replay-drive
mkdir -p "$out"
log="$out/logcat.txt"

fail() {
  echo "::error title=Replay drive::$*"
  finish 1
}

# The log and report are kept whether the drive passed or not.
finish() {
  adb shell run-as "$app" cat files/replay-drive.md > "$out/replay-drive.md" 2>/dev/null || true
  adb shell run-as "$app" sh -c 'cat files/logs/*.log' > "$out/betterdriver.log" 2>/dev/null || true
  [ -s "$out/replay-drive.md" ] && [ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$out/replay-drive.md" >> "$GITHUB_STEP_SUMMARY"
  sleep 2
  kill "$logcat" 2>/dev/null || true
  exit "$1"
}

# phase NAME [--no-restart]
phase() {
  echo "== ReplayDriveTest#$1"
  # A phase that hangs fails the drive instead of the job running out of time.
  result=$(timeout 600 adb shell am instrument -w ${2:-} -e language "$language" -e class "$test#$1" "$runner" 2>&1)
  echo "$result"
  echo "$result" | grep -q "^OK (1 test)" || fail "phase $1 failed: $(echo "$result" | grep -m5 -E 'Error|Exception|expected|:' | tr '\n' ' ')"
}

adb shell svc power stayon true || true
adb shell settings put system screen_off_timeout 2147483647 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb logcat -c || true
adb logcat -v threadtime > "$log" 2>&1 &
logcat=$!

adb install -r -t -g app/build/outputs/apk/debug/app-debug.apk || fail "app not installed"
adb install -r -t -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || fail "test APK not installed"

# Which speech engines the emulator has: the coach needs one with a voice of $language.
echo "Speech engines: $(adb shell cmd package query-services -a android.intent.action.TTS_SERVICE 2>/dev/null | grep -o 'packageName=[^ ]*' | sort -u | tr '\n' ' ')"

phase seed
adb shell am start -W -n "$app/.MainActivity" >/dev/null || fail "the app did not open"
sleep 3
phase start --no-restart

# The drive: the app stays in front on the track screen, as on Arek's phone.
end=$(( $(date +%s) + minutes * 60 ))
while [ "$(date +%s)" -lt "$end" ]; do
  sleep "$checkEverySeconds"
  left=$(( (end - $(date +%s)) / 60 ))
  echo "Replaying; about $left min left."
done
phase stillRecording --no-restart
phase stop --no-restart
phase check --no-restart
finish 0
