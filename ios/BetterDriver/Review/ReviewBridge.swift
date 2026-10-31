import Foundation
import SwiftUI
import UniformTypeIdentifiers
import BetterDriverCore

extension UTType {
    /// A session as one file (FBBD-23), the same on Android and iOS.
    static let bdsession = UTType(exportedAs: "com.flappedear.betterdriver.session")
    /// A Racelogic VBO log (FBBD-164), from Racelogic loggers, Circuit Tools, RaceChrono or Android.
    static let vbo = UTType(importedAs: "com.racelogic.vbo")
}

/// One session in the list.
struct SessionRow: Identifiable, Hashable {
    let id: String
    let start: Date
    let track: String
    let car: String
    let durationSeconds: Double?
}

/// One timed lap of a session's review.
struct LapRow: Identifiable, Hashable {
    /// Its index among the session's timed laps, for Compare.
    let index: Int
    let number: Int
    let time: String
    /// Behind the fastest lap, for example +1.24; nil for the fastest and for laps that do not count.
    let delta: String?
    let fastest: Bool
    let counts: Bool
    var id: Int { number }
}

/// What the review shows of one session, worked out by the shared core as on Android.
struct SessionSummary {
    /// The core's reviewed session, kept to compare laps without reading the session again.
    fileprivate let reviewed: ReviewedSession
    let id: String
    let trackName: String?
    let laps: [LapRow]
    let bestLap: String?
    let theoreticalBest: String?
    let line: WhereToFindTime?
    /// The fastest lap against the second fastest, as Compare opens on Android.
    let compare: LapCompare?
    /// Android's analysis screen model for Corners and Car, the same lap compared.
    let screen: AnalysisUi?
    /// The whole drive on the map when the session has no laps (FBBD-342); nil with laps.
    let drive: DriveSummary?
    /// The whole session's acceleration runs and G-forces, laps or not, as Android's summary shows them.
    let whole: WholeSession
}

/// A session with no laps as Android's DriveCards show it (FBBD-342): its figures, its route
/// coloured by speed on the Map tab's scale, and its speed along the drive.
struct DriveSummary {
    let figures: DriveFigures
    let runs: [[DriveDot]]
    let speed: [[ChartDot]]
    let distanceMeters: Double
    /// The speed scale the route is coloured with, as the legend labels it; nil without speed.
    let scale: (low: Double, high: Double)?
    /// Width over height of the route, as Android sizes the map.
    let aspect: CGFloat
}

/// A point of the drive's route: metres east and north, metres into the drive, speed and its colour.
struct DriveDot {
    let east: Double
    let north: Double
    let meters: Double
    let speed: Double?
    let color: Color
}

/// A point of a chart: metres from the start/finish line and a value.
struct ChartDot: Hashable {
    let meters: Double
    let value: Double
}

/// A corner on the lap's distance axis; it may wrap past the line.
struct Band: Hashable {
    let name: String
    let start: Double
    let end: Double

    func contains(_ m: Double) -> Bool { end >= start ? (start...end).contains(m) : (m >= start || m <= end) }
}

/// The Compare tab (FBBD-102): lap [otherNumber] against the fastest lap by distance, as Android
/// draws it. Each series is runs of continuous coverage; a GPS gap starts a new run.
struct LapCompare: Hashable {
    let referenceNumber: Int
    let otherNumber: Int
    let otherIndex: Int
    let lengthMeters: Double
    /// The other lap's time minus the fastest's, seconds; positive: behind.
    let delta: [[ChartDot]]
    let referenceSpeed: [[ChartDot]]
    let otherSpeed: [[ChartDot]]
    let corners: [Band]
}

/// What the pedals do on a stretch of a lap, for its colour (UI-165 C).
enum Pedal { case braking, coasting, accelerating }

/// A point of a drawn line, metres east and north of the map's origin.
struct MapDot: Hashable {
    let east: Double
    let north: Double
    let pedal: Pedal?
}

