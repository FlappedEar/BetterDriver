import SwiftUI
import BetterDriverCore

/// The sections of a session, the same six tabs as Android (UI-62 B, UI-157 B), drawn as
/// Android's tab row: an icon over a small name (UI-163 C, UI-291 A). Summary opens first (UI-293 A).
enum SessionTab: String, CaseIterable, Identifiable {
    case summary = "Summary"
    case compare = "Compare"
    case map = "Map"
    case laps = "Laps"
    case corners = "Corners"
    case car = "Car"

    var id: String { rawValue }

    var symbol: String {
        switch self {
        case .summary: "list.bullet.rectangle"
        case .compare: "chart.xyaxis.line"
        case .map: "map"
        case .laps: "stopwatch"
        case .corners: "arrow.turn.up.right"
        case .car: "car"
        }
    }
}

/// One session's review (FBBD-331): its numbers come from the shared core, as on Android.
struct SessionView: View {
    let session: SessionRow
    /// The imports so far; a new one may have replaced this session or its track.
    var revision = 0
    @State private var tab: SessionTab = .summary
    @State private var summary: SessionSummary?
    @State private var compare: LapCompare?
    @State private var screen: AnalysisUi?
    @State private var failure: String?
    @State private var notYet: String?

    var body: some View {
        VStack(spacing: 0) {
            TabRow(selected: $tab)
            Divider().overlay(Palette.outline)
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .background(Palette.background)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { ExportButton(sessionId: session.id) }
            // Android's ways on from a session: the track's progression (FBBD-181) and the coach's cues (FBBD-99).
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Progression at this track") { notYet = "Progression at a track" }
                    Button("Coach review") { notYet = "Coach review" }
                } label: {
                    Label("More", systemImage: "ellipsis.circle")
                }
                .accessibilityIdentifier("sessionMore")
            }
        }
        .notImplementedAlert($notYet)
        .task(id: "\(session.id)#\(revision)") { await load() }
    }

    private var title: String {
        let time = session.start.formatted(date: .omitted, time: .shortened)
        let track = summary?.trackName ?? session.track
        return track.isEmpty ? time : "\(time) · \(track)"
    }

    @ViewBuilder private var content: some View {
        if let failure {
            Message(text: failure)
        } else if let summary {
            switch tab {
            case .summary: SummaryTab(summary: summary)
            case .compare: CompareTab(compare: compare, summary: summary)
            case .laps: LapsTab(summary: summary) { lap in pick(lap, summary) }
            case .map: MapTab(line: summary.line)
            case .corners: CornersTab(screen: screen)
            case .car: CarTab(car: screen?.car)
            }
        } else {
            ProgressView().tint(Palette.accent).padding(.top, 60)
        }
    }

    /// Picking a lap compares it with the fastest, on Compare, Corners and Car; Compare opens, as on Android.
    private func pick(_ lap: LapRow, _ summary: SessionSummary) {
        Task {
            let index = lap.index
            let picked = await Task.detached(priority: .userInitiated) {
                (ReviewBridge.compare(summary, lapIndex: index), ReviewBridge.screen(summary, lapIndex: index))
            }.value
            compare = picked.0
            screen = picked.1
            tab = .compare
        }
    }

    private func load() async {
        summary = nil
        compare = nil
        screen = nil
        failure = nil
        let id = session.id
        do {
            let read = try await Task.detached(priority: .userInitiated) { try ReviewBridge.summary(id: id) }.value
            summary = read
            compare = read.compare
            screen = read.screen
        } catch {
            failure = "This session could not be read: \(error.localizedDescription)"
        }
    }
}

/// Android's analysis tab row: an icon over a small name, the picked tab in the accent colour.
private struct TabRow: View {
    @Binding var selected: SessionTab

    var body: some View {
        HStack(spacing: 0) {
            ForEach(SessionTab.allCases) { t in
                Button { selected = t } label: {
                    VStack(spacing: 3) {
                        Image(systemName: t.symbol).font(.system(size: 18))
                        Text(t.rawValue).font(.caption2.weight(.semibold))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .foregroundStyle(t == selected ? Palette.accent : Palette.onSurfaceVariant)
                    .overlay(alignment: .bottom) {
                        Rectangle().fill(t == selected ? Palette.accent : .clear).frame(height: 3)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("tab\(t.rawValue)")
            }
        }
        .frame(maxWidth: 760)
        .frame(maxWidth: .infinity)
    }
}

private struct SummaryTab: View {
    let summary: SessionSummary

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if summary.laps.isEmpty {
                    Card {
                        Text(summary.trackName == nil
                             ? "No saved track with a start/finish line here, so no laps."
                             : "No timed laps in this session.")
                            .foregroundStyle(Palette.onSurfaceVariant)
                    }
                } else {
                    Card {
                        HStack(alignment: .top) {
                            Figure(label: "Best lap", value: summary.bestLap ?? "–", best: true)
                            Figure(label: "Theoretical best", value: summary.theoreticalBest ?? "–")
                            Figure(label: "Laps", value: "\(summary.laps.count)")
                        }
                    }
                }
            }
            .padding(20)
            .frame(maxWidth: 900)
            .frame(maxWidth: .infinity)
        }
    }
}

private struct LapsTab: View {
    let summary: SessionSummary
    let pick: (LapRow) -> Void

    var body: some View {
        List(summary.laps) { lap in
            Button { pick(lap) } label: { row(lap) }
                .disabled(lap.fastest || !lap.counts)
                .listRowBackground(Palette.background)
                .accessibilityIdentifier("lap\(lap.number)")
        }
        .scrollContentBackground(.hidden)
        .frame(maxWidth: 900)
        .accessibilityIdentifier("lapList")
    }

    private func row(_ lap: LapRow) -> some View {
        HStack {
            Text("Lap \(lap.number)").foregroundStyle(Palette.onSurfaceVariant)
            Spacer()
            if let delta = lap.delta {
                Text(delta).font(.subheadline.monospacedDigit()).foregroundStyle(Palette.onSurfaceVariant)
            }
            Text(lap.time)
                .font(.title3.weight(.semibold).monospacedDigit())
                .foregroundStyle(lap.fastest ? Palette.best : (lap.counts ? Palette.onBackground : Palette.onSurfaceVariant))
                .frame(minWidth: 110, alignment: .trailing)
        }
        .contentShape(Rectangle())
    }
}

private struct Figure: View {
    let label: String
    let value: String
    var best = false

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label.uppercased())
                .font(.caption2.weight(.semibold))
                .tracking(0.8)
                .foregroundStyle(Palette.onSurfaceVariant)
            Text(value)
                .font(.system(size: 28, weight: .semibold).monospacedDigit())
                .foregroundStyle(best ? Palette.best : Palette.onBackground)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct Message: View {
    let text: String

    var body: some View {
        Text(text)
            .foregroundStyle(Palette.onSurfaceVariant)
            .multilineTextAlignment(.center)
            .padding(40)
    }
}
