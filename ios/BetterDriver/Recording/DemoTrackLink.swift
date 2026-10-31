import BetterDriverCore
import Foundation

/// The Demo track receiver (FBBD-373), as Android's (FBBD-233): a made-up car driving what Settings ›
/// Equipment › Demo track › Drives says, the demo circuit or Arek's laps built into the app or a session
/// again, at 25 fixes a second. The shared core picks and makes the fixes (DemoTrackCore, ScriptFeed);
/// this only ticks the clock.
final class DemoTrackLink {
    private let feed: ScriptFeed
    private var timer: Timer?

    /// Reads the chosen drive and saves its track, so the recording's timing finds it at the first fix.
    init(sink: SampleSink) throws {
        let demo = Bundle.main.resourceURL?.appendingPathComponent("demo").path ?? ""
        feed = try DemoTrackCore.shared.feed(
            sink: sink,
            filesDir: AppFolders.base.path,
            sessionsDir: AppFolders.sessions.path,
            tracksDir: AppFolders.tracks.path,
            demoDir: demo
        )
    }

    /// Call on the main queue.
    func start() {
        guard timer == nil else { return }
        feed.start()
        _ = feed.advance()
        // 25 ticks a second; a late tick sends every fix due by then.
        timer = Timer.scheduledTimer(withTimeInterval: 0.04, repeats: true) { [weak self] _ in
            _ = self?.feed.advance()
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }
}