/// One corner of "Where to find time" (FBBD-306), as Android's Map tab shows it.
struct LineCorner: Identifiable, Hashable {
    let tag: String
    let gainSeconds: Double
    let gain: String
    let todo: [String]
    let targetFrom: String
    let reference: [MapDot]
    let target: [MapDot]
    let roadLeft: [MapDot]
    let roadRight: [MapDot]
    let anchor: MapDot?
    var id: String { tag }
}

/// "Where to find time": the best lap against the target through each corner.
struct WhereToFindTime {
    let bestLap: String
    let bestLapNumber: Int
    let total: String
    /// [total] in seconds, for the link on the next-session card (UI-190 B).
    let totalSeconds: Double
    let laps: Int
    let sessions: Int
    /// In track order.
    let corners: [LineCorner]
    /// Most time first, corners with nothing to find last (LineUi.ranked).
    let ranked: [LineCorner]
    /// The perfect lap to drive in 3D (FBBD-308); nil when a corner has no line or road to draw.
    let scene: PerfectLapScene?
}

/// The one place the review talks to the Kotlin core (ReviewLibrary), so Kotlin types stay out
/// of the views (FBBD-331). Sessions and tracks live in the app's Application Support folder.
enum ReviewBridge {
    static let library: ReviewLibrary = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return ReviewLibrary(base: base.path)
    }()

    static func sessions() -> [SessionRow] {
        library.sessions().map { s in
            SessionRow(
                id: s.id,
                start: Date(timeIntervalSince1970: Double(s.info.startUtcMillis) / 1000),
                track: s.info.track,
                car: s.info.car,
                durationSeconds: s.durationMicros.map { Double($0.int64Value) / 1_000_000 }
            )
        }
    }

    /// The session's Data quality card (FBBD-183, FBBD-365), as Android's; nil when it has no report.
    /// Makes the report the first time: not on the main thread.
    static func quality(id: String) -> QualityUi? {
        library.quality(id: id)
    }

    /// Each session's row as Android's (UI-139 C): its detail line and best lap. Reads sessions: not on the main thread.
    static func sessionRows() -> [String: SessionRowFacts] {
        library.sessionRows()
    }

    static func summary(id: String) throws -> SessionSummary {
        let reviewed = try library.review(id: id)
        let review = reviewed.review
        let laps = review.laps.enumerated().map { i, lap in
            LapRow(
                index: i,
                number: Int(lap.number),
                time: CoreBridge.lapTime(millis: millis(lap.seconds)),
                delta: lap.fastest || !lap.counts ? nil : CoreBridge.delta(seconds: lap.deltaSeconds),
                fastest: lap.fastest,
                counts: lap.counts
            )
        }
        return SessionSummary(
            reviewed: reviewed,
            id: id,
            trackName: review.track?.name,
            laps: laps,
            bestLap: review.bestLapSeconds.map { CoreBridge.lapTime(millis: millis($0.doubleValue)) },
            theoreticalBest: review.theoreticalBestSeconds.map { CoreBridge.lapTime(millis: millis($0.doubleValue)) },
            line: review.line.map(whereToFindTime),
            compare: reviewed.defaultCompare().map(lapCompare),
            screen: reviewed.defaultScreen(),
            drive: reviewed.drive.map(driveSummary),
            whole: reviewed.whole
        )
    }

    /// [drive] with each point coloured as Android's LapMapView colours speed.
    private static func driveSummary(_ drive: DriveMap) -> DriveSummary {
        let speeds = drive.map.runs.joined().compactMap { $0.speed }
        let range = MapUiKt.speedRange(speeds: speeds)
        let runs = drive.map.runs.map { run in
            run.map { p in
                let color = range.flatMap { r in
                    p.speed.map { Color(argb: MapUiKt.scaleColor(fraction: r.fraction(value: $0.doubleValue), stops: MapUiKt.SpeedScaleColors)) }
                } ?? Palette.onSurfaceVariant
                return DriveDot(east: p.eastMeters, north: p.northMeters, meters: p.distanceMeters, speed: p.speed?.doubleValue, color: color)
            }
        }
        return DriveSummary(
            figures: DriveUiKt.driveFigures(drive: drive),
            runs: runs,
            speed: drive.speed.map { $0.map { ChartDot(meters: $0.distanceMeters, value: $0.value) } },
            distanceMeters: drive.distanceMeters,
            scale: range.map { (low: $0.low, high: $0.high) },
            aspect: CGFloat(MapUiKt.mapAspect(map: drive.map))
        )
    }

    /// The summary's coaching history card (FBBD-180, FBBD-364): keeps the session's record in the
    /// coaching history, as Android, and words what came of its focus. Reads files: not on the main thread.
    static func coaching(_ summary: SessionSummary) -> CoachingCardUi {
        library.coaching(reviewed: summary.reviewed, id: summary.id)
    }

    /// The fastest lap against lap [index] of [summary]'s session; nil without a fastest lap.
    static func compare(_ summary: SessionSummary, lapIndex index: Int) -> LapCompare? {
        summary.reviewed.compare(otherIndex: Int32(index)).map(lapCompare)
    }

    /// The fastest lap against lap [index] on video, as Android's lap review; nil without both laps.
    static func videoReview(_ summary: SessionSummary, lapIndex index: Int) -> LapVideoReview? {
        library.videoReview(reviewed: summary.reviewed, id: summary.id, otherIndex: Int32(index))
    }

    /// Android's analysis screen with lap [index] compared, for Corners and Car.
    /// The laps of the newest sessions at each saved track, for Compare laps (FBBD-379), as Android's
    /// lap picker. Replays every session not read yet: never on the main thread.
    static func lapPicker() -> [PickerTrack] { library.lapPicker() }

    /// Laps [a] against [b] (keys "session:lap") on video, as Android's lap review opens two picked laps.
    static func pickedReview(_ tracks: [PickerTrack], a: String, b: String) -> LapVideoReview? {
        LapPickerKt.pickedReview(tracks: tracks, a: a, b: b)
    }

    /// Every counted pass through corner [cornerId] with its traces and video, as Android's Corner
    /// laps screen (FBBD-93, FBBD-153). Reads the session's laps again: never on the main thread.
    static func everyPass(_ summary: SessionSummary, cornerId: String) -> CornerLapsUi? {
        library.everyPass(reviewed: summary.reviewed, id: summary.id, cornerId: cornerId)
    }

    /// Timed laps [a] and [b] side by side on video, as Android opens two passes of a corner.
    /// The corners Where time goes flags for the spoken coach, and those flagged at the track (FBBD-363).
    static func coachFlags(_ summary: SessionSummary) -> CoachFlags {
        library.coachFlags(reviewed: summary.reviewed, id: summary.id)
    }

    /// Flags or unflags [cornerId] for the coach's next session at the track; the flags after.
    static func toggleCoachFlag(_ summary: SessionSummary, cornerId: String) -> CoachFlags {
        library.toggleCoachFlag(reviewed: summary.reviewed, id: summary.id, cornerId: cornerId)
    }

    static func lapsOnVideo(_ summary: SessionSummary, a: Int, b: Int) -> LapVideoReview? {
        library.lapsOnVideo(reviewed: summary.reviewed, id: summary.id, aIndex: Int32(a), bIndex: Int32(b))
    }

    /// Where the lap review of a corner's passes starts: a little before its entry, as a share of the lap.
    static func reviewStart(_ summary: SessionSummary, cornerId: String) -> Double? {
        summary.reviewed.reviewStart(cornerId: cornerId)?.doubleValue
    }

    static func screen(_ summary: SessionSummary, lapIndex index: Int) -> AnalysisUi? {
        summary.reviewed.screen(otherIndex: Int32(index))
    }

    private static func lapCompare(_ c: ReviewCompare) -> LapCompare {
        func runs(_ r: [[ChartPoint]]) -> [[ChartDot]] { r.map { $0.map { ChartDot(meters: $0.distanceMeters, value: $0.value) } } }
        return LapCompare(
            referenceNumber: Int(c.referenceNumber),
            otherNumber: Int(c.otherNumber),
            otherIndex: Int(c.otherIndex),
            lengthMeters: c.lengthMeters,
            delta: runs(c.delta),
            referenceSpeed: runs(c.referenceSpeed),
            otherSpeed: runs(c.otherSpeed),
            corners: c.corners.map { Band(name: $0.name, start: $0.startMeters, end: $0.endMeters) }
        )
    }

    private static func whereToFindTime(_ ui: LineUi) -> WhereToFindTime {
        WhereToFindTime(
            bestLap: CoreBridge.lapTime(millis: millis(ui.bestLapSeconds)),
            bestLapNumber: Int(ui.bestLapNumber),
            total: twoDecimals(ui.totalGainSeconds),
            totalSeconds: ui.totalGainSeconds,
            laps: Int(ui.laps),
            sessions: Int(ui.sessions),
            corners: ui.corners.map(corner),
            ranked: ui.ranked.map(corner),
            scene: ui.scene
        )
    }

    private static func corner(_ c: LineCornerUi) -> LineCorner {
        LineCorner(
            tag: c.tag,
            gainSeconds: c.gainSeconds,
            gain: twoDecimals(c.gainSeconds),
            todo: c.todo,
            targetFrom: c.targetFrom,
            reference: c.reference.map(dot),
            target: c.target.map(dot),
            roadLeft: c.roadLeft.map(dot),
            roadRight: c.roadRight.map(dot),
            anchor: c.anchor.map(dot)
        )
    }

    private static func dot(_ d: LineDot) -> MapDot {
        var pedal: Pedal?
        if d.pedal == PedalState.braking {
            pedal = .braking
        } else if d.pedal == PedalState.accelerating {
            pedal = .accelerating
        } else if d.pedal == PedalState.coasting {
            pedal = .coasting
        }
        return MapDot(east: d.eastMeters, north: d.northMeters, pedal: pedal)
    }

    /// Seconds with two decimals, as Android shows them (core: fixed).
    private static func twoDecimals(_ v: Double) -> String { library.twoDecimals(value: v) }

    private static func millis(_ seconds: Double) -> Int64 { Int64((seconds * 1000).rounded()) }
}

