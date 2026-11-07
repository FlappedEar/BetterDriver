import Foundation
import UIKit

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

/// When the phone camera may start its next file after a file failed on its own (FBBD-441). Until
/// 1.30.0 the camera stopped for the rest of the session after 3 failed files, and Arek restarted the
/// drive 4 times on 3 Nov to get video back. Now it never gives up: the first file after a failure
/// starts at once, each further failure in a row waits twice as long (2, 4, 8 s, at most 30 s), and
/// a file that records for [steadySeconds] ends the run of failures. Times are seconds on any clock.
struct PhoneFileRetry: Equatable {
    static let steadySeconds = 60.0
    static let longestWait = 30.0

    /// Failed files since the last steady one.
    private(set) var inARow = 0
    /// All failed files in the session, for the log.
    private(set) var total = 0
    private var notBefore = -Double.infinity

    /// A file that started at [startedAt] failed at [now].
    mutating func failed(startedAt: Double, now: Double) {
        if now - startedAt >= Self.steadySeconds { inARow = 0 }
        inARow += 1
        total += 1
        notBefore = now + wait
    }

    /// How long the next file waits after the latest failure.
    var wait: Double {
        inARow <= 1 ? 0 : min(pow(2, Double(inARow - 1)), Self.longestWait)
    }

    /// Whether a new file may start at [now].
    func mayOpen(at now: Double) -> Bool { now >= notBefore }
}

/// How long one phone video file records before the next one starts (FBBD-468, audit 7 Nov BD-T02).
/// The files are not fragmented (FBBD-441), so an MP4 gets its index only when it is finished: a
/// killed app or a crash leaves the file it was writing unplayable. A new file every [seconds] bounds
/// that loss to one file's length instead of the whole session. Review, the data video and the VBO
/// export already join a session's files on the session clock (Android starts a new file when one is
/// full too), so a file change is no gap in the session's video apart from a frame or two.
enum PhoneFileLength {
    static let seconds = 300.0

    /// Whether a file that started at [startedAt] is full at [now]; [limit] for the tests.
    static func full(startedAt: Double, now: Double, limit: Double = seconds) -> Bool {
        now - startedAt >= limit
    }
}

/// Background time while a phone video file is finished (FBBD-468, audit 7 Nov BD-T08): its MP4 gets
/// its index only when finishWriting answers, and STOP or going to the background (which ends the file)
/// may be followed at once by the app being suspended. [begin] and [end] are UIApplication's, or a
/// test's; both run on the main queue, in the order asked. The task ends once, at the answer or when
/// iOS says the time is up.
final class BackgroundTime {
    typealias Begin = (_ name: String, _ expired: @escaping () -> Void) -> UIBackgroundTaskIdentifier
    typealias End = (UIBackgroundTaskIdentifier) -> Void

    static let shared = BackgroundTime(
        begin: { name, expired in UIApplication.shared.beginBackgroundTask(withName: name, expirationHandler: expired) },
        end: { UIApplication.shared.endBackgroundTask($0) }
    )

    private let beginTask: Begin
    private let endTask: End

    init(begin: @escaping Begin, end: @escaping End) {
        beginTask = begin
        endTask = end
    }

    /// Asks for background time named [name]; call the returned closure when the work is done (any queue).
    func hold(_ name: String) -> () -> Void {
        let lock = NSLock()
        var task = UIBackgroundTaskIdentifier.invalid
        var done = false
        // On the main queue: ends the task if it is still held.
        let finish = { [endTask] in
            lock.lock()
            let held = task
            task = .invalid
            done = true
            lock.unlock()
            if held != .invalid { endTask(held) }
        }
        DispatchQueue.main.async { [beginTask] in
            lock.lock()
            let finished = done
            lock.unlock()
            guard !finished else { return }
            let id = beginTask(name) { finish() }
            lock.lock()
            task = id
            lock.unlock()
        }
        return { DispatchQueue.main.async { finish() } }
    }
}
