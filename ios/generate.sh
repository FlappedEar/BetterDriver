#!/usr/bin/env bash
# Builds the shared Kotlin core as an XCFramework and generates BetterDriver.xcodeproj (FBBD-329).
# Needs macOS with Xcode, JDK 17+ and XcodeGen (brew install xcodegen).
set -euo pipefail
cd "$(dirname "$0")"
root=..
BETTERDRIVER_VERSION=$(sed -n 's/^betterdriver.version=//p' "$root/gradle.properties")
export BETTERDRIVER_VERSION
# debug (default) or release: which build of the Kotlin core the app links.
export BETTERDRIVER_CORE_BUILD=${BETTERDRIVER_CORE_BUILD:-debug}
if [[ "${SKIP_CORE:-}" != 1 ]]; then
    # macOS ships bash 3.2: no ${var^}.
    case $BETTERDRIVER_CORE_BUILD in
        release) task=assembleBetterDriverCoreReleaseXCFramework ;;
        *) task=assembleBetterDriverCoreDebugXCFramework ;;
    esac
    "$root/gradlew" -p "$root" -Pbetterdriver.coreOnly ":core-ios:$task"
fi
xcodegen generate