/// A bundle unpacked and checked, waiting to be added.
struct ReceivedImport {
    fileprivate let bundle: ReceivedBundle
    var sessionId: String { bundle.session.sessionId }
}

extension ReviewBridge {
    /// Unpacks and checks the bundle at [url]; [progress] gets bytes read and the file's size.
    static func receive(_ url: URL, progress: @escaping (Int64, Int64) -> Void) throws -> ReceivedImport {
        let bundle = try library.receive(path: url.path) { read, total in
            progress(read.int64Value, total.int64Value)
        }
        return ReceivedImport(bundle: bundle)
    }

    static func exists(_ received: ReceivedImport) -> Bool { library.exists(received: received.bundle) }

    /// The file's track has other lines or corners than the saved one of its id.
    static func trackDiffers(_ received: ReceivedImport) -> Bool { library.trackDiffers(received: received.bundle) }

    static func trackName(_ received: ReceivedImport) -> String { received.bundle.session.track?.displayName ?? "" }

    /// Adds the session; with one of its id here, [replace] picks which stays, and [replaceTrack]
    /// whether the file's track replaces the saved one. True when added.
    static func accept(_ received: ReceivedImport, replace: Bool, replaceTrack: Bool) throws -> Bool {
        try library.accept(received: received.bundle, replace: replace, replaceTrack: replaceTrack) == ImportOutcome.added
    }

