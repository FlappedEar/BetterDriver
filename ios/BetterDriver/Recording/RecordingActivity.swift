import ActivityKit
import Foundation

/// Runs the recording's Live Activity (UI-284 A) from the app: started with the session, updated
/// from the track screen's values at most once a second and only when they change, ended with it.
@MainActor
final class RecordingActivity {
    private var activity: Activity<RecordingAttributes>?
    private var shown: RecordingAttributes.ContentState?
    private var sentAt = Date.distantPast

    func start(at started: Date) {
        guard activity == nil, ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let state = RecordingAttributes.ContentState(lap: nil, delta: nil, gain: false, best: nil)
        activity = try? Activity.request(
            attributes: RecordingAttributes(startedAt: started),
            content: .init(state: state, staleDate: nil)
        )
        shown = state
    }

    func update(_ model: TrackScreenModel) {
        guard let activity else { return }
        let state = Self.state(model)
        let now = Date()
        guard state != shown, now.timeIntervalSince(sentAt) >= 1 else { return }
        shown = state
        sentAt = now
        Task { await activity.update(.init(state: state, staleDate: nil)) }
    }

    func end() {
        guard let activity else { return }
        self.activity = nil
        shown = nil
        Task { await activity.end(nil, dismissalPolicy: .immediate) }
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
