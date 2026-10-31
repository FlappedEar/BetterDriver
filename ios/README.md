# BetterDriver for iPhone and iPad

Native SwiftUI app (iOS 17+, iPhone 12 and newer, iPad) around the shared Kotlin
Multiplatform core in [`../shared`](../shared) and the modules [`../core-ios`](../core-ios) exports. Plan: Confluence "iOS / iPadOS app plan",
Jira epic FBBD-328.

## Build and test on your Mac

Needs a Mac with Apple silicon (the core builds for the arm64 simulator only).

One-time setup:

```sh
# Xcode 16+ from the App Store, opened once (installs the iOS simulator), then:
xcodebuild -runFirstLaunch
brew install xcodegen
brew install --cask temurin@17      # skip if `java -version` already shows 17 or newer
git clone https://github.com/FlappedEar/BetterDriver.git
```

Build and open:

```sh
cd BetterDriver
ios/generate.sh                     # builds BetterDriverCore.xcframework, generates ios/BetterDriver.xcodeproj
open ios/BetterDriver.xcodeproj
```

The first `generate.sh` downloads Gradle and the Kotlin/Native toolchain (~1 GB, a few
minutes). In Xcode pick an iPad or iPhone simulator and press ⌘R to run, ⌘U to test.

Tests from the terminal:

```sh
./gradlew -Pbetterdriver.coreOnly iosSimulatorArm64Test        # Kotlin core on the simulator
xcrun simctl list devices available | grep -E "iPad|iPhone"    # pick a simulator name
xcodebuild test -project ios/BetterDriver.xcodeproj -scheme BetterDriver \
  -destination "platform=iOS Simulator,name=<name from the list>"
```

On a real iPad or iPhone: connect it by cable, turn on Settings > Privacy & Security >
Developer Mode, and pick it in Xcode. Signing needs a team: a free Apple ID works (the app
then runs for 7 days), the Developer Program account works without that limit. Pass your
team id so regenerating keeps it, for example `BETTERDRIVER_TEAM=ABCDE12345 ios/generate.sh`
(team id: developer.apple.com > Account > Membership details), or choose the team in
Signing & Capabilities after each `generate.sh`.

`BetterDriver.xcodeproj` is generated from `project.yml` and not committed. Run
`ios/generate.sh` again after changing the Kotlin core or `project.yml`.

## CI

`.github/workflows/ios.yml` runs on macOS runners. One job runs the core's common tests on the
iOS simulator, builds the simulator XCFramework and builds the app and its tests once; four jobs
then run the unit and UI tests from that build at the same time, on an iPad and an iPhone
simulator, each in two halves. The "iOS app" check passes when all of them passed.

## TestFlight

`.github/workflows/ios-release.yml` archives the app with the release build of the core
after each Release and from the Actions tab. With the App Store Connect API key in the
secrets `ASC_ISSUER_ID`, `ASC_KEY_ID` and `ASC_KEY_P8` it signs automatically (team from
the registered bundle id `com.flappedear.betterdriver`) and uploads to TestFlight;
without them it only checks that the unsigned release archive builds.
