#!/usr/bin/env bash
# The runtime permissions flow (FBBD-35) on the emulator emulator-smoke.sh runs, with the APKs it
# built. Android ends the app when a permission is revoked, so PermissionTest runs in phases with the
# permissions changed by `pm` between them:
#   - each of location, nearby devices, camera and notifications revoked in turn: the app must say
#     what will not work without it (after Android's own dialog is answered "Don't allow" for the
#     required ones), with no crash;
#   - the camera revoked while Settings › App is open, then the app brought back: it must show it.
set -uo pipefail
app=com.flappedear.betterdriver
runner=$app.test/androidx.test.runner.AndroidJUnitRunner
test=$app.faults.PermissionTest
all="android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION
  android.permission.BLUETOOTH_SCAN android.permission.BLUETOOTH_CONNECT android.permission.CAMERA
  android.permission.RECORD_AUDIO android.permission.POST_NOTIFICATIONS"

fail() {
  echo "::error title=Permissions test::$*"
  grant_all
  exit 1
}

# phase NAME [am instrument options]
phase() {
  echo "== PermissionTest#$1"
  out=$(adb shell am instrument -w ${2:-} -e class "$test#$1" "$runner" 2>&1)
  echo "$out"
  echo "$out" | grep -q "^OK (1 test)" || fail "phase $1 ${2:-} failed: $(echo "$out" | grep -m3 -E 'Error|Exception|expected' | tr '\n' ' ')"
}

# The permissions of a PermissionGroup, by its name.
permissions_of() {
  case "$1" in
    Location) echo android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION ;;
    NearbyDevices) echo android.permission.BLUETOOTH_SCAN android.permission.BLUETOOTH_CONNECT ;;
    Camera) echo android.permission.CAMERA ;;
    Notifications) echo android.permission.POST_NOTIFICATIONS ;;
  esac
}

grant_all() {
  for p in $all; do adb shell pm grant "$app" "$p" || true; done
}

# Revokes a group, and clears what Android remembers of earlier answers so its dialog asks again.
revoke() {
  for p in $(permissions_of "$1"); do
    adb shell pm revoke "$app" "$p" || fail "could not revoke $p"
    adb shell pm clear-permission-flags "$app" "$p" user-set user-fixed 2>/dev/null || true
  done
}

grant_all
phase seed
for group in Camera Notifications Location NearbyDevices; do
  revoke "$group"
  phase deniedIsExplained "-e group $group"
  grant_all
done

# Revoked in system settings while the app is open, then the app comes back (FBBD-35).
phase openPermissions --no-restart
revoke Camera
sleep 2
adb shell input keyevent KEYCODE_HOME
sleep 1
adb shell am start -W -n "$app/.MainActivity" >/dev/null || fail "the app did not open"
sleep 3
phase revokeSeenOnResume "--no-restart -e group Camera"
grant_all
echo "Permissions test passed."
