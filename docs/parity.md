# Android and iOS feature parity

Every user feature and diagnostic of BetterDriver on Android and iOS (iPhone and iPad), including
planned work. The same table, with release history, is on Confluence: "Android and iOS feature parity"
(space B). Epic FBBD-355 moves the logic both apps need into the shared Kotlin core, so a row's
"Code" column should end up "Shared" and platform code is only Bluetooth, sockets, audio, camera,
files and drawing.

Legend: ✅ done · 🟡 partial · ❌ missing · 🔵 open PR · ⚪ planned · ➖ not applicable · 🟣 to verify (FBBD-375).
Android: first release naming the feature (≤0.3.1 = before release notes). iOS: TestFlight build.

## The rule

A pull request that adds a feature, a screen or a log tag on one platform adds or updates its row
here, with the other platform's status and, while the other platform lacks it, a Jira issue.
`tools/parity/check.py` runs in CI and fails when:

- a platform key in the code is in no row ("Keys" column);
- a row names a key the code no longer has;
- a row says a platform lacks the feature (❌ 🟡 🟣 ⚪) and names no FBBD issue.

Keys are read from the code: `route:` an Android destination in `app/.../Routes.kt`, `ios-screen:` a
SwiftUI view struct named `...Page`, `View`, `Tab`, `Sheet` or `Screen`, `log-android:` and `log-ios:`
a log tag written only by that platform's code. Log tags written from `commonMain` are shared and need
no key. `python3 tools/parity/check.py --keys` lists them.

