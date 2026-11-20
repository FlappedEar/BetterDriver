#!/usr/bin/env bash
# Removes any BetterDriver left on the emulator before the scripts install the APKs this run built.
# The cached AVD's data image can hold an app signed with another runner's debug key (the emulator
# boots from scratch when its snapshot no longer fits its version, keeping the data image); an
# update over it fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE and every emulator job goes red.
set -uo pipefail
for pkg in com.flappedear.betterdriver.test com.flappedear.betterdriver; do
  path=$(adb shell pm path "$pkg" 2>/dev/null | tr -d '\r')
  [ -n "$path" ] || continue
  out=$(adb uninstall "$pkg" 2>&1 | tr -d '\r')
  if [ "$out" != "Success" ]; then
    out="$out; $(adb shell pm uninstall --user 0 "$pkg" 2>&1 | tr -d '\r')"
  fi
  echo "Removed $pkg left on the emulator ($path): $out"
done
exit 0
