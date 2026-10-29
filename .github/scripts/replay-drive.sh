#!/usr/bin/env bash
# Arek's Demo track drive, done by CI on the emulator that is already running (ReplayDriveTest): the
# Silesia Ring laps built into the app (his 27 Sep 2026 sessions) replayed through the whole app,
# coach at Full in $LANGUAGE (Polish unless set), for $REPLAY_MINUTES minutes in real time (10: Arek,
# 2026-10-20). The coach knows the track from an earlier outing and learns 1 lap, so laps 3 and 4 are
# coached (ReplayDriveTest). Then the continuous log is checked as his shared logs are, and the report
# goes to the run's summary and build/replay-drive/.
set -uo pipefail
app=com.flappedear.betterdriver
runner=$app.test/androidx.test.runner.AndroidJUnitRunner
test=$app.replay.ReplayDriveTest
minutes=${REPLAY_MINUTES:-10}
# When the one-minute trace starts, in s after the drive phase starts (FBBD-266).
TRACE_AFTER_S=${TRACE_AFTER_S:-360}
language=${LANGUAGE:-Polish}
out=build/replay-drive
mkdir -p "$out"
log="$out/logcat.txt"

fail() {
  echo "::error title=Replay drive::$*"
  finish 1
}

# The log and report are kept whether the drive passed or not.
# Each adb call is bounded and reads no input: the first run hung here for 55 min after a failed phase.
finish() {
  timeout 60 adb exec-out run-as "$app" cat files/replay-drive.md < /dev/null > "$out/replay-drive.md" 2>/dev/null || true
  timeout 60 adb exec-out run-as "$app" cat files/logs/betterdriver.log < /dev/null > "$out/betterdriver.log" 2>/dev/null || true
  # The report in the job's log too, where it can be read without the artifact.
  [ -s "$out/replay-drive.md" ] && cat "$out/replay-drive.md"
  # Each cue's trigger, sound start and end as the coach logged them (FBBD-253), and the voice's own
  # lines, so an overlap can be traced without the artifact. No positions are in these lines.
  if [ -s "$out/betterdriver.log" ]; then
    echo "== Coach cues and their sound"
    grep -E "coach_(cue|voice|voice_end|problem|quiet)|[^a-zA-Z]Voice[^a-zA-Z]|CoachService" "$out/betterdriver.log" | tail -400
  fi
  if [ -s "$out/replay-drive.md" ] && [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    cat "$out/replay-drive.md" >> "$GITHUB_STEP_SUMMARY"
  fi
  if [ -s "$out/track-trace.md" ] && [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    cat "$out/track-trace.md" >> "$GITHUB_STEP_SUMMARY"
  fi
  sleep 2
  kill "$logcat" 2>/dev/null || true
  wait "$logcat" 2>/dev/null || true
  exit "$1"
}

# phase NAME [--no-restart] [SECONDS]
phase() {
  echo "== ReplayDriveTest#$1 at $(date -u +%H:%M:%S)"
  # A phase that hangs fails the drive instead of the job running out of time.
  result=$(timeout "${3:-600}" adb shell am instrument -w ${2:-} -e language "$language" -e minutes "$minutes" \
    -e class "$test#$1" "$runner" < /dev/null 2>&1)
  echo "$result"
  if ! echo "$result" | grep -q "^OK (1 test)"; then
    # What the test runner and the app's processes logged last, for a phase that hung or failed.
    grep -E "TestRunner|AndroidRuntime|ReplayDriveTest" "$log" | tail -40
  fi
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

bash .github/scripts/wait-apk-build.sh || fail "the APKs did not build"
adb install -r -t -g app/build/outputs/apk/debug/app-debug.apk || fail "app not installed"
adb install -r -t -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || fail "test APK not installed"

# Which speech engines the emulator has: the coach needs one with a voice of $language.
echo "Speech engines: $(adb shell cmd package query-services -a android.intent.action.TTS_SERVICE 2>/dev/null | grep -o 'packageName=[^ ]*' | sort -u | tr '\n' ' ')"

phase seed
adb shell am start -W -n "$app/.MainActivity" >/dev/null || fail "the app did not open"
sleep 3
# The drive, started, driven and stopped in one test: the app stays in front on the track screen, as
# on Arek's phone. The test runner waits for the app's main thread to go idle before a test, which it
# never does while the track screen draws every frame: a stop phase of its own waited 8 min there.
echo "Replaying for $minutes min."
# One minute of the drive traced (FBBD-266): where the track screen's frame time goes, from the UI
# thread's state and slices and who held the CPUs (track-trace.py). Starts midway, with the coach and
# its voice running.
adb push .github/scripts/track-trace.cfg /data/misc/perfetto-configs/track.cfg >/dev/null || true
( sleep "$TRACE_AFTER_S" && adb shell perfetto --background --txt -c /data/misc/perfetto-configs/track.cfg \
  -o /data/misc/perfetto-traces/track.pftrace < /dev/null >/dev/null 2>&1 ) &
tracer=$!
phase drive --no-restart $(( minutes * 60 + 300 ))
kill "$tracer" 2>/dev/null || true
timeout 60 adb pull /data/misc/perfetto-traces/track.pftrace "$out/track.pftrace" < /dev/null >/dev/null 2>&1 \
  && python3 -m pip install -q perfetto >/dev/null 2>&1 \
  && timeout 300 python3 .github/scripts/track-trace.py "$out/track.pftrace" "$out/track-trace.md" \
  || echo "No track screen trace (FBBD-266)."
# Home, as a phone put away, stops any drawing before the check.
adb shell input keyevent KEYCODE_HOME || true
sleep 5
phase check --no-restart
finish 0
