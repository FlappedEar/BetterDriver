import AVFoundation

/// Who uses the app's one audio session (FBBD-466): the coach's voice gives it back to the other apps
/// when a phrase ends, but never while the phone camera records from the microphone through it. Apple:
/// deactivating a session with running audio input stops that input, so the video's sound would stop
/// after the coach's first phrase. The camera says when its microphone starts and stops; the session
/// is given back only when neither the coach nor the camera uses it.
final class AudioSessionUse {
    static let shared = AudioSessionUse {
        try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private let lock = NSLock()
    private var microphones = 0
    /// What the recording's microphone has taken so far, for the log after each phrase; set by the camera.
    private var report: (() -> String)?
    private let deactivate: () throws -> Void

    /// [deactivate] gives the session back; tests pass their own.
    init(deactivate: @escaping () throws -> Void) {
        self.deactivate = deactivate
    }

    /// Whether a recording takes sound from the microphone through the session now.
    var recording: Bool {
        lock.lock()
        defer { lock.unlock() }
        return microphones > 0
    }

    /// A recording's microphone started taking sound through the session.
    /// [report] says what it has recorded, for the line logged after each coach phrase.
    func microphoneStarted(report: (() -> String)? = nil) {
        lock.lock()
        microphones += 1
        if let report { self.report = report }
        lock.unlock()
    }

    /// A recording's microphone stopped: the session can be given back again.
    func microphoneStopped() {
        lock.lock()
        microphones = max(microphones - 1, 0)
        if microphones == 0 { report = nil }
        lock.unlock()
    }

    /// Gives the session back to the other apps, unless a recording's microphone uses it; false when it
    /// was kept, or the deactivation failed.
    @discardableResult
    func giveBack() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard microphones == 0 else { return false }
        do {
            try deactivate()
            return true
        } catch {
            AppLog.w(Self.tag, "session not given back: \(error.localizedDescription)")
            return false
        }
    }

    /// A coach phrase ended: the session goes back to the other apps unless the camera records sound.
    /// Logged per phrase, with what the camera's sound has taken [checkAfter] later, so a drive log
    /// shows the video's sound going on after each phrase.
    func phraseEnded(checkAfter: TimeInterval = 2) {
        let gaveBack = giveBack()
        lock.lock()
        let held = microphones > 0
        let report = self.report
        lock.unlock()
        let session = gaveBack ? "given back" : held ? "kept for the camera's microphone" : "not given back"
        AppLog.i(Self.tag, "coach phrase ended: session \(session)")
        guard held, let report else { return }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + checkAfter) {
            AppLog.i(Self.tag, "\(Int(checkAfter)) s after the coach phrase: camera \(report())")
        }
    }

    private static let tag = "AudioSession"

    /// As [giveBack], tried up to [tries] times, [wait] apart, off the calling queue: deactivating
    /// blocks, and fails, while audio still runs. Stops trying once a recording keeps the session.
    func giveBackSoon(tries: Int = 10, wait: TimeInterval = 0.3) {
        DispatchQueue.global(qos: .utility).async {
            for _ in 0 ..< tries {
                if self.recording || self.giveBack() { return }
                Thread.sleep(forTimeInterval: wait)
            }
        }
    }
}
