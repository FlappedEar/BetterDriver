import SwiftUI
import BetterDriverCore

/// Progression at the track session [sessionId] was driven at (FBBD-181), as Android's
/// TrackProgressionScreen: the laps, then each corner by its CornerId, then what came of the
/// coaching, point by point (session, track day or month) with the way each is going, and the
/// sessions it is made of, imported ones marked with where they came from. Numbers only where they
/// are precise. The shared core reads the sessions at the track and words the screen, as on Android;
/// sessions with no record yet are analysed while the screen shows and join one by one.
struct ProgressionScreen: View {
    let sessionId: String
    @State private var track: ProgressionTrack?
    @State private var noTrack = false
    @State private var grouping: ProgressionGrouping = .session
    @State private var period: ProgressionPeriod = .all
    @State private var day: ProgressionDay?
    @State private var excluded: Set<String> = []
    @State private var adding = 0
    @State private var ui: ProgressionUi?
    /// Which redraw is the latest, so a slower earlier one never replaces it.
    @State private var drawn = 0
    /// The session a corner's "Every pass" opens (FBBD-93).
    @State private var passes: SessionRow?

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(Palette.background)
            .navigationTitle("Progression")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(item: $passes) { SessionView(session: $0) }
            .task(id: sessionId) { await load() }
    }

    @ViewBuilder private var content: some View {
        if let ui {
            ProgressionReady(ui: ui, actions: actions)
        } else if noTrack {
            CardColumn { Card { Dim("This session is at no saved track, so it has no progression.") } }
        } else {
            ProgressView().tint(Palette.accent).padding(.top, 60)
        }
    }

    private var actions: ProgressionActions {
        ProgressionActions(
            grouping: { g in
                grouping = g
                redraw()
            },
            period: { p in
                day = nil
                period = p
                redraw()
            },
            day: { d in
                day = d
                // A track day opens to its sessions.
                if d != nil { grouping = .session }
                redraw()
            },
            toggle: { id in
                if excluded.contains(id) { excluded.remove(id) } else { excluded.insert(id) }
                redraw()
            },
            cornerPasses: { id in passes = ReviewBridge.sessions().first { $0.id == id } }
        )
    }

    /// Finds the session's track, shows the records kept, then reads the track's sessions with none.
    private func load() async {
        // Back from a session it opened: the sessions are read already, or still being read.
        if track != nil { return redraw() }
        let id = sessionId
        let found = await Task.detached(priority: .userInitiated) { ReviewBridge.progressionTrack(sessionId: id) }.value
        guard let found else {
            noTrack = true
            return
        }
        track = found
        redraw()
        // Below the UI's priority, so the screen stays responsive while each session is analysed.
        await Task.detached(priority: .utility) {
            ReviewBridge.addToProgression(trackId: found.id) { left in
                Task { @MainActor in
                    adding = left
                    redraw()
                }
            } added: {
                Task { @MainActor in redraw() }
            }
        }.value
    }

    /// The screen worked out again off the main thread, for the choices as they are now.
    private func redraw() {
        guard let track else { return }
        drawn += 1
        let mine = drawn
        let (g, p, d, e, a) = (grouping, period, day, excluded, adding)
        Task {
            let made = await Task.detached(priority: .userInitiated) {
                ReviewBridge.progressionScreen(track: track, grouping: g, period: p, day: d, excluded: e, adding: a)
            }.value
            if mine == drawn { ui = made }
        }
    }
}

/// The progression on a page of its own over the session, with Done, as the track day opens: the
/// session under it stays as it was, analysed, for when the page is closed.
struct ProgressionSheet: View {
    let sessionId: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ProgressionScreen(sessionId: sessionId)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
                }
        }
    }
}

extension ReviewBridge {
    /// The saved track session [sessionId] was driven at, for its progression; nil when none.
    static func progressionTrack(sessionId: String) -> ProgressionTrack? {
        library.progressions.trackOf(id: sessionId)
    }

