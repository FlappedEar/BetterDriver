import SwiftUI
import BetterDriverCore

/// Session summary (FBBD-122, FBBD-123, UI-70), as Android's Progress card: this session's best lap
/// against the best of every earlier session at the track, how consistent its laps were (the typical,
/// median, lap and the spread of the middle half, FBBD-175) against the session before, and the
/// corners and straights whose typical time moved most since then. With [sessionId] it links to the
/// progression over every session at the track (FBBD-181).
struct ProgressionCard: View {
    let progress: SessionProgress
    var sessionId: String?
    @State private var progression = false

    var body: some View {
        Card {
            SectionTitle("Against earlier sessions here")
            if progress.earlierSessions > 0,
               let earlierBest = progress.earlierBestSeconds?.doubleValue,
               let delta = progress.bestDeltaSeconds?.doubleValue {
                HStack {
                    Text(SessionProgressKt.progressBestText(progress: progress))
                        .foregroundStyle(Palette.onBackground)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(CoreBridge.delta(seconds: delta))
                        .fontWeight(.semibold)
                        .foregroundStyle(CoreBridge.isGain(seconds: delta) ? Palette.gain : Palette.loss)
                }
                Dim("Was \(progressionLapTime(earlierBest)), \(progress.earlierBestLabel ?? "")")
            } else {
                Dim(SessionProgressKt.progressBestText(progress: progress))
            }
            // How consistent the session was (FBBD-175): its typical (median) lap and the spread around it.
            if let median = progress.lapMedianSeconds?.doubleValue {
                Text("Typical lap \(progressionLapTime(median))")
                    .foregroundStyle(Palette.onBackground)
                    .accessibilityIdentifier("typical-lap")
            }
            if let spread = progress.lapSpreadSeconds?.doubleValue {
                HStack {
                    Text("Lap spread \(CoreText.seconds(spread)) s")
                        .foregroundStyle(Palette.onBackground)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let d = progress.spreadDeltaSeconds?.doubleValue {
                        Text(d < 0 ? "steadier by \(CoreText.seconds(-d)) s" : "less steady by \(CoreText.seconds(d)) s")
                            .foregroundStyle(d < 0 ? Palette.gain : Palette.loss)
                    }
                }
                if progress.spreadDeltaSeconds != nil, let previous = progress.previousLabel { Dim("Against \(previous)") }
            }
            ProgressionChanges(progress: progress)
            Dim("\(progress.eligibleLapCount) counted laps. Typical: the median lap. Spread: the middle half of your lap times.")
            if sessionId != nil {
                Button("Progress over every session here") { progression = true }
                    .foregroundStyle(Palette.accent)
                    .accessibilityIdentifier("progression-link")
            }
        }
        .sheet(isPresented: $progression) {
            if let sessionId { ProgressionSheet(sessionId: sessionId) }
        }
    }
}

/// [ProgressionCard] for the session of [summary] ([sessionId]), read off the main thread once the
/// summary shows, as Android reads the earlier sessions after the analysis; nothing while it reads,
/// or when the session is at no track.
struct ProgressionSummaryCard: View {
    let summary: SessionSummary
    let sessionId: String
    @State private var progress: SessionProgress?

    var body: some View {
        Group {
            if let progress { ProgressionCard(progress: progress, sessionId: sessionId) }
        }
        .task(id: sessionId) {
            let read = summary
            progress = await Task.detached(priority: .utility) { ReviewBridge.progress(read) }.value
        }
    }
}

/// At most this many corners or straights in each of the quicker and slower lists.
private let progressionChangeRows = 3

/// The corners and straights whose typical time moved most since the session before.
private struct ProgressionChanges: View {
    let progress: SessionProgress

    var body: some View {
        if !progress.changes.isEmpty {
            let quicker = Array(SegmentChangesKt.quicker(changes: progress.changes).prefix(progressionChangeRows))
            let slower = Array(SegmentChangesKt.slower(changes: progress.changes).prefix(progressionChangeRows))
            Dim("Typical time by corner since \(progress.previousLabel ?? "")")
            if quicker.isEmpty && slower.isEmpty {
                Dim("Every corner and straight within 0.05 s of the session before.")
            } else {
                ForEach(Array((quicker + slower).enumerated()), id: \.offset) { _, change in
                    HStack {
                        Text(change.name)
                            .foregroundStyle(Palette.onBackground)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Text(CoreBridge.delta(seconds: change.deltaSeconds))
                            .fontWeight(.semibold)
                            .foregroundStyle(change.deltaSeconds < 0 ? Palette.gain : Palette.loss)
                    }
                }
            }
        }
    }
}

private func progressionLapTime(_ seconds: Double) -> String {
    CoreBridge.lapTime(millis: Int64((seconds * 1000).rounded()))
}
