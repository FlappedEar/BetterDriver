import ActivityKit
import SwiftUI
import WidgetKit

/// The widget extension holding the recording's Live Activity (UI-284 A). The iPhone 12 shows it on
/// the lock screen only; the Dynamic Island views are for later phones.
@main
struct LiveActivityBundle: WidgetBundle {
    var body: some Widget {
        RecordingLiveActivity()
    }
}

private let surface = Color(red: 0x15 / 255, green: 0x1B / 255, blue: 0x20 / 255)
private let dim = Color(red: 0x8C / 255, green: 0x9A / 255, blue: 0xA5 / 255)
private let gain = Color(red: 0x22 / 255, green: 0xD3 / 255, blue: 0x7A / 255)
private let loss = Color(red: 0xFF / 255, green: 0x68 / 255, blue: 0x63 / 255)
private let rec = Color(red: 0xFF / 255, green: 0x4B / 255, blue: 0x4B / 255)

struct RecordingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RecordingAttributes.self) { context in
            LockScreenView(started: context.attributes.startedAt, state: context.state)
                .activityBackgroundTint(surface)
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Text(context.state.lap.map { "Lap \($0)" } ?? "Recording").foregroundStyle(dim)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(timerInterval: context.attributes.startedAt...Date.distantFuture, countsDown: false)
                        .monospacedDigit()
                }
                DynamicIslandExpandedRegion(.bottom) {
                    DeltaText(state: context.state, size: 30)
                }
            } compactLeading: {
                Circle().fill(rec).frame(width: 8, height: 8)
            } compactTrailing: {
                DeltaText(state: context.state, size: 14)
            } minimal: {
                Circle().fill(rec).frame(width: 8, height: 8)
            }
        }
    }
}

/// The lock screen card: "● BetterDriver · recording 23:41" and the lap, then the delta and best.
private struct LockScreenView: View {
    let started: Date
    let state: RecordingAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                HStack(spacing: 6) {
                    Circle().fill(rec).frame(width: 8, height: 8)
                    Text("BetterDriver · recording")
                    Text(timerInterval: started...Date.distantFuture, countsDown: false)
                        .monospacedDigit()
                        .frame(maxWidth: 70, alignment: .leading)
                }
                Spacer()
                if let lap = state.lap {
                    Text("Lap \(lap)")
                }
            }
            .font(.footnote.weight(.semibold))
            .foregroundStyle(dim)
            HStack(alignment: .firstTextBaseline) {
                DeltaText(state: state, size: 40)
                Spacer()
                if let best = state.best {
                    Text("best \(best)").font(.title3.monospacedDigit()).foregroundStyle(.white)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }
}

private struct DeltaText: View {
    let state: RecordingAttributes.ContentState
    let size: CGFloat

    var body: some View {
        Text(state.delta ?? "\u{2014}")
            .font(.system(size: size, weight: .bold).monospacedDigit())
            .foregroundStyle(state.delta == nil ? dim : (state.gain ? gain : loss))
    }
}