    static func discard(_ received: ReceivedImport) { library.discard(received: received.bundle) }

    /// A file from another app (VBO, RaceChrono CSV) rather than a BetterDriver session.
    static func isForeign(_ url: URL) -> Bool { url.pathExtension.lowercased() != "bdsession" }

    /// Imports the VBO or RaceChrono CSV file at [url] as a new session, as Android does (FBBD-164);
    /// [progress] gets bytes read and the file's size.
    static func importForeign(_ url: URL, progress: @escaping (Int64, Int64) -> Void) throws {
        _ = try library.importForeign(path: url.path) { read, total in
            progress(read.int64Value, total.int64Value)
        }
    }

    static func clearStaging() { _ = library.clearStaging() }

    /// Sessions the app was killed in the middle of get their recovery record, as on Android (FBBD-71,
    /// audit BD-05); one log line per session. Reads every chunk of those, so not on the main thread.
    static func recoverInterrupted() -> [String] { library.recoverInterrupted() }

    /// Deletes session [id] with its video; nil when done, else why not, as Android's card says it.
    static func delete(_ id: String) -> String? {
        switch library.delete(id: id) {
        case .deleted, .notFound: return nil
        case .recording: return "Recording, not deleted"
        default: return "Delete failed"
        }
    }

    /// The track day of sessions [ids] (FBBD-315), read and worded by the shared core as on Android.
    static func trackDay(_ ids: [String]) -> TrackDayState { library.trackDay(ids: ids) }

