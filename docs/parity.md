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

Screens look and read the same on both platforms, Android being the reference (FBBD-394): the same
tabs, sections and actions, with the same names, in the same order. Each such ordered list is marked
in the code of both apps with a comment `// parity-list:<name>` on the line right above its
declaration (an enum, a `listOf`, a `TabView`); the list is the first string literal of each line at
the block's first level (an `if` or `else` block in it counts as that level, so an item shown on one
device only is in the list). The "Screens" table below gives each list in the Android order, and CI
fails when any marked list differs from it, when a marker has no row, or when a row's list is not
marked in the Android code, or in the iOS code while the row's iOS cell says ✅ (a gap mark with a
FBBD issue lets an iOS screen that still differs go unmarked until it is changed).
`python3 tools/parity/check.py --lists` prints what it reads.

## Features
| Area | Feature | Android | iOS | Code | Jira | Keys |
|---|---|---|---|---|---|---|
| Recording | RaceBox Micro/Mini over BLE, 25 Hz | ✅ ≤0.3.1 | ✅ TF 1.14+ | Decoder, session and link states shared; BLE calls per platform (core-ble GattLink / RaceBoxLink.swift on NotifyLinkCore) | FBBD-8, FBBD-43, FBBD-44, FBBD-358 | `log-android:RaceBox` `log-ios:RaceBox` `ios-screen:ReceiverSheet` |
| Recording | RaceBox setup ladder, fast link, MTU handling, reconnect | ✅ 1.6.0, 1.16.0 | ✅ TF 1.24.0 via shared core (#459): NotifyLinkCore with Android's RaceBox numbers (retries 0.5–3 s, setup ladder 10/30 s, 3 s silence restart, Link log lines); MTU and fast link N/A (iOS picks them) | iOS on NotifyLinkCore + RaceBoxSession.linkChanged (both); Android GattLink keeps its own copy of the life cycle plus MTU/fast link/service recheck until it moves onto the core | FBBD-282, FBBD-272, FBBD-358 | `log-android:Link` |
| Recording | Other GNSS receivers: NMEA BLE/Wi-Fi, BLE LNS, VBOX Sport, Dragy, RaceChrono DIY | ✅ ≤0.3.x | ❌ | Android only (core-ble, JVM) | FBBD-105, FBBD-114..118, FBBD-374 |  |
| Recording | Phone GPS when no receiver; phone accelerometer, live G with phone GPS | ✅ 1.8.0, 1.15.0 | ✅ | Shared fusion; sensor shim per platform | FBBD-275, FBBD-337, FBBD-226 | `log-android:PhoneGnss` `log-android:PhoneImu` |
| Recording | Crash-safe recorder, session files, storage low stop | ✅ ≤0.3.1 | ✅ | Session list, recovery, read, delete shared (SessionFolders commonMain, #425); recorder writer twice: Recorder.kt JVM + iosMain | FBBD-70, FBBD-360 | `log-android:RecorderService` `log-android:RecorderClient` `log-android:DeviceChecks` `log-ios:Recording` |
| Recording | Recover interrupted sessions | ✅ ≤0.3.1 | ✅ TF 1405 | Shared store (SessionFolders commonMain, #425) | FBBD-71 | `log-ios:Recovery` |
| Recording | Recorder in its own process (coach crash never stops recording) | ✅ ≤0.3.1 | ➖ n/a single-process app | Platform | FBBD-69 | `log-android:RecorderForeground` |
| Recording | Live Activity on the lock screen | ➖ n/a | ⚪ planned removed until app is stable | iOS only | FBBD-340 |  |
| Car data | OBD-II over BLE (Vgate vLinker MC+) | ✅ 1.2.0 | ✅ TF 1419; vLinker and Konnwei BLE work on the iPhone (30 Oct drives) | OBD protocol and connection supervisor shared (obd commonMain ObdConnector); BLE port per platform | FBBD-121, FBBD-333, FBBD-358 | `log-android:Obd` `log-ios:Obd` |
| Car data | OBD-II over Wi-Fi ELM327 | ✅ 1.2.0 | ✅ TF 1413; connected and polled a whole 30 min drive (29 Oct 13:27 log) | Socket shim per platform; supervisor shared (ObdConnector) | FBBD-351, FBBD-358 |  |
| Car data | OBD reconnect every 2 s after a drop | ✅ 1.19.0 | ✅ via shared core: same ObdConnector retries (2 s after a drop), retry at once when Bluetooth comes back | Shared (ObdConnector); Bluetooth/Wi-Fi watch per platform | FBBD-271, FBBD-358 |  |
| Car data | FR-S passive CAN as primary car data (steering, brake pressure, pedal, rpm, temps) | ✅ 1.12.0 | 🟡 in shared code (broadcast poll set, ObdFeed), never run on a car | Shared obd core | FBBD-320, FBBD-263 |  |
| Car data | Read only used channels; smooth RPM; steady temperatures | ✅ 1.6.0 | ✅ via shared core | Shared | FBBD-283, FBBD-284, FBBD-303 |  |
| Car data | Car page: scan car, PIDs, CAN ids, own parameters | ✅ 1.17.0 | ✅ TF 1410 | Twice: Android CarScreens vs core-ios CarPageCore | FBBD-344 | `log-android:Car` `log-ios:Car` `route:Car` `route:CarPagePattern` `route:CarPidsPattern` `route:CarCanPattern` `route:CarParameterPattern` `ios-screen:CarPage` `ios-screen:CarPidsPage` `ios-screen:CarCanPage` |
| Car data | Per-car PID profiles (FR-S first); generic PID discovery | ✅ 1.0.1, 1.12.0, 1.17.0 | ✅ via shared core | Shared (obd commonMain CarProfile, CarScan) | FBBD-16, FBBD-15 |  |
| Car data | OBD channels: crash-safe, independent reconnect, gaps and quality metadata | ✅ 1.19.0 | ✅ via shared core (channels and ObdConnector reconnect) | Shared | FBBD-173, FBBD-358 |  |
| Car data | Tyre pressures and temperatures (FlappedEar TPMS bridge) | ⚪ planned | ⚪ planned | — | FBBD-224 |  |
| Heart rate | BLE heart-rate strap in the session; shown in review | ✅ 1.2.0 | ✅ TF 1.16+ | Session logic shared; link twice | FBBD-119, FBBD-225 |  |
| Heart rate | Strap link lines in the debug log (found, connected, streaming, dropped, off) | ✅ 1.5.2 | ✅ TF 1.24.0 via shared core (#455): link lines from NotifyLinkCore (tag Link); strap choice lines (Settings, remembered, found) Swift | HeartRateLog (#422) and the strap link life cycle NotifyLinkCore shared (FBBD-358): retries 1–5 s, setup ladder, quiet wait after 6 setups, 5 s silence restart | FBBD-279, FBBD-352, FBBD-356, FBBD-358 | `log-ios:HeartRate` |
| Settings | Only the OBD-II adapters and the heart-rate strap listed in Equipment record; one removed there is not used again; Wi-Fi OBD-II adapter under Add without Bluetooth | ✅ 1.3.0 | ✅ next TestFlight after 1.25.0 (FBBD-349): no fallback to the adapter used last, any adapter nearby or a remembered strap; a setup saved before gets the Wi-Fi adapter it used listed once | Shared (setup commonMain SessionEquipment: which adapters in which order, which strap); Bluetooth and sockets per platform | FBBD-121, FBBD-349 |  |
| Video | Phone camera video tied to session time; 4K/1080p/720p at 60/30 | ✅ ≤0.3.1, 1.2.0 | ✅ no 2.7K | Platform (CameraX / AVFoundation) | FBBD-21, FBBD-88, FBBD-265 | `log-android:CameraService` `log-android:PhoneCamera` `log-ios:PhoneCamera` `log-android:VideoCaps` `log-android:VideoModes` `log-android:WideRecorder` `log-android:Thermal` `ios-screen:VideoModeSheet` |
| Video | GoPro files added and synced by GoPro GPS; other action cameras by clock | ✅ 0.3.14 | ✅ TF 1393 | core-ios GoPro import (iOS copy of Android logic) | FBBD-22, FBBD-120, FBBD-343 | `log-android:ClipIndexer` `log-android:GoProImport` |
| Video | GoPro Bluetooth start/stop with the session | ✅ | ✅ TF 1400 | Shared OpenGoProCamera; BLE shim | FBBD-107, FBBD-343 | `log-android:GoPros` `log-ios:GoPro` |
| Video | GoPro Wi-Fi download after the drive ("video on the camera" › Download), lined up by GoPro GPS | ✅ | ✅ TF 1.27.0, real camera not yet tried | Shared GoProDownload (videosync commonMain); Wi-Fi join and HTTP shims (WifiNetworkSpecifier + HttpURLConnection / NEHotspotConfiguration + URLSession) | FBBD-343, FBBD-82 |  |
| Video | Real-camera test HERO11 control/download, HERO8 import | ⚪ planned | ⚪ planned | — | FBBD-82, FBBD-22 |  |
| Track screen | Track screen: delta, lap times, sectors, coach line, tiles, keep screen on | ✅ ≤0.3.1 | ✅ TF 1.14+ | Timing shared; UI per platform | FBBD-62, FBBD-12, FBBD-59, FBBD-63 | `route:Drive` `log-android:TrackScreen` `ios-screen:DriveView` `ios-screen:DeltaView` `ios-screen:LapTimesView` `ios-screen:CoachLineView` |
| Track screen | Track detection and live laps at a saved track | ✅ ≤0.3.1 | ✅ | Shared timing | FBBD-55, FBBD-58 | `route:Track` |
| Track screen | Provisional start/finish at a track with no line | ✅ 1.20.0 | ✅ TF 1422 | Shared timing (SessionTiming), wired in Android RecorderService and iOS LiveSession | FBBD-335, FBBD-369 |  |
| Track screen | Status strip: OBD and HR connected/connecting/lost; GNSS lost sound | ✅ 1.3.0 | ✅ | UI per platform | FBBD-268, FBBD-64 | `log-android:GnssSound` |
| Track screen | Configurable tiles; layout editor per car in Settings | ✅ 1.4.0, 1.16.0 | ✅ TF 1405 | Twice: app TrackTiles vs core-ios TrackTiles | FBBD-262, FBBD-345 | `ios-screen:TileView` `ios-screen:TileListSheet` `ios-screen:TrackScreenPage` `ios-screen:TrackScreenView` |
| Track screen | Gear tile, from RPM and speed with the session's own fitted gearbox, held between shifts | ✅ 1.23.0 | ✅ via shared core | LiveGear and GearTracker shared (timing commonMain), wired in Android RecorderService and iOS LiveSession; tile lists twice | FBBD-397 |  |
| Track screen | Second swipe screen and G radar | ✅ 1.12.0 | ✅ TF 1432 | Tile ids shared (TrackTiles); UI per platform | FBBD-262, FBBD-318, FBBD-368 | `ios-screen:GRadarView` |
| Track screen | Frame time p95 within budget | ✅ 1.20.0 | 🟡 frame stretches logged every 30 s (#430), budget not gated | FrameStretch shared (recorder commonMain) | FBBD-266, FBBD-376 | `log-ios:Frames` |
| Track screen | Demo track and built-in replay drives | ✅ 0.3.33+ | ✅ TF 1.24.0 (#457): Settings › Equipment › Try the app › Demo track, Drives, START sheet | Shared (DemoTrack, DemoCircuit, ScriptFeed in commonMain, #457); file access and UI per platform | FBBD-233, FBBD-254, FBBD-373 | `ios-screen:DemoDrivesSheet` |
| Coach | Spoken corner cues, never in a braking zone; praise; cool-down laps | ✅ ≤0.3.1 … 0.3.41 | ✅ | Shared (CoachRun commonMain, FBBD-357): reference, focus, cue scheduling, cue sound log; speech per platform | FBBD-97, FBBD-243, FBBD-244, FBBD-357 |  |
| Coach | Coach uses the line plan, steering and gear | ✅ 1.10.0, 1.12.0, 1.14.0 | ✅ via shared core | Shared | FBBD-307, FBBD-321, FBBD-326 | `log-android:CoachReferences` |
| Coach | Polish voice, voice picker, speech speed | ✅ 0.3.42, 0.3.43 | ✅ | Speech per platform | FBBD-240, FBBD-245 | `log-android:Voice` `log-ios:Voice` `route:CoachVoice` |
| Coach | Warm-up lap when the track was driven earlier the same day | ✅ 0.3.3 | ✅ | Shared | FBBD-190 |  |
| Coach | Pre-rendered cue clips (Settings › Spoken coach › Voice playback) | ✅ ≤0.3.1 | ✅ TF 1.27.0 (FBBD-48) | Shared (CueClips, CueClipSet, Pcm and Wav, CueClipLog lines in commonMain); text-to-speech rendering and playback per platform (CueClipBank, CueClipCache + CueClipRenderer) | FBBD-48, FBBD-357 |  |
| Coach | Headset delay: measured, re-measured on route change, in the log | 🟡 open for 1.0 | 🟡 re-timed on route change and in the log (TF 1425), not yet tested with a headset | Delay bookkeeping, re-timing and its log lines shared (CoachRun, SessionDelays, FBBD-357); route watch and measuring per platform | FBBD-9, FBBD-49, FBBD-255, FBBD-354, FBBD-357 | `log-android:AudioOut` `log-android:AudioRoute` `log-android:OutputDelay` `log-ios:AudioRoute` `log-ios:OutputDelay` |
| Coach | Coach corner picks from the review | ✅ 0.3.x | ✅ TF 1441 flags in Where time goes (#434) | Store shared (CoachPicksStore commonMain, #421); both coaches read the picks (CoachRun, FBBD-357); flaggable corners and pickedOf shared | FBBD-148, FBBD-363 | `log-android:CoachPicks` |
| Coach | Whole loop in CI on real sessions | 🟡 | ➖ n/a | CI | FBBD-311 |  |
| Sessions | Sessions by day; delete; share a session (.bdsession) | ✅ | ✅ | UI per platform; bundle shared | FBBD-90, FBBD-95, FBBD-23 | `route:Sessions` `log-android:Sessions` `ios-screen:SessionsListView` `ios-screen:SessionRowView` |
| Sessions | Export VBO + video (Circuit Tools), VBO, CSV, GPX | ✅ ≤0.3.21 | ✅ | Shared exporters | FBBD-25, FBBD-150, FBBD-216 |  |
| Sessions | Import .bdsession, VBO, GPX, RaceChrono CSV with progress | ✅ 1.11.0 | ✅ | Shared transfer | FBBD-164, FBBD-314 | `log-android:Imports` `log-android:Transfers` |
| Sessions | Backup and restore; share tracks between devices | ✅ | ✅ TF 1.26.0: Settings › App › Back up / Restore (Merge or Replace), Tracks › Import, track editor Share, .bdbackup and .bdtrack open from Files or AirDrop; files interchangeable with Android (no Android app settings in an iPhone backup) | Shared (transfer commonMain, FBBD-372): BackupFormat, BackupPrefs, RestoreRules, BackupTexts, TrackShare; iOS writes and restores with BackupWriter and BackupRestorer, Android still with its java.io Backup and BackupRestore on the same format and rules (BackupInteropTest checks both ways); file pickers per platform | FBBD-156, FBBD-80, FBBD-372 | `log-android:Backups` `log-ios:Backups` `log-ios:Imports` `log-ios:Transfers` `ios-screen:RestoreSheet` |
| Analysis | Six-tab session analysis (Summary, Compare, Map, Laps, Corners, Car) | ✅ | ✅ | Screen models twice (app sessionanalysis / ReviewBridge.swift) | FBBD-110, FBBD-360 | `route:SessionAnalysisPattern` `log-android:SessionAnalysis` `ios-screen:SessionView` `ios-screen:CompareTab` `ios-screen:LapsTab` |
| Analysis | Session without laps: route, speed, figures in the six tabs | 🔵 PR #416 tabs (figures 1.15.0) | ✅ | Twice | FBBD-342, FBBD-353 |  |
| Analysis | Summary: next-session focus, three lap times, where time goes, HR, G-G, acceleration runs | ✅ | ✅ TF 1416 | Words shared (SummaryText) | FBBD-20, FBBD-178, FBBD-103, FBBD-154 | `log-android:SessionFocus` `ios-screen:SummaryTab` |
| Analysis | Session vs earlier sessions and vs session before | ✅ 0.3.x | ✅ Summary card (#411, TF 1440) | Shared (readEarlierSessions, progressAgainst, progressBestText in analysis summary); UI per platform (Progress.kt / ProgressionCard.swift) | FBBD-122, FBBD-123, FBBD-377 |  |
| Analysis | Map: where to find time, line plan, targets beyond own best | ✅ 1.10.0, 1.13.0 | ✅ | Shared analysis | FBBD-306, FBBD-312 | `log-android:LinePlan` `ios-screen:MapTab` |
| Analysis | Lap map coloured by speed and delta | ✅ | ✅ TF 1440 Map tab Laps view (#411) | Shared MapScale (#411) | FBBD-149, FBBD-378 | `ios-screen:LapMapView` `ios-screen:LapsMapView` |
| Analysis | Perfect lap in 3D, and as MP4 | ✅ 1.10.0, 1.11.0 | ✅ | Shared scene; painter per platform | FBBD-308, FBBD-316 |  |
| Analysis | Satellite photo under the perfect lap in 3D | ✅ 1.11.0 (MapTiler key) | 🟡 TF 1.25.0 (#467) player: Apple Maps satellite, no key (FBBD-381); saved MP4 keeps the drawn road (Apple terms: no stored imagery) | Shared scene tiles (SceneGround, SceneTile); tile source per platform: Android MapTilerGround, iOS PerfectLapTiles (MKMapSnapshotter, memory only) | FBBD-316, FBBD-381 |  |
| Analysis | Corners tab: theoretical best by corner, braking, speeds, pickup, gear | ✅ | ✅ | Shared | FBBD-104, FBBD-326 | `ios-screen:CornersTab` |
| Analysis | Compare two laps with both videos, delta chart | ✅ | ✅ | Shared timing; UI per platform | FBBD-24, FBBD-93 | `route:Review` `ios-screen:LapVideoView` |
| Analysis | Pick two laps from any sessions | ✅ | ✅ TF 1440 (#411) | Shared LapPicker (#411) | FBBD-92, FBBD-379 | `route:ReviewPattern` `log-android:ReviewLaps` `ios-screen:LapPickerScreen` |
| Analysis | Every pass of one corner, with video | ✅ | ✅ TF 1440 (#411) | Shared ReviewLaps (#411) | FBBD-93, FBBD-153, FBBD-362 | `ios-screen:EveryPassScreen` `ios-screen:CornerClipView` |
| Analysis | Car tab: pedals, FR-S brake pressure and steering, temperatures, HR | ✅ 1.9.0, 1.13.0 | ✅ | UI per platform | FBBD-176, FBBD-322 | `ios-screen:CarTab` |
| Analysis | Track day page | ✅ 1.11.0 | ✅ | Shared analysis; UI per platform | FBBD-315 | `route:TrackDayPattern` `log-android:TrackDay` `ios-screen:TrackDayView` |
| Analysis | Progression at a track | ✅ 0.3.9 | ✅ TF 1440 (#411) | Moved to shared core in #411 | FBBD-181 | `route:TrackProgressionPattern` `log-android:Progression` `ios-screen:ProgressionScreen` `ios-screen:ProgressionSheet` `ios-screen:ProgressionMetricView` |
| Analysis | Coach review (each cue and whether the next pass improved) | ✅ | ✅ TF 1440 (#411) | Shared CueCards in #411 | FBBD-99 | `route:CueReviewPattern` `ios-screen:CoachReviewScreen` |
| Analysis | Coaching history | ✅ 0.3.9 | ✅ TF 1.24.0 (#458): Summary card, records kept as Android | Shared: CoachingHistoryStore (#421), keepCoachingHistory, rows and texts (CoachingRows, CoachingText); UI per platform | FBBD-180, FBBD-364 |  |
| Analysis | Loop proof: time won back in focus corners | ✅ 1.10.0 | ✅ TF 1.24.0 (#458): track day and session won-back | Shared analysis (lastFocusProof, coachingCard) | FBBD-310, FBBD-313, FBBD-364 |  |
| Analysis | Data quality card | ✅ 0.3.2 | ✅ TF 1.24.0 (#456): Summary card from the shared report; phone video frame count not measured on iOS | Shared: checks (analysis/quality commonMain, #432), quality.json store QualityReportFiles and card model qualityUi (#456); frame count of phone files Android only (MediaMetadataRetriever) | FBBD-183, FBBD-365 | `log-android:QualityReports` |
| Analysis | Provenance and confidence; driving states and coasting | ✅ 0.3.3, 0.3.9 | ✅ TF 1440 driving-states strip on Compare (#411) | Shared (driving-state names and keys in CompareModel, #411) | FBBD-174, FBBD-177, FBBD-175, FBBD-380 |  |
| Video export | Watch a session's video with the data overlay | ✅ 1.15.0 | ✅ TF 1.16+ | Overlay geometry shared | FBBD-341 | `route:WatchVideoPattern` `log-android:WatchVideo` `ios-screen:DataVideoWatchPage` `ios-screen:DataVideoPlayerPage` |
| Video export | Video with data in Tech HUD layout; single-lap export; gear on the RPM gauge | ✅ 1.17.0, 1.18.0, 1.23.0 | ✅ TF 1413/1416, gear via shared core | HudGeometry, gear (GearTracker) and texts shared; painter twice | FBBD-152, FBBD-346, FBBD-347, FBBD-397 | `log-android:VideoExports` `log-android:VideoExportService` `ios-screen:DataVideoSheet` |
| Video export | Optimal-lap composite video (realistic optimal, raw theoretical best; save only): "Realistic optimal video" under the lap times with its sheet, in the Video tab and Video with data | ✅ ≤0.3.2 | ✅ TF 1.25.0 (FBBD-370, #468) Video tab; under the lap times TF 1.27.0 (FBBD-370, #475) | Which laps, plan and texts shared (OptimalClipPlan, OptimalVideoChoice, CompositeOptions commonMain; core-ios CompositeLapChoice); cutting and drawing per platform | FBBD-163, FBBD-370 | `log-android:OptimalVideo` `ios-screen:LapTimesOptimalVideoSheet` |
| Video export | Play video in landscape | ✅ | ✅ TF 1428 | UI | — | `log-ios:Orientation` |
| Video export | Video tab in a session with video (Watch, Save video) | ✅ 1.24.0 (FBBD-392, #453) | ✅ TF 1447 (#435) | Parts listed twice (Android VideoExports, core-ios DataVideo); tab UI per platform | FBBD-392 |  |
| Tracks | Tracks list, where you are, New track here | ✅ | ✅ TF 1.15+ | Store shared (TrackStore commonMain, #421); list UI twice: Android Tracks vs core-ios TrackLibrary | FBBD-336 | `route:Tracks` `log-android:Tracks` `ios-screen:TracksScreen` `ios-screen:TracksView` |
| Tracks | Track editor: start/finish and sectors on the map; Android MapTiler or OSM, iOS Apple Maps satellite or map (FBBD-381) | ✅ | ✅ | Twice: Android editor vs core-ios TrackEditor | FBBD-56, FBBD-230 | `route:TrackEditorPattern` `ios-screen:TrackEditorScreen` `ios-screen:TrackEditorView` |
| Tracks | Circuit directory; point-to-point tracks; delete a track | ✅ | ✅ | Shared timing | FBBD-79, FBBD-155, FBBD-157 |  |
| Tracks | Make a track from a session | ✅ | ✅ TF 1440 (#411) | Shared trackProposal (#411); Android still its own | FBBD-151, FBBD-367 |  |
| Tracks | Manual corner and segment corrections | ✅ 0.3.9 | ✅ TF 1440 (#411) | Shared CornerCorrector (#411); Android editor not on it yet | FBBD-179, FBBD-366 |  |
| Tracks | Apple Maps on iOS (editor Satellite/Map, 3D ground) | ➖ n/a | ✅ TF 1.25.0 (#467): editor Satellite/Map, 3D ground under the player | iOS only (Android uses MapTiler/OSM) | FBBD-381 |  |
| Settings | Settings topics; equipment; cars | ✅ | ✅ TF 1405 | Stores shared (SetupStore, CoachSettingsStore commonMain, #421); setup UI logic twice: Android vs core-ios SettingsCore | FBBD-338, FBBD-349 | `route:Settings` `route:SettingsTopicPattern` `route:AddEquipment` `route:EquipmentPattern` `route:CarPattern` `log-android:Settings` `log-ios:Settings` `ios-screen:SettingsView` `ios-screen:AppPage` `ios-screen:EquipmentPage` `ios-screen:EquipmentItemPage` `ios-screen:AddEquipmentPage` `ios-screen:CarsPage` `ios-screen:CarEditSheet` |
| Settings | OBD page with connection and live values | ✅ 1.1.0 | ✅ | UI per platform | FBBD-261 | `route:Obd` `ios-screen:ObdPage` |
| Settings | Spoken coach settings; phone video setting; About and credits | ✅ | ✅ | UI per platform | FBBD-98, FBBD-88, FBBD-79 | `route:CoachSettings` `route:About` `log-android:CoachPrefs` `ios-screen:CoachSettingsPage` `ios-screen:AboutPage` `ios-screen:VideoPage` |
| Settings | First-run setup / Run setup again | ✅ | ✅ TF 1.26.0: checklist with Android's four steps; iPhone opens on it until Done, iPad (review only) never at launch, as the Android tablet | Steps, texts, done rules and when the app opens on it shared (setup commonMain FirstRunSetup); permission dialogs and drawing per platform | FBBD-371 | `route:Setup` `log-android:SetupRepo` `ios-screen:FirstRunPage` |
| Settings | RaceBox diagnostics (live values, read-only queries, calibration); camera test | ✅ ≤0.3.1, 1.5.1 | 🟣 TF 1.25.0 (#471): simulator tests only (no Bluetooth or camera there), check on a real RaceBox and camera pending; iOS part 2 of the camera test writes no file and runs no encoder | Shared: probe and screen text (racebox commonMain RaceBoxDiagnostics, RaceBoxDiagnosticsText), camera test text and log lines (recorder commonMain CameraTestText), FBBD-371; Bluetooth, camera, files and drawing per platform | FBBD-89, FBBD-278, FBBD-371 | `route:CameraTest` `route:DiagnosticsPattern` `log-android:CameraTest` `log-ios:CameraTest` `ios-screen:DiagnosticsPage` `ios-screen:RaceBoxDiagnosticsPage` `ios-screen:CameraTestPage` |
| Settings | In-app updater from GitHub Releases | ✅ | ➖ n/a TestFlight | Platform | FBBD-73 |  |
| Diagnostics | Continuous debug log, share as one zip; crash capture | ✅ ≤0.3.1 | ✅ TF 1.14+ | Writer per platform, same line format | FBBD-146, FBBD-339 | `log-android:App` `log-android:Crash` `log-android:DebugLogs` `log-android:Log` `log-ios:App` `log-ios:Crash` `log-ios:DebugLogs` |
| Diagnostics | Log lines: session start/stop and events, GNSS seconds, equipment reach, load, quality report at stop | ✅ | ✅ session start/stop, events, GNSS seconds, heart rate, equipment reach from the shared core (#422); quality report at stop shared (FBBD-365); load line from the shared LoadLine (CPU, footprint, threads; no GC on iOS; TF 1.25.0, #466) | Shared (recorder commonMain, #422; logQuality analysis commonMain, FBBD-365; LoadLine); samplers per platform | FBBD-193, FBBD-280, FBBD-281, FBBD-356, FBBD-359, FBBD-365 |  |
| Diagnostics | Log lines: coach cues, audio route, output delay | ✅ | ✅ TF 1425, same tags and text as Android | Coach lines shared (CoachRun, FBBD-357); audio route and output delay lines per platform | FBBD-354, FBBD-357 | `log-android:CoachService` `log-android:CoachLink` `log-ios:CoachService` |
| Look | Pitwall look for the whole app | ⚪ planned parked by Arek | ⚪ planned parked | — | FBBD-317 |  |
| Look | App shell: bottom bar Drive, Sessions, Car, Settings | ✅ | ✅ | Twice (Compose / SwiftUI); iOS keeps a "Not implemented yet" page for screens still to port | | `ios-screen:RootView` `ios-screen:NotImplementedPage` |
| Look | Tablet: session list beside the session, list button hides it and shows it again | ✅ 1.25.0 (FBBD-399, #464) | ✅ iPad, TF 1.25.0 (#464) | Twice (Compose / SwiftUI) | FBBD-40 |  |

## Screens
| Screen | List | Order (Android) | iOS | Jira |
|---|---|---|---|---|
| Top-level navigation (phone bottom bar, tablet rail; tablets have no Drive) | `list:top-level` | Drive, Sessions, Car, Settings | ✅ one tab bar on iPhone and iPad; iPad without Drive, as Android tablets | FBBD-394 |
| Session tabs | `list:session-tabs` | Summary, Compare, Map, Laps, Corners, Car, Video | ✅ same tabs; Video after Car only for a session with video, on both (FBBD-392) | FBBD-110, FBBD-392 |
