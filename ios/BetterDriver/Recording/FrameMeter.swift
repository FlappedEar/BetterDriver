import QuartzCore
import UIKit

/// Counts the track screen's frames while a session records (FBBD-376), as Android's JankStats does:
/// a display link on the main thread, each frame's time the gap since the one before, janky when it
/// took more than twice the screen's refresh (JankStats' default), so a busy main thread shows as
/// late frames. Every 30 s the session logs the stretch (LiveSession.frame), and the app log gets it
/// too. In the background the screen draws nothing: counting stops there and starts afresh after.
@MainActor
final class FrameMeter {
    private let session: LiveSession
    private var link: CADisplayLink?
    private var last: CFTimeInterval?
    private var observers: [NSObjectProtocol] = []

    init(session: LiveSession) {
        self.session = session
    }

    func start() {
        guard link == nil else { return }
        let target = FrameTarget { [weak self] link in
            MainActor.assumeIsolated { self?.tick(link) }
        }
        let l = CADisplayLink(target: target, selector: #selector(FrameTarget.tick(_:)))
        l.add(to: .main, forMode: .common)
        link = l
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.pause(true) }
            },
            center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.pause(false) }
            },
        ]
    }

    /// Stops counting and logs the frames since the last stretch; before the session stops.
    func stop() {
        link?.invalidate()
        link = nil
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers = []
        if let text = session.flushFrames() { AppLog.i("Frames", text) }
    }

    private func pause(_ paused: Bool) {
        link?.isPaused = paused
        last = nil
    }

    private func tick(_ link: CADisplayLink) {
        let now = link.timestamp
        defer { last = now }
        guard let last else { return }
        let took = now - last
        let refresh = link.duration > 0 ? link.duration : 1.0 / 60
        let text = session.frame(
            startNanos: Int64(last * 1e9),
            durationNanos: Int64(took * 1e9),
            jank: took > 2 * refresh
        )
        if let text { AppLog.i("Frames", text) }
    }
}

/// CADisplayLink holds its target strongly; this keeps the meter out of that cycle.
private final class FrameTarget: NSObject {
    let onFrame: (CADisplayLink) -> Void
    init(_ onFrame: @escaping (CADisplayLink) -> Void) { self.onFrame = onFrame }
    @objc func tick(_ link: CADisplayLink) { onFrame(link) }
}