    /// Session [id] as a .bdsession to share, as Android's Share: its files, video included, and its
    /// track; [progress] gets bytes written and their total.
    static func share(_ id: String, progress: @escaping (Int64, Int64) -> Void = { _, _ in }) throws -> URL {
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        return URL(fileURLWithPath: try library.share(id: id, outRoot: out.path) { written, total in
            progress(written.int64Value, total.int64Value)
        })
    }

    /// What session [id] takes on this device, in bytes, and whether it has video.
    static func footprint(_ id: String) -> (bytes: Int64?, video: Bool) {
        let dir = AppFolders.sessions.appendingPathComponent(id, isDirectory: true)
        var bytes: Int64 = 0
        var video = false
        guard let files = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: [.fileSizeKey]) else {
            return (nil, false)
        }
        for case let url as URL in files {
            bytes += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
            if url.pathExtension.lowercased() == "mp4" { video = true }
        }
        return (bytes, video)
    }

    /// The UI tests' launch arguments (FBBD-331), in debug builds only: -freshInstall starts with no
    /// sessions or tracks, as a new install; -demoSessions adds the Android app's built-in demo
    /// sessions (Jastrząb, Silesia Ring) when they are not here yet; -demoCues coaches the Jastrząb one.
    static func prepareForUITests() {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        let base = URL(fileURLWithPath: library.sessionsRoot).deletingLastPathComponent()
        if arguments.contains("-freshInstall") {
            for dir in ["sessions", "tracks", "transfer", "coach"] {
                try? FileManager.default.removeItem(at: base.appendingPathComponent(dir))
            }
        }
        guard arguments.contains("-demoSessions"), library.sessions().isEmpty,
              let demo = Bundle.main.resourceURL?.appendingPathComponent("demo") else { return }
        // When Arek drove them, as Android names them (DemoReplay).
        var demos: [(String, Int64)] = [("jastrzab", 1_787_990_409_000), ("silesia-ring", 1_790_506_830_000)]
        // A second Jastrząb session an hour later the same day, for the track day (FBBD-315).
        if arguments.contains("-demoTrackDay") { demos.append(("jastrzab", 1_787_994_009_000)) }
        // -demoCues: the Jastrząb demo driven with the spoken coach on, for its Coach review (FBBD-99).
        let coached = arguments.contains("-demoCues")
        for (name, start) in demos {
            let replay = demo.appendingPathComponent("\(name).replay").path
            let track = demo.appendingPathComponent("\(name).track.json").path
            if coached && name == "jastrzab" {
                _ = try? library.addCoachedDemo(replayPath: replay, trackPath: track, startUtcMillis: start)
            } else {
                _ = try? library.addDemo(replayPath: replay, trackPath: track, startUtcMillis: start)
            }
        }
        // The demos with no saved track, as a road drive reads: no laps, the drive on the map (FBBD-342).
        if arguments.contains("-demoNoTrack") { try? FileManager.default.removeItem(at: base.appendingPathComponent("tracks")) }
        // Phone video in the demo sessions, for Video with data (FBBD-152).
        if arguments.contains("-demoVideo") { DataVideoDemo.add(sessionsRoot: library.sessionsRoot) }
        #endif
    }
}

extension ReviewBridge {
    /// [summary]'s session against the earlier sessions at its track, as Android's summary card
    /// (FBBD-122, FBBD-175); nil when it is at no track. Reads up to 60 sessions: never on the main thread.
    static func progress(_ summary: SessionSummary) -> SessionProgress? {
        library.progress(reviewed: summary.reviewed, id: summary.id)
    }
}
