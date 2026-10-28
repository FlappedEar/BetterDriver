import ActivityKit
import Foundation

/// Runs the recording's Live Activity (UI-284 A) from the app: started with the session, updated
/// from the track screen's values at most once a second when they change, and at least every
/// [heartbeat] seconds, ended with it. Each update is stale after [staleAfter]: when the app dies
/// mid-session the card stops claiming a recording instead of counting on, and the next launch ends it.
@MainActor
final class RecordingActivity {
    private var activity: Activity<RecordingAttributes>?
    private var shown: RecordingAttributes.ContentState?
    private var sentAt = Date.distantPast

    /// Off until the app is stable (Arek, 2026-10-27): no card is started; leftovers still end at launch.
    static let enabled = false

    static let heartbeat: TimeInterval = 5
    static let staleAfter: TimeInterval = 15

    /// Ends the activities a session left when the app died: at launch nothing records yet.
    static func endLeftOver() {
        for left in Activity<RecordingAttributes>.activities {
            Task { await left.end(nil, dismissalPolicy: .immediate) }
        }
    }

    func start(at started: Date) {
        guard Self.enabled, activity == nil, ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let state = RecordingAttributes.ContentState(lap: nil, delta: nil, gain: false, best: nil)
        activity = try? Activity.request(
            attributes: RecordingAttributes(startedAt: started),
            content: .init(state: state, staleDate: Date().addingTimeInterval(Self.staleAfter))
        )
        shown = state
        sentAt = Date()
    }

    func update(_ model: TrackScreenModel) {
        guard let activity else { return }
        let state = Self.state(model)
        let now = Date()
        let since = now.timeIntervalSince(sentAt)
        guard (state != shown && since >= 1) || since >= Self.heartbeat else { return }
        shown = state
        sentAt = now
        let stale = now.addingTimeInterval(Self.staleAfter)
        Task { await activity.update(.init(state: state, staleDate: stale)) }
    }

    func end() {
        guard let activity else { return }
        self.activity = nil
        shown = nil
        // Ended before the process exits when the app is swiped away (willTerminate).
        let done = DispatchSemaphore(value: 0)
        Task.detached {
            await activity.end(nil, dismissalPolicy: .immediate)
            done.signal()
        }
        _ = done.wait(timeout: .now() + 1)
    }

    static func state(_ model: TrackScreenModel) -> RecordingAttributes.ContentState {
        RecordingAttributes.ContentState(
            lap: model.lapNumber.flatMap { $0 > 0 ? $0 : nil },
            delta: model.deltaSeconds.map { CoreBridge.delta(seconds: $0) },
            gain: model.deltaSeconds.map { CoreBridge.isGain(seconds: $0) } ?? false,
            best: model.bestMillis.map { CoreBridge.lapTime(millis: $0) }
        )
    }
}
