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
    let trackName: String?
    let laps: [LapRow]
    let bestLap: String?
    let theoreticalBest: String?
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
        let review = try library.review(id: id)
        let laps = review.laps.map { lap in
            LapRow(
                number: Int(lap.number),
                time: CoreBridge.lapTime(millis: millis(lap.seconds)),
                delta: lap.fastest || !lap.counts ? nil : CoreBridge.delta(seconds: lap.deltaSeconds),
                fastest: lap.fastest,
                counts: lap.counts
            )
        }
        return SessionSummary(
            trackName: review.track?.name,
            laps: laps,
            bestLap: review.bestLapSeconds.map { CoreBridge.lapTime(millis: millis($0.doubleValue)) },
            theoreticalBest: review.theoreticalBestSeconds.map { CoreBridge.lapTime(millis: millis($0.doubleValue)) }
        )
    }

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

    /// Adds the session; with one of its id here, [replace] picks which stays. True when added.
    static func accept(_ received: ReceivedImport, replace: Bool) throws -> Bool {
        try library.accept(received: received.bundle, replace: replace) == ImportOutcome.added
    }

    static func discard(_ received: ReceivedImport) { library.discard(received: received.bundle) }

    static func clearStaging() { _ = library.clearStaging() }
}
