# BetterDriver

Track-day coach for Android and iOS: logging and live timing, in-car spoken
cues, and post-session analysis with video. Personal use. A phone records in the
car (an Android phone or an iPhone); a tablet (an Android tablet or an iPad) is
for analysis only and does not record. The iPhone and iPad app is in `ios/`
(SwiftUI, on TestFlight) and shares the Kotlin Multiplatform core with Android;
what each platform has is in [docs/parity.md](docs/parity.md). How to use it: the
[user guide](https://flappedear.github.io/BetterDriver/) (source in
[guide/](guide/README.md)). Plan: Confluence "BetterDriver Build Plan"; work is
tracked in Jira project FBBD.

## Modules

| Module | Holds |
| --- | --- |
| `app` | Activity, navigation, app wiring, track library and corner corrections |
| `recorder` | Recording service and crash-safe session store; runs in its own process (FBBD-69 to FBBD-72) |
| `timing` | Tracks, circuit directory, track detection, lap and sector timing, predictive delta; pure Kotlin |
| `coach` | Live coach: corners, per-track reference from earlier sessions, cue engine; pure Kotlin |
| `analysis` | Post-session analysis, a port of FlappedEar Telemetry's core; see [analysis/README.md](analysis/README.md); pure Kotlin |
| `video` | Video clips on session time, MP4 and GPMF reading, alignment; see [video/README.md](video/README.md); pure Kotlin |
| `gopro` | GoPro control, download and import; see [gopro/README.md](gopro/README.md); pure Kotlin |
| `obd` | OBD-II channels, RaceChrono-compatible equations, car profiles, ELM327 response parsing and polling; pure Kotlin |
| `setup` | Equipment (GNSS receivers, cameras, sensors), car profiles and the session choice |
| `transfer` | Session and track transfer between phone and tablet, backup and restore |
| `updater` | In-app update check against GitHub Releases |
| `core-ui` | Theme and shared Compose components |
| `core-ble` | RaceBox Mini, Mini S and Micro over BLE; Bluetooth NMEA 0183 receivers over SPP |
| `feature-track` | Always-on track screen |
| `feature-review` | Lap review with each lap's video (phone and tablet layouts) |
| `feature-analysis` | Session analysis screens: summary, focus, compare, corners and every pass, progression, data quality |
| `shared` | Common file access, formats and maths for the shared core (Kotlin Multiplatform) |
| `videosync` | Video clips on session time, MP4 and GPMF reading, GoPro GPS alignment and Open GoPro control, shared by both apps |
| `racebox` | RaceBox message decoding, device session and receiver clock |
| `heartrate` | Heart-rate strap decoding and the session's heart-rate state |
| `core-ios` | Packs the shared modules as an XCFramework for the iOS app, with its bridges |
| `ios/` | The iPhone and iPad app (SwiftUI, XcodeGen); see [ios/README.md](ios/README.md) |

The modules `shared`, `analysis`, `coach`, `heartrate`, `obd`, `racebox`, `recorder`, `setup`,
`timing`, `transfer` and `videosync` are Kotlin Multiplatform: a JVM target for Android and iOS
targets. Logic both apps need goes into their `commonMain` (epic FBBD-355).

`tools/` holds the scripts that make test fixtures (mock session, review fixtures, Circuit Tools
sync kit) and update the circuit directory.

## Build

JDK 17 and the Android SDK (compile SDK 36).

```
./gradlew build detekt        # build, unit tests, Android lint, detekt
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

## CI and releases

- `.github/workflows/ci.yml`: unit tests (`test`, which also verifies the
  screenshot goldens), Android lint (`lint`), detekt and `assemble` as
  parallel jobs on every pull request and on `main`, plus the emulator smoke
  tests (FBBD-161, see [CONTRIBUTING.md](CONTRIBUTING.md)). The unit tests
  include the replay of real recordings (FBBD-194) and the Telemetry parity
  tests. The debug APK is kept as an artifact of runs on `main` and manual
  runs.
- `.github/workflows/screenshots.yml`: records the screenshot goldens; see
  below.
- `.github/workflows/release.yml`: a tag `vX.Y.Z` on `main` (or a manual run
  with the version) publishes `BetterDriver-X.Y.Z-android.apk` and
  `SHA256SUMS.txt` on GitHub Releases, once CI is green on that commit. The
  version must match `betterdriver.version` in `gradle.properties`; the build
  number is the commit count. Versions below 1.0.0 are pre-releases.
  It also needs the release's acceptance record,
  `docs/release-notes/vX.Y.Z.md`; see
  [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md).
- `.github/workflows/signing-key.yml`: daily check of the signing key secret.
  See [docs/signing-key.md](docs/signing-key.md).

## Screenshot tests

`core-ui` (theme and components) and `feature-track` (the whole track screen)
have JVM screenshot tests with [Roborazzi](https://github.com/takahirom/roborazzi)
on Robolectric. The goldens are the PNGs in `<module>/src/test/screenshots`,
the same pictures as the `@Preview`s.

- CI runs `./gradlew test -Proborazzi.test.verify=true` and fails on any pixel
  difference; the failed run's `reports-Unit tests` artifact holds the actual
  and diff images (`build/outputs/roborazzi`).
- After a deliberate UI change, record new goldens on a GitHub runner, so they
  match the runner that verifies them: add the label `record-screenshots` to
  the pull request (remove and add it again for another recording), or run the
  Screenshots workflow on the branch. It commits the changed goldens to the
  branch and uploads them as the `screenshots` artifact. Push again (or rerun
  CI) to verify against them.
- Locally: `./gradlew recordRoborazziDebug`, `verifyRoborazziDebug` or
  `compareRoborazziDebug`. Goldens recorded on another OS can differ in
  anti-aliasing, so commit only goldens recorded in CI.
