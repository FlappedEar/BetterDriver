#!/usr/bin/env bash
# Builds the shared Kotlin core as an XCFramework and generates BetterDriver.xcodeproj (FBBD-329).
# Needs macOS with Xcode, JDK 17+ and XcodeGen (brew install xcodegen).
set -euo pipefail
cd "$(dirname "$0")"
root=..
BETTERDRIVER_VERSION=$(sed -n 's/^betterdriver.version=//p' "$root/gradle.properties")
export BETTERDRIVER_VERSION
if [[ "${SKIP_CORE:-}" != 1 ]]; then
    "$root/gradlew" -p "$root" -Pbetterdriver.coreOnly :shared:assembleBetterDriverCoreDebugXCFramework
fi
xcodegen generate
