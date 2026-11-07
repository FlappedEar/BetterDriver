import AVFoundation
import Foundation

/// How much phone video still fits on the phone (FBBD-467, audit BD-T06): the recorder warns only at
/// 200 MiB free, about a minute of 1080p · 60, so the START sheet says how long the chosen mode fits
/// and the track screen warns while minutes are left. Worked out from the bitrate the camera sets
/// (PhoneCamera.videoSettings, H.264, the larger of its two codecs) and the sound, not measured.
enum VideoStorage {
    /// The sound's share, high: AAC as the phone records it stays under this.
    static let audioBitsPerSecond = 192_000
    /// Below this many minutes of the mode the track screen warns.
    static let warnMinutes = 10
    /// Below this many minutes the START sheet says it in amber: less than two 15-minute sessions.
    static let shortMinutes = 30

    /// Bytes one minute of [mode] takes, video and sound.
    static func bytesPerMinute(_ mode: PhoneVideoMode) -> Int64 {
        let settings = PhoneCamera.videoSettings(codec: .h264, width: mode.size.width, height: mode.size.height, fps: mode.fps)
        let video = (settings[AVVideoCompressionPropertiesKey] as? [String: Any])?[AVVideoAverageBitRateKey] as? Int ?? 0
        return Int64(video + audioBitsPerSecond) * 60 / 8
    }

    /// Whole minutes of [mode] that fit in [freeBytes].
    static func minutesLeft(freeBytes: Int64, mode: PhoneVideoMode) -> Int {
        Int(max(freeBytes, 0) / bytesPerMinute(mode))
    }

    /// The bytes this phone has free for sessions and video, as Settings' Storage row reads them.
    static func freeBytes() -> Int64 {
        let home = URL(fileURLWithPath: NSHomeDirectory())
        let values = try? home.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? 0
    }

    /// The START sheet's line under the video mode: "412 GB free · about 39 h of video", and whether
    /// it is short (amber).
    static func startLine(freeBytes: Int64, mode: PhoneVideoMode) -> (text: String, short: Bool) {
        let minutes = minutesLeft(freeBytes: freeBytes, mode: mode)
        let gb = Int((Double(freeBytes) / 1_000_000_000).rounded())
        let fits = minutes >= 120 ? "about \(minutes / 60) h of video" : "about \(minutes) min of video"
        return ("\(gb) GB free · \(fits)", minutes < shortMinutes)
    }

    /// The track screen's alert detail while recording, when under [warnMinutes] of [mode] are left.
    static func warning(freeBytes: Int64, mode: PhoneVideoMode) -> String? {
        let minutes = minutesLeft(freeBytes: freeBytes, mode: mode)
        return minutes < warnMinutes ? "About \(minutes) min of video left" : nil
    }
}
