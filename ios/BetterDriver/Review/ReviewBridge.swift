import Foundation
import UniformTypeIdentifiers
import BetterDriverCore

extension UTType {
    /// A session as one file (FBBD-23), the same on Android and iOS.
    static let bdsession = UTType(exportedAs: "com.flappedear.betterdriver.session")
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
    let trackName: String?
    let laps: [LapRow]
    let bestLap: String?
    let theoreticalBest: String?
    let line: WhereToFindTime?
    /// The fastest lap against the second fastest, as Compare opens on Android.
    let compare: LapCompare?
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
    let laps: Int
    let sessions: Int
    /// In track order.
    let corners: [LineCorner]
    /// Most time first, corners with nothing to find last (LineUi.ranked).
    let ranked: [LineCorner]
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
            trackName: review.track?.name,
            laps: laps,
            bestLap: review.bestLapSeconds.map { CoreBridge.lapTime(millis: millis($0.doubleValue)) },
            theoreticalBest: review.theoreticalBestSeconds.map { CoreBridge.lapTime(millis: millis($0.doubleValue)) },
            line: review.line.map(whereToFindTime),
            compare: reviewed.defaultCompare().map(lapCompare)
        )
    }

    /// The fastest lap against lap [index] of [summary]'s session; nil without a fastest lap.
    static func compare(_ summary: SessionSummary, lapIndex index: Int) -> LapCompare? {
        summary.reviewed.compare(otherIndex: Int32(index)).map(lapCompare)
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
            laps: Int(ui.laps),
            sessions: Int(ui.sessions),
            corners: ui.corners.map(corner),
            ranked: ui.ranked.map(corner)
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

    static func clearStaging() { _ = library.clearStaging() }
}
