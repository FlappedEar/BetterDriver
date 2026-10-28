import SwiftUI
import BetterDriverCore

/// The sessions of one day at one track, as Android's day heading links them (FBBD-315).
struct TrackDayLink: Identifiable, Hashable {
    let track: String
    let ids: [String]
    var id: String { ids.joined(separator: ",") }

    /// The tracks of one day's [sessions] driven in at least two of them, most sessions first, each
    /// with its sessions oldest first, as Android's trackDays; a session with no track takes no part.
    static func of(_ sessions: [SessionRow]) -> [TrackDayLink] {
        Dictionary(grouping: sessions.filter { !$0.track.isEmpty }, by: \.track)
            .filter { $0.value.count >= 2 }
            .map { TrackDayLink(track: $0.key, ids: $0.value.sorted { $0.start < $1.start }.map(\.id)) }
            .sorted { $0.ids.count > $1.ids.count }
    }
}

/// The track day page (FBBD-315, UI round 48), as Android's TrackDayScreen: the day's best, typical
/// and perfect lap with each session's (UI-211 A), the corners where a typical lap loses most, with a
/// small line of each over the day (UI-212 A, UI-213 A), what the next day here works on and what
/// came of the last day here (UI-214 B). The shared core reads the day and words it.
struct TrackDayView: View {
    let link: TrackDayLink
    @Environment(\.dismiss) private var dismiss
    @State private var state: TrackDayState?

    var body: some View {
        NavigationStack {
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .background(Palette.background)
                .navigationTitle("Track day")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
                }
        }
        .task(id: link) {
            let ids = link.ids
            state = await Task.detached { ReviewBridge.trackDay(ids) }.value
        }
    }

    @ViewBuilder private var content: some View {
        if let ready = state as? TrackDayStateReady {
            Ready(ui: ready.ui)
        } else if let unavailable = state as? TrackDayStateUnavailable {
            CardColumn { Card { Dim(unavailable.why) } }
        } else {
            VStack(spacing: 12) {
                ProgressView().tint(Palette.accent)
                Dim("Reading every lap of the day…")
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

private struct Ready: View {
    let ui: TrackDayUi

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(ui.title).font(.system(size: 18, weight: .semibold)).foregroundStyle(Palette.onBackground)
                    Dim(ui.subtitle)
                }
                Numbers(ui: ui)
                if ui.sessions.count > 1 { Sessions(ui: ui) }
                if let proof = ui.proof { Proof(proof: proof) }
                if !ui.corners.isEmpty { Corners(ui: ui) }
                if !ui.next.isEmpty { Next(ui: ui) }
            }
            .padding(16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .accessibilityIdentifier("trackDay")
    }
}

private struct Numbers: View {
    let ui: TrackDayUi

    var body: some View {
        Card {
            HStack(alignment: .top, spacing: 8) {
                DayFigure(label: "Best", value: ui.best, color: Palette.best)
                DayFigure(label: "Typical", value: ui.typical, color: Palette.onBackground)
                DayFigure(label: "Perfect", value: ui.perfect, color: Palette.accent)
            }
            Text(ui.loss).font(.subheadline).foregroundStyle(Palette.onBackground)
        }
    }
}

private struct DayFigure: View {
    let label: String
    let value: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            SectionTitle(label)
            Text(value).font(.system(size: 24, weight: .bold).monospacedDigit()).foregroundStyle(color)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Each session's typical and best lap as two bars, in the order they were driven (UI-211 A).
private struct Sessions: View {
    let ui: TrackDayUi
    private let typicalColor = Palette.accent.opacity(0.55)

    var body: some View {
        Card {
            SectionTitle("Over the day")
            let values = ui.sessions.flatMap { [$0.bestSeconds?.doubleValue, $0.typicalSeconds?.doubleValue].compactMap { $0 } }
            if let lowest = values.min(), let high = values.max() {
                // The bars start this far below the quickest lap, so the quickest still shows a bar.
                let low = lowest - 2.0
                Canvas { context, size in
                    let slot = size.width / CGFloat(ui.sessions.count)
                    let bar = slot * 0.7 / 2
                    func top(_ v: Double) -> CGFloat { size.height * CGFloat(1 - (v - low) / (high - low)) }
                    for (i, s) in ui.sessions.enumerated() {
                        let x = slot * CGFloat(i) + (slot - 2 * bar) / 2
                        if let t = s.typicalSeconds?.doubleValue {
                            context.fill(Path(CGRect(x: x, y: top(t), width: bar, height: size.height - top(t))), with: .color(typicalColor))
                        }
                        if let b = s.bestSeconds?.doubleValue {
                            context.fill(Path(CGRect(x: x + bar, y: top(b), width: bar, height: size.height - top(b))), with: .color(Palette.best))
                        }
                    }
                }
                .frame(height: 110)
                HStack(spacing: 0) {
                    ForEach(Array(ui.sessions.enumerated()), id: \.offset) { _, s in
                        Text(s.label).font(.caption2).foregroundStyle(Palette.onSurfaceVariant).frame(maxWidth: .infinity)
                    }
                }
                HStack(spacing: 14) {
                    Legend(text: "best lap", color: Palette.best)
                    Legend(text: "typical lap", color: typicalColor)
                }
            }
            if let over = ui.overDay { Text(over).font(.subheadline).foregroundStyle(Palette.onBackground) }
        }
    }
}

private struct Legend: View {
    let text: String
    let color: Color

    var body: some View {
        HStack(spacing: 4) {
            Rectangle().fill(color).frame(width: 10, height: 10)
            Dim(text)
        }
    }
}

private struct Corners: View {
    let ui: TrackDayUi

    var body: some View {
        Card {
            SectionTitle("Where the day can get faster · per lap")
            ForEach(Array(ui.corners.enumerated()), id: \.offset) { i, corner in
                if i > 0 { Divider().overlay(Palette.outline) }
                CornerRow(corner: corner)
            }
            Dim(
                "The typical pass of your laps at the day's pace (within \(TrackDayUiKt.PaceSharePercent) % of your best) against " +
                    "your second fastest pass of the day. Under 0.05 s is GPS timing."
            )
        }
    }
}

private struct CornerRow: View {
    let corner: TrackDayCornerUi

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 10) {
                Text(corner.tag).font(.system(size: 18, weight: .bold)).foregroundStyle(Palette.onBackground)
                    .frame(width: 40, alignment: .leading)
                Text(corner.kind).font(.caption.weight(.medium))
                    .foregroundStyle(corner.learn ? Palette.warning : Palette.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                // A corner not driven in a session is null there.
                Trend(values: corner.trend.map { ($0 as? KotlinDouble)?.doubleValue })
                Text("\(CoreText.seconds(corner.perLapSeconds)) s")
                    .font(.system(size: 18, weight: .bold).monospacedDigit()).foregroundStyle(Palette.accent)
            }
            Todo(lines: corner.todo)
        }
    }
}

