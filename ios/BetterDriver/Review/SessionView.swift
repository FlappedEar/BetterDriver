import SwiftUI
import BetterDriverCore

/// The sections of a session, the same six tabs as Android (UI-62 B, UI-157 B), drawn as
/// Android's tab row: an icon over a small name (UI-163 C, UI-291 A). Summary opens first (UI-293 A).
/// Video (Arek, 2026-10-30) shows only for a session with video: its Video with data, in sight
/// rather than in the export menu. Android gets it in FBBD-392; this list is marked again
/// (`// parity-list:session-tabs`) once both apps have it.
enum SessionTab: String, CaseIterable, Identifiable {
    case summary = "Summary"
    case compare = "Compare"
    case map = "Map"
    case laps = "Laps"
    case corners = "Corners"
    case car = "Car"
    case video = "Video"

    var id: String { rawValue }

    var symbol: String {
        switch self {
        case .summary: "list.bullet.rectangle"
        case .compare: "chart.xyaxis.line"
        case .map: "map"
        case .laps: "stopwatch"
        case .corners: "arrow.turn.up.right"
        case .car: "car"
        case .video: "film"
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
    @State private var coachReview: CoachReviewLink?
    @State private var progression = false
    @State private var everyPass: EveryPassLink?
    @State private var mapView: MapTabView = .laps
    /// Video with data (FBBD-152): its sheet, then its player full screen; closing the player comes
    /// back to the sheet.
    @State private var dataVideo = false
    @State private var playing: DataVideoPlayback?
    @State private var toPlay: DataVideoPlayback?
    /// The player came from the sheet, so closing it opens the sheet again; from the Video tab it returns there.
    @State private var fromSheet = false
    @State private var hasVideo = false

    var body: some View {
        VStack(spacing: 0) {
            TabRow(tabs: SessionTab.allCases.filter { $0 != .video || hasVideo }, selected: $tab)
            Divider().overlay(Palette.outline)
            content
                .environment(\.everyPass, openEveryPass)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .background(Palette.background)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { ExportButton(sessionId: session.id) { dataVideo = true } }
            ToolbarItem(placement: .topBarTrailing) {
                SessionMoreMenu(session: session, progression: $progression, coachReview: $coachReview)
            }
        }
        .sheet(isPresented: $dataVideo, onDismiss: {
            playing = toPlay
            toPlay = nil
        }) {
            DataVideoSheet(sessionId: session.id, title: title) { p in
                toPlay = p
                fromSheet = true
                dataVideo = false
            } onClose: { dataVideo = false }
                .presentationDetents([.medium, .large])
        }
        .fullScreenCover(item: $playing, onDismiss: {
            if fromSheet { dataVideo = true }
            fromSheet = false
        }) { p in
            DataVideoPlayerPage(playback: p) { playing = nil }
        }
        .notImplementedAlert($notYet)
        .fullScreenCover(item: $coachReview) { CoachReviewScreen(link: $0) }
        // Every pass through a corner (FBBD-153), from the focus and the Corners tab, as on Android.
        .fullScreenCover(item: $everyPass) { EveryPassScreen(link: $0) }
        .sheet(isPresented: $progression) { ProgressionSheet(sessionId: session.id) }
        .task(id: "\(session.id)#\(revision)") { await load() }
        .task(id: session.id) {
            let id = session.id
            hasVideo = await Task.detached { Exports.make().hasVideo(id: id) }.value
        }
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
            case .summary:
                SummaryTab(summary: summary, sessionId: session.id, screen: screen, compare: { pick(index: $0, summary) }, openLine: { mapView = .line; tab = .map })
            case .compare: CompareTab(compare: compare, summary: summary, states: screen?.compare)
            case .laps: LapsTab(summary: summary) { lap in pick(index: lap.index, summary) }
            case .map: MapTab(line: summary.line, laps: screen?.map, view: $mapView)
            case .corners: CornersTab(screen: screen)
            case .car: CarTab(car: screen?.car)
            case .video: DataVideoPanel(sessionId: session.id, title: title) { playing = $0 }
            }
        } else {
            ProgressView().tint(Palette.accent).padding(.top, 60)
        }
    }

