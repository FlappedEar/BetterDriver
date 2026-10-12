#!/usr/bin/env bash
# Runs the emulator smoke tests (FBBD-161) on the emulator that is already
# running, in one Gradle invocation, then the UI-process kill test (FBBD-72,
# ui-kill.sh), fault injection during a recording (FBBD-34, 69, 43, 64, 63,
# faults.sh), and fails when any process of the app
# (UI, recorder, camera, coach) crashed or stopped responding meanwhile, as
# logcat reports it: a crash in a background process does not fail a test by
# itself. The log is kept in build/emulator/logcat.txt for the workflow to
# upload when something fails.
set -uo pipefail
app=com.flappedear.betterdriver
out=build/emulator
mkdir -p "$out"
log="$out/logcat.txt"

# The screen stays on and unlocked for the whole run, and the system's "isn't responding" dialogs
# stay hidden: either takes the window focus from the app, and the tests that use Espresso then
# fail. Crashes and ANRs are still found, in logcat below.
adb shell svc power stayon true || true
adb shell settings put system screen_off_timeout 2147483647 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null || true

adb logcat -c || true
adb logcat -v threadtime > "$log" 2>&1 &
logcat=$!

./gradlew :app:connectedDebugAndroidTest --stacktrace
status=$?

# The UI-process kill test (FBBD-72), with the APKs Gradle built; logcat still runs.
bash .github/scripts/ui-kill.sh || status=1
# Fault injection during a recording: memory pressure, screen off, GNSS loss, recorder kill, 10 stops.
bash .github/scripts/faults.sh || status=1

# Lets logcat write the last lines before it stops.
sleep 3
kill "$logcat" 2>/dev/null || true

# Crashes (Java and native) and ANRs of every process, as annotations: errors for the app's
# processes (UI, recorder, camera, coach), which fail the job, and warnings for the others.
if ! python3 .github/scripts/logcat-problems.py "$log"; then
  echo "::group::App crashes and ANRs in logcat"
  grep -A40 'FATAL EXCEPTION' "$log" | grep -A40 "Process: $app" || true
  grep -B2 -A30 ">>> $app" "$log" || true
  grep -A20 "ANR in $app" "$log" || true
  echo "::endgroup::"
  status=1
fi
if [ "$status" -ne 0 ]; then
  # Each failed test as an annotation, with its whole stack trace in the log (Gradle prints only
  # the first line of a failure).
  find app/build/outputs/androidTest-results -name 'TEST-*.xml' -exec python3 .github/scripts/test-failures.py {} \;
  # What had the window focus and whether the screen was on, as tests that lose focus fail.
  focus=$(adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | tr -s ' ' | head -2 | tr '\n' ';')
  power=$(adb shell dumpsys power 2>/dev/null | grep -E 'mWakefulness=|Display Power' | tr -s ' ' | head -2 | tr '\n' ';')
  echo "::warning title=Device at the end::$focus $power"
  echo "::error::Emulator smoke tests failed. Logcat and test reports are in the run's artifacts."
fi
exit "$status"