## Features
| Area | Feature | Android | iOS | Code | Jira | Keys |
|---|---|---|---|---|---|---|
| Recording | RaceBox Micro/Mini over BLE, 25 Hz | ✅ ≤0.3.1 | ✅ TF 1.14+ | Decoder shared; BLE link twice (core-ble / RaceBoxLink.swift) | FBBD-8, FBBD-43, FBBD-44, FBBD-358 | `log-android:RaceBox` `log-ios:RaceBox` `ios-screen:ReceiverSheet` |
| Recording | RaceBox setup ladder, fast link, MTU handling, reconnect | ✅ 1.6.0, 1.16.0 | 🟡 own simpler link: reconnect 0.5–3 s, no setup ladder or fast link | Twice: core-ble GattLink/SetupLadder vs Swift | FBBD-282, FBBD-272, FBBD-358 | `log-android:Link` |
| Recording | Other GNSS receivers: NMEA BLE/Wi-Fi, BLE LNS, VBOX Sport, Dragy, RaceChrono DIY | ✅ ≤0.3.x | ❌ | Android only (core-ble, JVM) | FBBD-105, FBBD-114..118, FBBD-374 |  |
| Recording | Phone GPS when no receiver; phone accelerometer, live G with phone GPS | ✅ 1.8.0, 1.15.0 | ✅ | Shared fusion; sensor shim per platform | FBBD-275, FBBD-337, FBBD-226 | `log-android:PhoneGnss` `log-android:PhoneImu` |
| Recording | Crash-safe recorder, session files, storage low stop | ✅ ≤0.3.1 | ✅ | Twice: recorder Recorder.kt JVM + iosMain | FBBD-70, FBBD-360 | `log-android:RecorderService` `log-android:RecorderClient` `log-android:DeviceChecks` `log-ios:Recording` |
| Recording | Recover interrupted sessions | ✅ ≤0.3.1 | ✅ TF 1405 | Shared store | FBBD-71 | `log-ios:Recovery` |
| Recording | Recorder in its own process (coach crash never stops recording) | ✅ ≤0.3.1 | ➖ n/a single-process app | Platform | FBBD-69 | `log-android:RecorderForeground` |
| Recording | Live Activity on the lock screen | ➖ n/a | ⚪ planned removed until app is stable | iOS only | FBBD-340 |  |
| Car data | OBD-II over BLE (Vgate vLinker MC+) | ✅ 1.2.0 | 🟡 TF 1419, not yet proven in the car | OBD protocol shared (obd commonMain); link twice | FBBD-121, FBBD-333, FBBD-358 | `log-android:Obd` `log-ios:Obd` |
| Car data | OBD-II over Wi-Fi ELM327 | ✅ 1.2.0 | 🟡 TF 1413 EINTR fix, not yet proven in the car | Socket shim per platform; supervisor twice | FBBD-351, FBBD-358 |  |
| Car data | OBD reconnect every 2 s after a drop | ✅ 1.19.0 | 🟡 retry on rediscovery only | Twice | FBBD-271, FBBD-358 |  |
| Car data | FR-S passive CAN as primary car data (steering, brake pressure, pedal, rpm, temps) | ✅ 1.12.0 | 🟡 in shared code (broadcast poll set, ObdFeed), never run on a car | Shared obd core | FBBD-320, FBBD-263 |  |
| Car data | Read only used channels; smooth RPM; steady temperatures | ✅ 1.6.0 | ✅ via shared core | Shared | FBBD-283, FBBD-284, FBBD-303 |  |
| Car data | Car page: scan car, PIDs, CAN ids, own parameters | ✅ 1.17.0 | ✅ TF 1410 | Twice: Android CarScreens vs core-ios CarPageCore | FBBD-344 | `log-android:Car` `log-ios:Car` `route:Car` `route:CarPagePattern` `route:CarPidsPattern` `route:CarCanPattern` `route:CarParameterPattern` `ios-screen:CarPage` `ios-screen:CarPidsPage` `ios-screen:CarCanPage` |
| Car data | Per-car PID profiles (FR-S first); generic PID discovery | ⚪ planned | ⚪ planned | Should be shared | FBBD-16, FBBD-15 |  |
| Car data | OBD channels: crash-safe, independent reconnect, gaps and quality metadata | ⚪ planned | ⚪ planned | Should be shared | FBBD-173 |  |
| Car data | Tyre pressures and temperatures (FlappedEar TPMS bridge) | ⚪ planned | ⚪ planned | — | FBBD-224 |  |
| Heart rate | BLE heart-rate strap in the session; shown in review | ✅ 1.2.0 | ✅ TF 1.16+ | Session logic shared; link twice | FBBD-119, FBBD-225 |  |
| Heart rate | Strap link lines in the debug log (found, connected, streaming, dropped, off) | ✅ 1.5.2 | 🟡 10 s heart-rate lines shared (#422); link lines (found, connected, dropped) own Swift text | HeartRateLog shared (#422); link lines twice until FBBD-358 | FBBD-279, FBBD-352, FBBD-356 | `log-ios:HeartRate` |
| Video | Phone camera video tied to session time; 4K/1080p/720p at 60/30 | ✅ ≤0.3.1, 1.2.0 | ✅ no 2.7K | Platform (CameraX / AVFoundation) | FBBD-21, FBBD-88, FBBD-265 | `log-android:CameraService` `log-android:PhoneCamera` `log-android:VideoCaps` `log-android:VideoModes` `log-android:WideRecorder` `log-android:Thermal` `ios-screen:VideoModeSheet` |
| Video | GoPro files added and synced by GoPro GPS; other action cameras by clock | ✅ 0.3.14 | ✅ TF 1393 | core-ios GoPro import (iOS copy of Android logic) | FBBD-22, FBBD-120, FBBD-343 | `log-android:ClipIndexer` `log-android:GoProImport` |
| Video | GoPro Bluetooth start/stop with the session | ✅ | ✅ TF 1400 | Shared OpenGoProCamera; BLE shim | FBBD-107, FBBD-343 | `log-android:GoPros` `log-ios:GoPro` |
| Video | GoPro Wi-Fi download | ✅ | ⚪ planned | Android only | FBBD-343 |  |
| Video | Real-camera test HERO11 control/download, HERO8 import | ⚪ planned | ⚪ planned | — | FBBD-82, FBBD-22 |  |
| Track screen | Track screen: delta, lap times, sectors, coach line, tiles, keep screen on | ✅ ≤0.3.1 | ✅ TF 1.14+ | Timing shared; UI per platform | FBBD-62, FBBD-12, FBBD-59, FBBD-63 | `route:Drive` `log-android:TrackScreen` `ios-screen:DriveView` `ios-screen:DeltaView` `ios-screen:LapTimesView` `ios-screen:CoachLineView` |
| Track screen | Track detection and live laps at a saved track | ✅ ≤0.3.1 | ✅ | Shared timing | FBBD-55, FBBD-58 | `route:Track` |
| Track screen | Provisional start/finish at a track with no line | ✅ 1.20.0 | ✅ TF 1422 | Shared timing (SessionTiming), wired in Android RecorderService and iOS LiveSession | FBBD-335, FBBD-369 |  |
| Track screen | Status strip: OBD and HR connected/connecting/lost; GNSS lost sound | ✅ 1.3.0 | ✅ | UI per platform | FBBD-268, FBBD-64 | `log-android:GnssSound` |
| Track screen | Configurable tiles; layout editor per car in Settings | ✅ 1.4.0, 1.16.0 | ✅ TF 1405 | Twice: app TrackTiles vs core-ios TrackTiles | FBBD-262, FBBD-345 | `ios-screen:TileView` `ios-screen:TileListSheet` `ios-screen:TrackScreenPage` `ios-screen:TrackScreenView` |
| Track screen | Second swipe screen and G radar | ✅ 1.12.0 | ✅ TF 1432 | Tile ids shared (TrackTiles); UI per platform | FBBD-262, FBBD-318, FBBD-368 | `ios-screen:GRadarView` |
| Track screen | Frame time p95 within budget | ✅ 1.20.0 | 🟡 frame stretches logged every 30 s (#430), budget not gated | FrameStretch shared (recorder commonMain) | FBBD-266, FBBD-376 | `log-ios:Frames` |
| Track screen | Demo track and built-in replay drives | ✅ 0.3.33+ | ❌ | Android only | FBBD-233, FBBD-254, FBBD-373 | `log-android:DemoReplay` |
| Coach | Spoken corner cues, never in a braking zone; praise; cool-down laps | ✅ ≤0.3.1 … 0.3.41 | ✅ | Cue engine shared; run glue twice (CoachService / LiveCoach) | FBBD-97, FBBD-243, FBBD-244, FBBD-357 |  |
| Coach | Coach uses the line plan, steering and gear | ✅ 1.10.0, 1.12.0, 1.14.0 | ✅ via shared core | Shared | FBBD-307, FBBD-321, FBBD-326 | `log-android:CoachReferences` |
| Coach | Polish voice, voice picker, speech speed | ✅ 0.3.42, 0.3.43 | ✅ | Speech per platform | FBBD-240, FBBD-245 | `log-android:Voice` `log-ios:Voice` `route:CoachVoice` |
| Coach | Warm-up lap when the track was driven earlier the same day | ✅ 0.3.3 | ✅ | Shared | FBBD-190 |  |
| Coach | Pre-rendered cue clips | ✅ ≤0.3.1 | ❌ live speech only | Android only | FBBD-48, FBBD-357 | `log-android:CueClips` |
| Coach | Headset delay: measured, re-measured on route change, in the log | 🟡 open for 1.0 | 🟡 re-timed on route change and in the log (TF 1425), not yet tested with a headset | Twice (iOS lines in Swift, #417) | FBBD-9, FBBD-49, FBBD-255, FBBD-354, FBBD-357 | `log-android:AudioOut` `log-android:AudioRoute` `log-android:OutputDelay` `log-ios:AudioRoute` `log-ios:OutputDelay` |
| Coach | Coach corner picks from the review | ✅ 0.3.x | ❌ | Store shared (CoachPicksStore commonMain, #421); iOS UI to do | FBBD-148, FBBD-363 | `log-android:CoachPicks` |
| Coach | Whole loop in CI on real sessions | 🟡 | ➖ n/a | CI | FBBD-311 |  |
| Sessions | Sessions by day; delete; share a session (.bdsession) | ✅ | ✅ | UI per platform; bundle shared | FBBD-90, FBBD-95, FBBD-23 | `route:Sessions` `log-android:Sessions` `ios-screen:SessionsListView` `ios-screen:SessionRowView` |
| Sessions | Export VBO + video (Circuit Tools), VBO, CSV, GPX | ✅ ≤0.3.21 | ✅ | Shared exporters | FBBD-25, FBBD-150, FBBD-216 |  |
| Sessions | Import .bdsession, VBO, GPX, RaceChrono CSV with progress | ✅ 1.11.0 | ✅ | Shared transfer | FBBD-164, FBBD-314 | `log-android:Imports` `log-android:Transfers` |
| Sessions | Backup and restore; share tracks between devices | ✅ | ❌ | Android only; track match shared (TrackMatch commonMain, #421) | FBBD-156, FBBD-80, FBBD-372 | `log-android:Backups` |
| Analysis | Six-tab session analysis (Summary, Compare, Map, Laps, Corners, Car) | ✅ | ✅ | Screen models twice (app sessionanalysis / ReviewBridge.swift) | FBBD-110, FBBD-360 | `route:SessionAnalysisPattern` `log-android:SessionAnalysis` `ios-screen:SessionView` `ios-screen:CompareTab` `ios-screen:LapsTab` |
| Analysis | Session without laps: route, speed, figures in the six tabs | 🔵 PR #416 tabs (figures 1.15.0) | ✅ | Twice | FBBD-342, FBBD-353 |  |
| Analysis | Summary: next-session focus, three lap times, where time goes, HR, G-G, acceleration runs | ✅ | ✅ TF 1416 | Words shared (SummaryText) | FBBD-20, FBBD-178, FBBD-103, FBBD-154 | `log-android:SessionFocus` `ios-screen:SummaryTab` |
| Analysis | Session vs earlier sessions and vs session before | ✅ 0.3.x | ❌ not on the Summary | Android only (Progress.kt) | FBBD-122, FBBD-123, FBBD-377 |  |
| Analysis | Map: where to find time, line plan, targets beyond own best | ✅ 1.10.0, 1.13.0 | ✅ | Shared analysis | FBBD-306, FBBD-312 | `log-android:LinePlan` `ios-screen:MapTab` |
| Analysis | Lap map coloured by speed and delta | ✅ | 🔵 PR #411 Map tab Laps view | Shared MapScale (#411) | FBBD-149, FBBD-378 | `ios-screen:LapMapView` `ios-screen:LapsMapView` |
| Analysis | Perfect lap in 3D, and as MP4 | ✅ 1.10.0, 1.11.0 | ✅ | Shared scene; painter per platform | FBBD-308, FBBD-316 |  |
| Analysis | Corners tab: theoretical best by corner, braking, speeds, pickup, gear | ✅ | ✅ | Shared | FBBD-104, FBBD-326 | `ios-screen:CornersTab` |
| Analysis | Compare two laps with both videos, delta chart | ✅ | ✅ | Shared timing; UI per platform | FBBD-24, FBBD-93 | `route:Review` `ios-screen:LapVideoView` |
| Analysis | Pick two laps from any sessions | ✅ | 🔵 PR #411 | Shared LapPicker (#411) | FBBD-92, FBBD-379 | `route:ReviewPattern` `log-android:ReviewLaps` `ios-screen:LapPickerScreen` |
| Analysis | Every pass of one corner, with video | ✅ | 🔵 PR #411 | Shared ReviewLaps (#411) | FBBD-93, FBBD-153, FBBD-362 | `ios-screen:EveryPassScreen` `ios-screen:CornerClipView` |
| Analysis | Car tab: pedals, FR-S brake pressure and steering, temperatures, HR | ✅ 1.9.0, 1.13.0 | ✅ | UI per platform | FBBD-176, FBBD-322 | `ios-screen:CarTab` |
| Analysis | Track day page | ✅ 1.11.0 | ✅ | Shared analysis; UI per platform | FBBD-315 | `route:TrackDayPattern` `log-android:TrackDay` `ios-screen:TrackDayView` |
| Analysis | Progression at a track | ✅ 0.3.9 | 🔵 PR #411 | Moved to shared core in #411 | FBBD-181 | `route:TrackProgressionPattern` `log-android:Progression` `ios-screen:ProgressionScreen` `ios-screen:ProgressionSheet` `ios-screen:ProgressionMetricView` |
| Analysis | Coach review (each cue and whether the next pass improved) | ✅ | 🔵 PR #411 | Shared CueCards in #411 | FBBD-99 | `route:CueReviewPattern` `ios-screen:CoachReviewScreen` |
| Analysis | Coaching history | ✅ 0.3.9 | ❌ | Store shared (CoachingHistoryStore commonMain, #421); iOS UI to do | FBBD-180, FBBD-364 | `log-android:CoachingHistory` |
| Analysis | Loop proof: time won back in focus corners | ✅ 1.10.0 | 🟡 track day proof only; session won-back needs coaching history | Shared analysis | FBBD-310, FBBD-313, FBBD-364 |  |
| Analysis | Data quality card | ✅ 0.3.2 | ❌ log only | Quality checks JVM-only | FBBD-183, FBBD-365 | `log-android:QualityReports` |
| Analysis | Provenance and confidence; driving states and coasting | ✅ 0.3.3, 0.3.9 | 🟡 provenance and confidence done; driving-states strip missing | Shared | FBBD-174, FBBD-177, FBBD-175, FBBD-380 |  |
| Video export | Watch a session's video with the data overlay | ✅ 1.15.0 | ✅ TF 1.16+ | Overlay geometry shared | FBBD-341 | `route:WatchVideoPattern` `log-android:WatchVideo` `ios-screen:DataVideoWatchPage` `ios-screen:DataVideoPlayerPage` |
| Video export | Video with data in Tech HUD layout; single-lap export | ✅ 1.17.0, 1.18.0 | ✅ TF 1413/1416 | HudGeometry shared; painter twice | FBBD-152, FBBD-346, FBBD-347 | `log-android:VideoExports` `log-android:VideoExportService` `ios-screen:DataVideoSheet` |
| Video export | Optimal-lap composite video | ✅ ≤0.3.2 | ❌ | Android only | FBBD-163, FBBD-370 | `log-android:OptimalVideo` |
| Video export | Play video in landscape | ✅ | ✅ TF 1428 | UI | — | `log-ios:Orientation` |
| Tracks | Tracks list, where you are, New track here | ✅ | ✅ TF 1.15+ | Store shared (TrackStore commonMain, #421); list UI twice: Android Tracks vs core-ios TrackLibrary | FBBD-336 | `route:Tracks` `log-android:Tracks` `ios-screen:TracksScreen` `ios-screen:TracksView` |
| Tracks | Track editor: start/finish and sectors on the map; MapTiler or OSM | ✅ | ✅ | Twice: Android editor vs core-ios TrackEditor | FBBD-56, FBBD-230 | `route:TrackEditorPattern` `ios-screen:TrackEditorScreen` `ios-screen:TrackEditorView` |
| Tracks | Circuit directory; point-to-point tracks; delete a track | ✅ | ✅ | Shared timing | FBBD-79, FBBD-155, FBBD-157 |  |
| Tracks | Make a track from a session | ✅ | 🔵 PR #411 | Shared trackProposal (#411); Android still its own | FBBD-151, FBBD-367 |  |
| Tracks | Manual corner and segment corrections | ✅ 0.3.9 | 🔵 PR #411 | Shared CornerCorrector (#411); Android editor not on it yet | FBBD-179, FBBD-366 |  |
| Tracks | Apple Maps on iOS | ➖ n/a | ⚪ planned paused | iOS only | FBBD-381 |  |
| Settings | Settings topics; equipment; cars | ✅ | ✅ TF 1405 | Stores shared (SetupStore, CoachSettingsStore commonMain, #421); setup UI logic twice: Android vs core-ios SettingsCore | FBBD-338, FBBD-349 | `route:Settings` `route:SettingsTopicPattern` `route:AddEquipment` `route:EquipmentPattern` `route:CarPattern` `log-android:Settings` `log-ios:Settings` `ios-screen:SettingsView` `ios-screen:AppPage` `ios-screen:EquipmentPage` `ios-screen:EquipmentItemPage` `ios-screen:AddEquipmentPage` `ios-screen:CarsPage` `ios-screen:CarEditSheet` |
| Settings | OBD page with connection and live values | ✅ 1.1.0 | ✅ | UI per platform | FBBD-261 | `route:Obd` `ios-screen:ObdPage` |
| Settings | Spoken coach settings; phone video setting; About and credits | ✅ | ✅ | UI per platform | FBBD-98, FBBD-88, FBBD-79 | `route:CoachSettings` `route:About` `log-android:CoachPrefs` `ios-screen:CoachSettingsPage` `ios-screen:AboutPage` `ios-screen:VideoPage` |
| Settings | First-run setup / Run setup again | ✅ | ❌ placeholder | — | FBBD-371 | `route:Setup` `log-android:SetupRepo` `ios-screen:NotImplementedPage` |
| Settings | RaceBox diagnostics; camera test | ✅ ≤0.3.1, 1.5.1 | ❌ placeholder | — | FBBD-89, FBBD-278, FBBD-371 | `route:CameraTest` `route:DiagnosticsPattern` `log-android:CameraTest` `ios-screen:DiagnosticsPage` |
| Settings | In-app updater from GitHub Releases | ✅ | ➖ n/a TestFlight | Platform | FBBD-73 |  |
| Diagnostics | Continuous debug log, share as one zip; crash capture | ✅ ≤0.3.1 | ✅ TF 1.14+ | Writer per platform, same line format | FBBD-146, FBBD-339 | `log-android:App` `log-android:Crash` `log-android:DebugLogs` `log-android:Log` `log-ios:App` `log-ios:Crash` `log-ios:DebugLogs` |
| Diagnostics | Log lines: session start/stop and events, GNSS seconds, equipment reach, load, quality report at stop | ✅ | 🟡 session start/stop, events, GNSS seconds, heart rate, equipment reach from the shared core (#422); load line and quality report at stop still Android only | Shared (recorder commonMain, #422); load line and quality checks JVM-only | FBBD-193, FBBD-280, FBBD-281, FBBD-356, FBBD-359 |  |
| Diagnostics | Log lines: coach cues, audio route, output delay | ✅ | ✅ TF 1425, same tags and text as Android | Twice until moved | FBBD-354, FBBD-357 | `log-android:CoachService` `log-android:CoachLink` `log-ios:CoachService` |
| Look | Pitwall look for the whole app | ⚪ planned parked by Arek | ⚪ planned parked | — | FBBD-317 |  |
| Look | App shell: bottom bar Drive, Sessions, Car, Settings | ✅ | ✅ | Twice (Compose / SwiftUI) |  | `ios-screen:RootView` |
