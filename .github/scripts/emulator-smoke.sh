#!/usr/bin/env bash
# Runs the emulator smoke tests (FBBD-161) on the emulator that is already
# running, in one Gradle invocation, and fails when any process of the app
# (UI, recorder, camera, coach) crashed or stopped responding meanwhile, as
# logcat reports it: a crash in a background process does not fail a test by
# itself. The log is kept in build/emulator/logcat.txt for the workflow to
# upload when something fails.
set -uo pipefail
app=com.flappedear.betterdriver
out=build/emulator
mkdir -p "$out"
log="$out/logcat.txt"

adb logcat -c || true
adb logcat -v threadtime > "$log" 2>&1 &
logcat=$!

./gradlew :app:connectedDebugAndroidTest --stacktrace
status=$?

# Lets logcat write the last lines before it stops.
sleep 3
kill "$logcat" 2>/dev/null || true

# Java crashes: "FATAL EXCEPTION" then "Process: <name>, PID: <pid>".
crashes=$(grep -A2 'FATAL EXCEPTION' "$log" | grep -c "Process: $app" || true)
# Native crashes: the tombstone header names the process ">>> <name> <<<".
native=$(grep -c ">>> $app" "$log" || true)
anrs=$(grep -c "ANR in $app" "$log" || true)

if [ "$crashes" -gt 0 ] || [ "$native" -gt 0 ] || [ "$anrs" -gt 0 ]; then
  echo "::error::The app crashed ($crashes, native $native) or stopped responding ($anrs) during the smoke tests."
  grep -A40 'FATAL EXCEPTION' "$log" | grep -A40 "Process: $app" || true
  grep -B2 -A30 ">>> $app" "$log" || true
  grep -A20 "ANR in $app" "$log" || true
  status=1
fi
if [ "$status" -ne 0 ]; then
  echo "::error::Emulator smoke tests failed. Logcat and test reports are in the run's artifacts."
fi
exit "$status"
