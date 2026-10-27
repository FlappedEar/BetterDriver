# BetterDriver for iPhone and iPad

Native SwiftUI app (iOS 17+, iPhone 12 and newer, iPad) around the shared Kotlin
Multiplatform core in [`../shared`](../shared). Plan: Confluence "iOS / iPadOS app plan",
Jira epic FBBD-328.

## Build (macOS)

Needs Xcode, JDK 17+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```sh
./generate.sh            # builds BetterDriverCore.xcframework, generates BetterDriver.xcodeproj
open BetterDriver.xcodeproj
```

`BetterDriver.xcodeproj` is generated from `project.yml` and not committed. Run
`./generate.sh` again after changing the Kotlin core or `project.yml`.

## CI

`.github/workflows/ios.yml` runs on a macOS runner: the core's common tests on the iOS
simulator, the XCFramework, and the app's unit tests on an iPad and an iPhone simulator.