/// The corner's typical pass in each session, first session on the left, lower is faster (UI-213 A).
private struct Trend: View {
    let values: [Double?]

    var body: some View {
        let known = values.compactMap { $0 }
        if known.count >= 2, let low = known.min(), let high = known.max() {
            let span = high - low > 0 ? high - low : 1
            Canvas { context, size in
                let step = size.width / CGFloat(max(values.count - 1, 1))
                var path = Path()
                var started = false
                for (i, v) in values.enumerated() {
                    guard let v else { continue }
                    let p = CGPoint(x: step * CGFloat(i), y: size.height * CGFloat(1 - (v - low) / span))
                    if started { path.addLine(to: p) } else { path.move(to: p); started = true }
                }
                context.stroke(path, with: .color(Palette.onSurfaceVariant), style: StrokeStyle(lineWidth: 2, lineCap: .round))
            }
            .frame(width: 70, height: 22)
        }
    }
}

private struct Todo: View {
    let lines: [String]

    var body: some View {
        ForEach(Array(lines.enumerated()), id: \.offset) { _, t in
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("›").foregroundStyle(Palette.accent)
                Text(t).font(.subheadline).foregroundStyle(Palette.onBackground)
            }
        }
    }
}

private struct Next: View {
    let ui: TrackDayUi

    var body: some View {
        Card {
            SectionTitle("Next time here · work on")
            ForEach(Array(ui.next.enumerated()), id: \.offset) { i, corner in
                if i > 0 { Divider().overlay(Palette.outline) }
                HStack {
                    Text(corner.tag).font(.system(size: 17, weight: .bold)).foregroundStyle(Palette.onBackground)
                    Spacer()
                    Text("\(CoreText.seconds(corner.perLapSeconds)) s a lap")
                        .font(.body.weight(.bold).monospacedDigit()).foregroundStyle(Palette.accent)
                }
                Todo(lines: corner.todo)
            }
            if let coach = ui.coachLine { Dim(coach) }
            Dim("Your next day here measures these: their typical pass then against now.")
        }
    }
}

private struct Proof: View {
    let proof: TrackDayProofUi

    var body: some View {
        Card {
            SectionTitle(proof.title)
            if let wonBack = proof.wonBack {
                HStack(alignment: .lastTextBaseline, spacing: 10) {
                    Text(wonBack).font(.system(size: 24, weight: .bold).monospacedDigit())
                        .foregroundStyle(proof.better ? Palette.gain : Palette.loss)
                    Dim("a lap \(proof.better ? "back" : "lost") on the corners that day said")
                }
            }
            ForEach(Array(proof.rows.enumerated()), id: \.offset) { _, row in
                Divider().overlay(Palette.outline)
                HStack {
                    Text(row.tag).font(.system(size: 17, weight: .bold)).foregroundStyle(Palette.onBackground)
                    Spacer()
                    Text(row.verdict).font(.body.weight(.bold)).foregroundStyle(color(row.tone))
                }
                Text(row.line).font(.subheadline).foregroundStyle(Palette.onBackground)
            }
        }
    }

    private func color(_ tone: ProofTone) -> Color {
        switch tone {
        case .better: Palette.gain
        case .worse: Palette.loss
        default: Palette.onSurfaceVariant
        }
    }
}
