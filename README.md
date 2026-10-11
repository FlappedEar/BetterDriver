# BetterDriver

Android track-day coach: logging and live timing, in-car spoken cues, and
post-session analysis with video. Personal use, Android only (Pixel 6 in the
car, Galaxy Tab for review). Plan: Confluence "BetterDriver Build Plan";
work is tracked in Jira project FBBD.

## Modules

| Module | Holds |
| --- | --- |
| `app` | Activity, navigation, app wiring |
| `recorder` | Recording service and crash-safe session store; runs in its own process (FBBD-69 to FBBD-72) |
| `core-ui` | Theme and shared Compose components |
| `core-ble` | RaceBox Micro over BLE |
| `feature-track` | Always-on track screen |
| `feature-review` | Session review (phone and tablet layouts) |

The screens are placeholders until each design is approved.

## Build

JDK 17, Gradle 8.14.3 and the Android SDK (compile SDK 36). The Gradle
wrapper is not committed yet.

```
gradle build detekt        # build, unit tests, Android lint, detekt
gradle :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

## CI and releases

- `.github/workflows/ci.yml`: build, tests, lint and detekt on every pull
  request and on `main`; the debug APK is kept as a run artifact.
- `.github/workflows/release.yml`: a tag `vX.Y.Z` on `main` (or a manual run
  with the version) publishes `BetterDriver-X.Y.Z-android.apk` and
  `SHA256SUMS.txt` on GitHub Releases, once CI is green on that commit. The
  version must match `betterdriver.version` in `gradle.properties`; the build
  number is the commit count. Versions below 1.0.0 are pre-releases.
- `.github/workflows/signing-key.yml`: daily check of the signing key secret.
  See [docs/signing-key.md](docs/signing-key.md).
