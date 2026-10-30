import Foundation

/// What became of the camera's frames in one file (audit 30 Oct BD-01): every frame the camera gave,
/// those the file took, those dropped because the writer was not ready for them, and those the camera
/// dropped itself before they reached the app (late). Until now a frame the writer was not ready for
/// was skipped without a count, and a video_sync could name it although the file does not hold it.
struct PhoneFrameCounts: Equatable {
    var captured = 0
    var appended = 0
    var dropped = 0
    var late = 0

    enum Outcome: Equatable {
        /// In the file: only then may a video_sync name it.
        case appended
        /// The writer was not ready: counted, and not in the file.
        case dropped
        /// The writer refused it: the file has failed.
        case failed
    }

    /// One frame from the camera; [append] runs only when the writer is [ready].
    mutating func frame(ready: Bool, append: () -> Bool) -> Outcome {
        captured += 1
        guard ready else {
            dropped += 1
            return .dropped
        }
        guard append() else { return .failed }
        appended += 1
        return .appended
    }

    /// For the log line at the file's end.
    var text: String { "captured=\(captured) appended=\(appended) dropped=\(dropped) late=\(late)" }
}

/// The phone camera's files whose MP4 is still being finished after the file ended (audit 30 Oct
/// BD-02, FBBD-350 R1). AVAssetWriter.finishWriting runs on its own; until its answer the file has
/// no index and must not be offered for export or playback. When a session's last file is done,
/// [Notification.Name.sessionsChanged] is posted again, so the session list and its export menu read
/// the session's video anew. Thread-safe.
final class PhoneVideoFinishing {
    static let shared = PhoneVideoFinishing()

    private let lock = NSLock()
    private var pending: [String: Int] = [:]

    /// A file of session [sessionId] started finishing.
    func begin(sessionId: String) {
        lock.lock()
        pending[sessionId, default: 0] += 1
        lock.unlock()
    }

    /// A file of session [sessionId] is finished (or failed); true when it was that session's last.
    @discardableResult
    func end(sessionId: String) -> Bool {
        lock.lock()
        let left = max((pending[sessionId] ?? 1) - 1, 0)
        pending[sessionId] = left == 0 ? nil : left
        lock.unlock()
        guard left == 0 else { return false }
        DispatchQueue.main.async { NotificationCenter.default.post(name: .sessionsChanged, object: nil) }
        return true
    }

    /// Whether some file of session [sessionId] is still being finished.
    func busy(sessionId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return pending[sessionId] != nil
    }
}
