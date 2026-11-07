import BetterDriverCore
import Foundation

/// The coach's voice check while the phone camera records sound (FBBD-466). Away from a saved track the
/// coach never speaks, so a road drive could not show that the video's sound goes on after a phrase.
/// With the coach on and the camera recording, the sample cue is said [first] seconds after START and
/// then every [every] seconds, until the recording is at a saved track, where the coach speaks itself.
/// Each one is logged with the audio session's decision and, 2 s later, the camera's sound
/// ("AudioSession" lines).
struct VoiceCheck {
    static let first: TimeInterval = 30
    static let every: TimeInterval = 120

    private(set) var said = 0
    private var next: TimeInterval = VoiceCheck.first

    /// Whether to say the check now, [elapsed] seconds into the recording; true moves the next one on.
    mutating func due(elapsed: TimeInterval, atSavedTrack: Bool, speaking: Bool) -> Bool {
        guard !atSavedTrack, !speaking, elapsed >= next else { return false }
        said += 1
        next = elapsed + Self.every
        return true
    }
}

/// One voice check phrase: its start and end go to the log.
final class VoiceCheckSpeech: NSObject, CoachSpeech {
    private let number: Int

    init(number: Int) { self.number = number }

    func started(route: String, device: String, clip: Bool) {
        AppLog.i("VoiceCheck", "check \(number) started on \(route) (\(device))")
    }

    func ended(end: String) {
        AppLog.i("VoiceCheck", "check \(number) ended: \(end)")
    }
}
