#!/usr/bin/env bash
# Removes any BetterDriver left on the emulator before the scripts install the APKs this run built.
# The cached AVD's data image can hold an app signed with another runner's debug key (the emulator
# boots from scratch when its snapshot no longer fits its version, keeping the data image); an
# update over it fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE and every emulator job goes red.
set -uo pipefail
for pkg in com.flappedear.betterdriver.test com.flappedear.betterdriver; do
  if adb shell pm list packages | tr -d '\r' | grep -qx "package:$pkg"; then
    adb uninstall "$pkg" >/dev/null 2>&1 && echo "Removed $pkg left on the emulator."
  fi
done
exit 0