    /// Analyses the sessions at [trackId] with no record yet, newest first, as Android does when the
    /// progression opens; [left] hears how many more may be read, [added] each session added.
    static func addToProgression(trackId: String, left: @escaping (Int) -> Void, added: @escaping () -> Void) {
        _ = library.progressions.addNewest(trackId: trackId, left: { left(Int($0.int32Value)) }, added: added)
    }

    /// The progression screen of [track] for the driver's choices, worded by the shared core.
    static func progressionScreen(
        track: ProgressionTrack,
        grouping: ProgressionGrouping,
        period: ProgressionPeriod,
        day: ProgressionDay?,
        excluded: Set<String>,
        adding: Int
    ) -> ProgressionUi {
        library.progressions.screen(
            trackId: track.id, title: track.name, grouping: grouping, period: period, day: day,
            excluded: excluded, adding: Int32(adding)
        )
    }
}

/// What the driver can change on the progression screen, as Android's ProgressionActions.
private struct ProgressionActions {
    let grouping: (ProgressionGrouping) -> Void
    let period: (ProgressionPeriod) -> Void
    /// Shows only that day's sessions, or every day again when nil.
    let day: (ProgressionDay?) -> Void
    /// Leaves a session out of the progression, or counts it again.
    let toggle: (String) -> Void
    /// Opens a session, where the corner's passes are (FBBD-93).
    let cornerPasses: (String) -> Void
}

/// On an opened corner of the progression: its passes in the latest session that drove it (FBBD-93).
private let progressionLatestPassesText = "Every pass, latest session ›"

private struct ProgressionReady: View {
    let ui: ProgressionUi
    let actions: ProgressionActions

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                ProgressionChoices(ui: ui, actions: actions)
                if ui.empty {
                    Card {
                        Dim(
                            ui.adding > 0
                                ? "Adding the sessions recorded here…"
                                : "No analysed session here in this period. A session takes part once its lap analysis is made."
                        )
                    }
                } else {
                    ProgressionLaps(ui: ui)
                    if !ui.corners.isEmpty { ProgressionCorners(ui: ui, actions: actions) }
                    if !ui.coaching.isEmpty { ProgressionCoaching(ui: ui) }
                    ProgressionPoints(ui: ui, actions: actions)
                }
            }
            .padding(16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .accessibilityIdentifier("trackProgression")
    }
}

/// The shared core's chips, so no Kotlin enum entry is spelled here.
private let progressionGroupingTitles: [(ProgressionGrouping, String)] =
    TrackProgressionUiKt.ProgressionGroupingTitles.map { ($0.grouping, $0.title) }

private let progressionPeriods: [ProgressionPeriod] = [.all, .quarter, .year]

private struct ProgressionChoices: View {
    let ui: ProgressionUi
    let actions: ProgressionActions

    var body: some View {
        Card {
            Text(ui.title).fontWeight(.semibold).foregroundStyle(Palette.onBackground)
            Dim(ui.summary)
            ProgressionFlow(spacing: 8) {
                ForEach(progressionGroupingTitles, id: \.1) { item in
                    ProgressionChip(label: item.1, on: ui.grouping == item.0) { actions.grouping(item.0) }
                }
            }
            ProgressionFlow(spacing: 8) {
                ForEach(progressionPeriods, id: \.label) { p in
                    ProgressionChip(label: p.label, on: ui.period == p && ui.day == nil) {
                        actions.day(nil)
                        actions.period(p)
                    }
                }
                if let d = ui.day {
                    ProgressionChip(label: "\(d.description) ✕", on: true) { actions.day(nil) }
                }
            }
            if ui.adding > 0 {
                Dim("Adding \(ui.adding) more \(ui.adding == 1 ? "session" : "sessions") recorded here…")
                ProgressView().tint(Palette.accent)
            }
            ForEach(Array(ui.notes.enumerated()), id: \.offset) { _, note in Dim(note) }
        }
    }
}

private struct ProgressionLaps: View {
    let ui: ProgressionUi

