# BetterDriver

Android track-day coach: logging and live timing, in-car spoken cues, and
post-session analysis with video. Personal use, Android only: a phone in the
car, a tablet for review. Plan: Confluence "BetterDriver Build Plan";
work is tracked in Jira project FBBD.

## Modules

| Module | Holds |
| --- | --- |
| `app` | Activity, navigation, app wiring |
| `recorder` | Recording service and crash-safe session store; runs in its own process (FBBD-69 to FBBD-72) |
| `timing` | Tracks, circuit directory, track detection, lap and sector timing, predictive delta; pure Kotlin |
| `coach` | Live coach: corners, per-track reference from earlier sessions, cue engine; pure Kotlin |
| `obd` | OBD-II channels, RaceChrono-compatible equations, car profiles, ELM327 response parsing and polling; pure Kotlin |
| `core-ui` | Theme and shared Compose components |
| `core-ble` | RaceBox Mini, Mini S and Micro over BLE; Bluetooth NMEA 0183 receivers over SPP |
| `feature-track` | Always-on track screen |
| `feature-review` | Session review (phone and tablet layouts) |

The screens are placeholders until each design is approved.

## Build

JDK 17 and the Android SDK (compile SDK 36).

```
./gradlew build detekt        # build, unit tests, Android lint, detekt
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

## CI and releases

- `.github/workflows/ci.yml`: unit tests (`test`), Android lint (`lint`),
  detekt and `assemble` as parallel jobs on every pull request and on `main`.
  The debug APK is kept as an artifact of runs on `main` and manual runs.
- `.github/workflows/release.yml`: a tag `vX.Y.Z` on `main` (or a manual run
  with the version) publishes `BetterDriver-X.Y.Z-android.apk` and
  `SHA256SUMS.txt` on GitHub Releases, once CI is green on that commit. The
  version must match `betterdriver.version` in `gradle.properties`; the build
  number is the commit count. Versions below 1.0.0 are pre-releases.
- `.github/workflows/signing-key.yml`: daily check of the signing key secret.
  See [docs/signing-key.md](docs/signing-key.md).
