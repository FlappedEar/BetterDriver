#!/usr/bin/env bash
# Waits for the APK build that ci.yml starts in the background before the emulator boots
# (build/apk-build.log, build/apk-build.status), so Gradle and the emulator start-up overlap.
# Returns at once when no background build was started (APK_BUILD_IN_BACKGROUND unset), and fails
# with the end of the build's log when the build failed or did not finish in 30 min.
set -uo pipefail
[ -n "${APK_BUILD_IN_BACKGROUND:-}" ] || exit 0
started=$SECONDS
while [ ! -f build/apk-build.status ]; do
  if [ $((SECONDS - started)) -gt 1800 ]; then
    echo "::error title=APK build::The background APK build did not finish in 30 min."
    tail -60 build/apk-build.log 2>/dev/null
    exit 1
  fi
  sleep 5
done
echo "Background APK build finished $((SECONDS - started)) s after the emulator was ready."
code=$(cat build/apk-build.status)
if [ "$code" != 0 ]; then
  echo "::error title=APK build::The background APK build failed (exit $code)."
  tail -120 build/apk-build.log
  exit 1
fi