    var body: some View {
        Card {
            SectionTitle("Laps")
            ForEach(Array(ui.laps.enumerated()), id: \.offset) { i, row in
                if i > 0 { ProgressionDivider() }
                ProgressionMetricView(row: row)
            }
            // One track day open: how its lap times went, lap by lap (FBBD-175).
            if let dayLaps = ui.dayLaps {
                ProgressionDivider()
                ProgressionMetricView(row: dayLaps)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("progression-laps")
    }
}

private struct ProgressionCorners: View {
    let ui: ProgressionUi
    let actions: ProgressionActions

    var body: some View {
        Card {
            SectionTitle("Corners · tap one for more")
            ForEach(Array(ui.corners.enumerated()), id: \.element.cornerId) { i, corner in
                if i > 0 { ProgressionDivider() }
                ProgressionCorner(corner: corner, actions: actions)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("progression-corners")
    }
}

private struct ProgressionCorner: View {
    let corner: BetterDriverCore.CornerRow
    let actions: ProgressionActions
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ProgressionMetricView(row: corner.time, title: corner.name)
            if open {
                ForEach(Array(corner.details.enumerated()), id: \.offset) { _, row in
                    ProgressionMetricView(row: row, small: true)
                }
                if corner.details.isEmpty { Dim("Nothing else measured here.") }
                if let session = corner.sessionId {
                    Button(progressionLatestPassesText) { actions.cornerPasses(session) }
                        .foregroundStyle(Palette.accent)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture { withAnimation { open.toggle() } }
    }
}

/// The colour a trend reads in, as Android's.
private func progressionToneColor(_ tone: TrendTone) -> Color {
    if tone == .better { return Palette.gain }
    if tone == .worse { return Palette.loss }
    if tone == .neutral { return Palette.onBackground }
    return Palette.onSurfaceVariant
}

/// One metric: its name and latest value, a small chart of its points, and its trend in words.
private struct ProgressionMetricView: View {
    let row: MetricRow
    var title: String?
    var small = false

    var body: some View {
        let tone = progressionToneColor(row.tone)
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .center, spacing: 0) {
                VStack(alignment: .leading, spacing: 0) {
                    if let title { Text(title).fontWeight(.semibold).foregroundStyle(Palette.onBackground) }
                    if title == nil || small {
                        Text(row.label).foregroundStyle(small ? Palette.onSurfaceVariant : Palette.onBackground)
                    }
                    if title != nil && !small { Dim(row.label) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if !row.latest.isEmpty {
                    Text(row.latest)
                        .fontWeight(.semibold)
                        .foregroundStyle(row.doubtful ? Palette.onSurfaceVariant : Palette.onBackground)
                }
                ProgressionSparkline(row: row, color: tone)
                    .frame(width: 88, height: 28)
                    .padding(.leading, 12)
            }
            Text(row.trend + (row.doubtful ? " · not every pass is precise, so no number" : ""))
                .font(.subheadline)
                .foregroundStyle(tone)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The metric at each point, oldest on the left; a time drawn upside down, so up is always quicker.
/// No numbers on it: the value and its trend are said beside it, where they may be.
private struct ProgressionSparkline: View {
    let row: MetricRow
    let color: Color

    var body: some View {
        let values = row.values.map { ($0 as? KotlinDouble)?.doubleValue }
        let points = values.enumerated().compactMap { i, v in v.map { (i, $0) } }
        Canvas { context, size in
            guard let first = points.first else { return }
            let lo = points.map(\.1).min() ?? first.1
            let hi = points.map(\.1).max() ?? first.1
            let span = hi - lo > 0 ? hi - lo : 1
            let last = CGFloat(max(values.count - 1, 1))
            func at(_ i: Int, _ v: Double) -> CGPoint {
                let share = CGFloat((v - lo) / span)
                let y = row.invert ? share : 1 - share
                return CGPoint(x: size.width * CGFloat(i) / last, y: hi == lo ? size.height / 2 : y * size.height)
            }
            for (a, b) in zip(points, points.dropFirst()) {
                var path = Path()
                path.move(to: at(a.0, a.1))
                path.addLine(to: at(b.0, b.1))
                context.stroke(path, with: .color(Palette.onSurfaceVariant), style: StrokeStyle(lineWidth: 2, lineCap: .round))
            }
            if let end = points.last {
                let c = at(end.0, end.1)
                context.fill(Path(ellipseIn: CGRect(x: c.x - 3, y: c.y - 3, width: 6, height: 6)), with: .color(color))
            }
        }
        .accessibilityLabel("\(row.label): \(row.trend)")
    }
}

private struct ProgressionCoaching: View {
    let ui: ProgressionUi

    var body: some View {
        Card {
            SectionTitle("Coaching · what came of each focus")
            if let summary = ui.coachingSummary { Text(summary).foregroundStyle(Palette.onBackground) }
            ForEach(Array(ui.coaching.enumerated()), id: \.offset) { _, row in
                VStack(alignment: .leading, spacing: 2) {
                    Dim(row.label)
                    Text(row.text)
                        .font(.subheadline)
                        .foregroundStyle(
                            row.tone == .better ? Palette.gain : (row.tone == .worse ? Palette.loss : Palette.onBackground)
                        )
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("progression-coaching")
    }
}

private struct ProgressionPoints: View {
    let ui: ProgressionUi
    let actions: ProgressionActions

    private var title: String {
        if ui.grouping == .session { return "Sessions · untick one to leave it out" }
        if ui.grouping != .month { return "Track days · tap one for its sessions" }
        return "Months"
    }

    var body: some View {
        Card {
            SectionTitle(title)
            ForEach(ui.points, id: \.key) { p in
                let sessions = ui.grouping == .session
                let tap: (() -> Void)? = sessions
                    ? { actions.toggle(p.key) }
                    : p.day.map { d in { actions.day(d) } }
                HStack(spacing: 0) {
                    if sessions {
                        Image(systemName: p.excluded ? "square" : "checkmark.square.fill")
                            .font(.title3)
                            .foregroundStyle(p.excluded ? Palette.onSurfaceVariant : Palette.accent)
                            .frame(width: 48, height: 48)
                            .accessibilityLabel(p.excluded ? "Left out" : "Counted")
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        Text(p.label).foregroundStyle(p.excluded ? Palette.onSurfaceVariant : Palette.onBackground)
                        Dim(p.detail)
                        if let origin = p.origin { Dim(origin, color: Palette.cold) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Text(p.best)
                        .fontWeight(.semibold)
                        .foregroundStyle(p.excluded ? Palette.onSurfaceVariant : Palette.onBackground)
                }
                .contentShape(Rectangle())
                .onTapGesture { tap?() }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("progression-points")
    }
}

private struct ProgressionDivider: View {
    var body: some View { Divider().overlay(Palette.outline) }
}

/// A filter chip, as Material's FilterChip on Android (as the track editor's).
private struct ProgressionChip: View {
    let label: String
    let on: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if on { Image(systemName: "checkmark").font(.caption.weight(.bold)) }
                Text(label).font(.subheadline.weight(.medium))
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
            .foregroundStyle(on ? Palette.onAccent : Palette.onBackground)
            .background(on ? Palette.accent : Color.clear, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(on ? Color.clear : Palette.outline))
        }
        .buttonStyle(.plain)
    }
}

/// Android's FlowRow: its children left to right, wrapping onto the next line when out of room.
private struct ProgressionFlow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let width = rows.map(\.width).max() ?? 0
        let height = rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(rows.count - 1, 0))
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for i in row.indices {
                let size = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        for i in subviews.indices {
            let size = subviews[i].sizeThatFits(.unspecified)
            let needed = row.indices.isEmpty ? size.width : row.width + spacing + size.width
            if !row.indices.isEmpty && needed > width {
                rows.append(row)
                row = Row()
            }
            row.width = row.indices.isEmpty ? size.width : row.width + spacing + size.width
            row.height = max(row.height, size.height)
            row.indices.append(i)
        }
        if !row.indices.isEmpty { rows.append(row) }
        return rows
    }
}
