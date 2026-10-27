import ActivityKit
import Foundation

/// The Live Activity of a running recording (UI-284 A), shared by the app and its widget extension:
/// lap, delta and best on the lock screen, with the time recorded counting on its own.
struct RecordingAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// The current lap's number; nil before the car is timed at a track.
        var lap: Int?
        /// The delta as the track screen shows it ("−0.34"); nil without one.
        var delta: String?
        /// The delta is a gain (green); otherwise a loss (red).
        var gain: Bool
        /// The session's best lap ("1:52.41"); nil before the first timed lap.
        var best: String?
    }

    /// When the recording started, for the clock.
    var startedAt: Date
}