    /// Every pass through a corner, once the session is read.
    private var openEveryPass: ((String) -> Void)? {
        guard let summary else { return nil }
        return { everyPass = EveryPassLink(summary: summary, cornerId: $0) }
    }

    /// Picking a lap (timed lap [index]) compares it with the fastest, on Compare, Corners and Car;
    /// Compare opens, as on Android.
    private func pick(index: Int, _ summary: SessionSummary) {
        Task {
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

/// Android's ways on from a session: the track's progression (FBBD-181) and the coach's cues (FBBD-99).
/// Its own view, so that only it reads the cue counts: when they land, the session's page and its
/// export menu are not drawn again, which would close a menu the driver is opening.
private struct SessionMoreMenu: View {
    let session: SessionRow
    @Binding var progression: Bool
    @Binding var coachReview: CoachReviewLink?

    var body: some View {
        Menu {
            Button("Progression at this track") { progression = true }
            // Only for a session the coach spoke in, with its cues, as Android's card (FBBD-99).
            if let cues = CoachReviewCounts.shared.counts[session.id] {
                Button { coachReview = CoachReviewLink(session) } label: {
                    Text("Coach review")
                    Text(CoachReviewCounts.text(cues))
                }
            }
        } label: {
            Label("More", systemImage: "ellipsis.circle")
        }
        .accessibilityIdentifier("sessionMore")
    }
}

/// Android's analysis tab row: an icon over a small name, the picked tab in the accent colour.
private struct TabRow: View {
    let tabs: [SessionTab]
    @Binding var selected: SessionTab

    var body: some View {
        HStack(spacing: 0) {
            ForEach(tabs) { t in
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

/// Android's Summary (AnalysisScreen.kt): what to work on next first (FBBD-20), the three lap
/// times (FBBD-178), heart rate, what came of the last focus (FBBD-180), where time goes, then the whole session's G-forces and acceleration
/// runs. A session with no laps shows its drive, G-forces and runs, which need no laps (FBBD-342).
private struct SummaryTab: View {
    let summary: SessionSummary
    let sessionId: String
    let screen: AnalysisUi?
    let compare: (Int) -> Void
    let openLine: () -> Void

    var body: some View {
        CardColumn {
            if let ui = screen, !summary.laps.isEmpty {
                SummaryFocusCard(ui: ui, lineTotalSeconds: summary.line?.totalSeconds, compare: compare, openLine: openLine)
                SummaryTimesCard(ui: ui)
                if let hr = ui.heartRate { SummaryHeartRateCard(hr: hr) }
                // What came of the last focus (FBBD-180, FBBD-364): the loop closed, under the lap times as on Android.
                CoachingHistoryCard(summary: summary)
                // This session against the earlier ones at its track (FBBD-122), as Android under the lap times.
                ProgressionSummaryCard(summary: summary, sessionId: sessionId)
                if !ui.gains.isEmpty || !ui.losses.isEmpty { SummaryTimeGoesCard(ui: ui, summary: summary, compare: compare) }
            } else if summary.laps.isEmpty {
                if summary.trackName == nil {
                    // A loop driven again and again: make a track from it (FBBD-367).
                    MakeTrackCard(sessionId: sessionId)
                } else {
                    Card {
                        Text("No timed laps in this session.").foregroundStyle(Palette.onSurfaceVariant)
                    }
                }
                // Where the car went all the same, as Android's no-laps analysis (FBBD-342).
                if let drive = summary.drive { DriveCards(drive: drive) }
            } else {
                // Laps, but none counts (out laps, cuts): their times all the same.
                Card {
                    HStack(alignment: .top) {
                        Figure(label: "Best lap", value: summary.bestLap ?? "–", best: true)
                        Figure(label: "Laps", value: "\(summary.laps.count)")
                    }
                }
            }
            if let g = summary.whole.gForces { SummaryGForcesCard(trace: g) }
            if let runs = summary.whole.acceleration { SummaryAccelerationCard(ui: runs) }
        }
        .accessibilityIdentifier("summaryTab")
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
