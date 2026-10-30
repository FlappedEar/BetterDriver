import SwiftUI
import BetterDriverCore

// The Summary tab's cards, as Android's analysis Summary draws them (AnalysisScreen.kt): what to
// work on next first, the three lap times, heart rate, where time goes, then the whole session's
// G-forces and acceleration runs. Their words come from the shared core (SummaryText.kt).

/// Next session (FBBD-20, Focus.kt): one primary corner large with the time a typical pass leaves
/// there, at most two secondary ones beside it on an iPad and under it on an iPhone, the corners left
/// out for weak evidence, and a link to Where to find time on the Map tab (UI-190 B). Tapping a
/// focus compares its lap with the fastest.
struct SummaryFocusCard: View {
    let ui: AnalysisUi
    /// The line analysis's total time to find, seconds; nil without one.
    let lineTotalSeconds: Double?
    let compare: (Int) -> Void
    let openLine: () -> Void
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        let primary = ui.focus.first { $0.primary } ?? ui.focus.first
        let secondary = ui.focus.filter { $0 !== primary }
        Card {
            SectionTitle("Next session · work on")
            if let primary {
                if sizeClass == .regular && !secondary.isEmpty {
                    HStack(alignment: .top, spacing: 16) {
                        SummaryFocusPrimary(row: primary, compare: compare)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .layoutPriority(1.4)
                        VStack(alignment: .leading, spacing: 4) {
                            SectionTitle("Also")
                            ForEach(Array(secondary.enumerated()), id: \.offset) { _, row in
                                SummaryFocusSecondary(row: row, compare: compare)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                } else {
                    SummaryFocusPrimary(row: primary, compare: compare)
                    if !secondary.isEmpty {
                        Divider().overlay(Palette.outline)
                        SectionTitle("Also")
                        ForEach(Array(secondary.enumerated()), id: \.offset) { _, row in
                            SummaryFocusSecondary(row: row, compare: compare)
                        }
                    }
                }
            } else {
                Dim("Nothing clear enough to work on from this session's laps.")
            }
            if !ui.withheld.isEmpty {
                Dim("Left out, too little evidence: \(ui.withheld.map(\.name).joined(separator: ", "))")
            }
            if let lineTotalSeconds {
                Button(SummaryTextKt.whereToFindTimeLink(totalSeconds: lineTotalSeconds), action: openLine)
                    .foregroundStyle(Palette.accent)
                    .padding(.vertical, 4)
                    .accessibilityIdentifier("whereToFindTimeLink")
            }
        }
        .accessibilityIdentifier("nextSession")
    }
}

/// The primary focus: its corner and time large, what the faster passes did, the evidence under it.
private struct SummaryFocusPrimary: View {
    let row: FocusRow
    let compare: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { focus }
    }

    @ViewBuilder private var focus: some View {
        Button { row.compareIndex.map { compare($0.intValue) } } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .bottom) {
                    Text(row.name)
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(Palette.onBackground)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let time = SummaryTextKt.focusTimeText(row: row) {
                        VStack(alignment: .trailing, spacing: 0) {
                            Text(time)
                                .font(.system(size: 24, weight: .semibold).monospacedDigit())
                                .foregroundStyle(row.timeDoubtful ? Palette.onSurfaceVariant : Palette.gain)
                            Dim("a lap")
                        }
                    }
                }
                Text(row.what.isEmpty ? row.observation : row.what)
                    .foregroundStyle(Palette.onBackground)
                    .multilineTextAlignment(.leading)
                if !row.what.isEmpty { Dim(row.observation) }
                if row.confidence.doubtful { SummaryConfidence(confidence: row.confidence) }
                if let n = row.compareNumber { Dim("Tap to compare lap \(n.intValue)", color: Palette.cold) }
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(row.compareIndex == nil)
        .accessibilityIdentifier("nextSessionPrimary")
        // A focus corner offers every pass through it (FBBD-93), as Android's focus; a straight does not.
        EveryPassButton(cornerId: row.cornerId).frame(maxWidth: .infinity, alignment: .trailing)
    }
}

/// A secondary focus: one line with its time, and what the faster passes did.
private struct SummaryFocusSecondary: View {
    let row: FocusRow
    let compare: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { focus }
    }

    @ViewBuilder private var focus: some View {
        Button { row.compareIndex.map { compare($0.intValue) } } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(row.name)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.onBackground)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let time = SummaryTextKt.focusTimeText(row: row) {
                        Text(time).foregroundStyle(row.timeDoubtful ? Palette.onSurfaceVariant : Palette.gain)
                    }
                }
                Dim(row.what.isEmpty ? row.observation : row.what)
                if row.confidence.doubtful { SummaryConfidence(confidence: row.confidence) }
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(row.compareIndex == nil)
        EveryPassButton(cornerId: row.cornerId).frame(maxWidth: .infinity, alignment: .trailing)
    }
}

/// A conclusion's confidence next to it (FBBD-177): below High in the warning colour.
private struct SummaryConfidence: View {
    let confidence: ConfidenceUi
    var about: String?

    var body: some View {
        Dim(about.map { "\($0): \(confidence.note)" } ?? confidence.note,
            color: confidence.doubtful ? Palette.warning : Palette.onSurfaceVariant)
    }
}

/// The three lap times, never one name for two of them (FBBD-178): the best actual lap and the
/// realistic optimal side by side, the raw theoretical best under them, smaller.
struct SummaryTimesCard: View {
    let ui: AnalysisUi

    var body: some View {
        Card {
            HStack(alignment: .top, spacing: 16) {
                SummaryFigure(
                    label: ProvenanceMarkKt.BestActualLapLabel,
                    value: SummaryTextKt.bestLapText(ui: ui),
                    detail: "Lap \(ui.bestLapNumber)",
                    color: Palette.best
                )
                SummaryFigure(
                    label: ProvenanceMarkKt.RealisticOptimalLabel,
                    value: SummaryTextKt.realisticLapText(ui: ui),
                    detail: SummaryTextKt.realisticDetail(ui: ui),
                    color: ui.realisticGainSeconds != nil && !ui.gainConfidence.doubtful ? Palette.gain : Palette.onBackground
                )
            }
            SummaryFigure(
                label: ProvenanceMarkKt.RawTheoreticalBestLabel,
                value: SummaryTextKt.theoreticalLapText(ui: ui),
                detail: SummaryTextKt.theoreticalDetail(ui: ui),
                color: Palette.onSurfaceVariant,
                size: 20
            )
            if ui.gainSeconds != nil { SummaryConfidence(confidence: ui.gainConfidence, about: "Time to gain") }
            if ui.theoreticalMillis != nil { Dim(ProvenanceMarkKt.ProvenanceLegendText) }
        }
        .accessibilityIdentifier("lapTimes")
    }
}

private struct SummaryFigure: View {
    let label: String
    let value: String
    let detail: String
    let color: Color
    var size: CGFloat = 26

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            SectionTitle(label)
            Text(value)
                .font(.system(size: size, weight: .semibold).monospacedDigit())
                .foregroundStyle(color)
            Dim(detail)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The session's heart rate (FBBD-225, UI-107 A), only when it recorded heart rate (UI-109).
struct SummaryHeartRateCard: View {
    let hr: HeartRateUi

    var body: some View {
        Card {
            SectionTitle(hr.device.map { "Heart rate · \($0)" } ?? "Heart rate")
            HStack(alignment: .top, spacing: 16) {
                SummaryFigure(label: "Average", value: "\(hr.averageBpm)", detail: "bpm", color: Palette.onBackground, size: 22)
                SummaryFigure(label: "Max", value: "\(hr.maxBpm)", detail: "bpm", color: Palette.onBackground, size: 22)
                SummaryFigure(label: "Min", value: "\(hr.minBpm)", detail: "bpm", color: Palette.onBackground, size: 22)
            }
            Dim("Whole session, \(hr.minutes) min")
        }
        .accessibilityIdentifier("heartRateSummary")
    }
}

/// Where time goes (FBBD-103, UI-67): where the fastest lap is slowest against the best time
/// through each corner, with the lap that set it, then the other laps' largest single losses
/// against the fastest. Tapping a loss compares that lap. Below High confidence a time is said
/// without its number, and why is shown (FBBD-177). The first row of each corner carries Android's
/// coach flag (FBBD-363, FBBD-148): the spoken coach works on a flagged corner first next time there.
struct SummaryTimeGoesCard: View {
    let ui: AnalysisUi
    var summary: SessionSummary? = nil
    let compare: (Int) -> Void
    @State private var flags: CoachFlags?

    var body: some View {
        Card {
            SectionTitle("Where time goes")
            if !ui.gains.isEmpty {
                Dim(TimeGoesTextKt.timeGoesHeading(ui: ui))
                ForEach(Array(ui.gains.enumerated()), id: \.offset) { i, gain in
                    HStack(spacing: 4) {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text(gain.name).foregroundStyle(Palette.onBackground).frame(maxWidth: .infinity, alignment: .leading)
                                Dim(TimeGoesTextKt.setOnText(gain: gain)).frame(width: 80, alignment: .leading)
                                SummaryLoss(text: TimeGoesTextKt.gainLossText(gain: gain), confidence: gain.confidence)
                            }
                            SummaryConfidence(confidence: gain.confidence)
                        }
                        flag(flags?.gainFlag(ui: ui, index: Int32(i)), name: gain.name)
                    }
                }
            }
            if !ui.losses.isEmpty {
                Dim("Largest losses on other laps · tap to compare")
                ForEach(Array(ui.losses.enumerated()), id: \.offset) { i, loss in
                    HStack(spacing: 4) {
                        Button { compare(Int(loss.lapIndex)) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    Dim("Lap \(loss.lapNumber)").frame(width: 56, alignment: .leading)
                                    Text(loss.name).foregroundStyle(Palette.onBackground).frame(maxWidth: .infinity, alignment: .leading)
                                    SummaryLoss(text: TimeGoesTextKt.lossLossText(loss: loss), confidence: loss.confidence)
                                }
                                SummaryConfidence(confidence: loss.confidence)
                            }
                            .padding(.vertical, 4)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        flag(flags?.lossFlag(ui: ui, index: Int32(i)), name: loss.name)
                    }
                }
            }
            if flags?.any(ui: ui) == true { Dim(AnalysisUiKt.CoachFlagNote) }
        }
        // No identifier on the card: its flags carry their own.
        .task(id: summary?.id) { await loadFlags() }
    }

    /// The flag of corner [cornerId] at the end of a row; an empty slot keeps the column when the row
    /// has none, so the rows line up. No flags until the track's picks have been read.
    @ViewBuilder private func flag(_ cornerId: String?, name: String) -> some View {
        if let cornerId, let flags {
            let picked = flags.isPicked(cornerId: cornerId)
            Button { toggle(cornerId) } label: {
                Image(systemName: picked ? "flag.fill" : "flag")
                    .foregroundStyle(picked ? Palette.cold : Palette.onSurfaceVariant)
                    .frame(width: 40, height: 40)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(picked ? "Coaching \(name) next time" : "Coach \(name) next time")
            .accessibilityIdentifier("coachFlag-\(cornerId)")
        } else if flags?.any(ui: ui) == true {
            Color.clear.frame(width: 40, height: 40)
        }
    }

    private func loadFlags() async {
        guard let summary else { return }
        flags = await Task.detached(priority: .userInitiated) { ReviewBridge.coachFlags(summary) }.value
    }

    private func toggle(_ cornerId: String) {
        guard let summary else { return }
        Task {
            flags = await Task.detached(priority: .userInitiated) {
                ReviewBridge.toggleCoachFlag(summary, cornerId: cornerId)
            }.value
        }
    }
}

/// A time lost: a number at High confidence in the loss colour, a word below it, dimmed.
private struct SummaryLoss: View {
    let text: String
    let confidence: ConfidenceUi

    var body: some View {
        Text(text)
            .fontWeight(.semibold)
            .monospacedDigit()
            .foregroundStyle(confidence.doubtful ? Palette.onSurfaceVariant : Palette.loss)
            .frame(width: 64, alignment: .leading)
    }
}

/// G-forces of the whole session (GForcesCard.kt): its G-G diagram while the car moved and the
/// peaks. Needs no laps, so a road drive shows it too.
struct SummaryGForcesCard: View {
    let trace: GgTrace

    var body: some View {
        Card {
            SectionTitle("G-forces · whole session")
            SummaryGgChart(trace: trace)
                .aspectRatio(1, contentMode: .fit)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
                .accessibilityElement()
                .accessibilityLabel("G-G diagram")
                .accessibilityIdentifier("ggChart")
            Dim(TimeGoesTextKt.ggPeaksText(trace: trace))
            Dim(TimeGoesTextKt.ggSourceNote(source: trace.source))
        }
        .accessibilityIdentifier("gForces")
    }
}

/// Android's GgChart (FBBD-149): every reading of [trace] as a dot, speeding up toward the top, left
/// turns to the left, a ring every 0.5 g (solid every 1 g), over [reference] (the best lap) in its
/// own colour; [cursor] is the reading at the map cursor, ringed.
struct SummaryGgChart: View {
    let trace: GgTrace
    var reference: GgTrace? = nil
    var color: Color = Palette.best
    var referenceColor: Color = Palette.best
    var cursor: GgPoint? = nil

    var body: some View {
        let all = trace.points + (reference?.points ?? [])
        let rings = MapUiKt.ggRings(maxG: all.map { hypot($0.lateralG, $0.longitudinalG) }.max() ?? 0).map { $0.doubleValue }
        Canvas { ctx, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) / 2 - 14
            let perG = radius / CGFloat(rings.last ?? 1)
            func at(_ p: GgPoint) -> CGPoint {
                CGPoint(x: center.x - CGFloat(p.lateralG) * perG, y: center.y - CGFloat(p.longitudinalG) * perG)
            }
            for ring in rings {
                let r = CGFloat(ring) * perG
                let solid = ring.truncatingRemainder(dividingBy: 1) == 0
                let circle = Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: 2 * r, height: 2 * r))
                ctx.stroke(
                    circle,
                    with: .color(solid ? Palette.onSurfaceVariant.opacity(0.5) : Palette.outline),
                    style: StrokeStyle(lineWidth: 1, dash: solid ? [] : [4, 4])
                )
                let along = r * 0.70710677
                ctx.draw(
                    Text(TimeGoesTextKt.ggRingText(ring: ring)).font(.system(size: 10)).foregroundStyle(Palette.onSurfaceVariant),
                    at: CGPoint(x: center.x + along, y: center.y - along)
                )
            }
            var axes = Path()
            axes.move(to: CGPoint(x: center.x - radius, y: center.y))
            axes.addLine(to: CGPoint(x: center.x + radius, y: center.y))
            axes.move(to: CGPoint(x: center.x, y: center.y - radius))
            axes.addLine(to: CGPoint(x: center.x, y: center.y + radius))
            ctx.stroke(axes, with: .color(Palette.outline), lineWidth: 1)
            ctx.draw(Text("Accel").font(.system(size: 10)).foregroundStyle(Palette.onSurfaceVariant),
                     at: CGPoint(x: center.x, y: center.y - radius - 6))
            ctx.draw(Text("Brake").font(.system(size: 10)).foregroundStyle(Palette.onSurfaceVariant),
                     at: CGPoint(x: center.x, y: center.y + radius + 6))
            // The best lap first, so the shown lap reads on top.
            let layers: [(GgTrace?, Color)] = [(reference, referenceColor.opacity(0.55)), (trace, color.opacity(0.8))]
            for (g, c) in layers {
                guard let g else { continue }
                var dots = Path()
                for p in g.points {
                    let o = at(p)
                    dots.addEllipse(in: CGRect(x: o.x - 1.5, y: o.y - 1.5, width: 3, height: 3))
                }
                ctx.fill(dots, with: .color(c))
            }
            if let cursor {
                let o = at(cursor)
                ctx.stroke(Path(ellipseIn: CGRect(x: o.x - 6, y: o.y - 6, width: 12, height: 12)),
                           with: .color(Palette.onBackground), lineWidth: 2)
            }
        }
    }
}

/// Acceleration runs (FBBD-154, AccelerationCard.kt): the quickest run of each kind with its trap
/// speed and peak forward g. A kind with more runs opens to list them, with when each started.
struct SummaryAccelerationCard: View {
    let ui: AccelerationUi
    @State private var open: Set<String> = []

    var body: some View {
        Card {
            SectionTitle("Acceleration")
            ForEach(Array(ui.kinds.enumerated()), id: \.offset) { _, kind in
                let more = Int(kind.count) - 1
                let isOpen = open.contains(kind.label)
                Button {
                    if isOpen { open.remove(kind.label) } else { open.insert(kind.label) }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(kind.label).foregroundStyle(Palette.onBackground).frame(maxWidth: .infinity, alignment: .leading)
                            Dim(Self.detail(kind.best)).padding(.trailing, 12)
                            SummaryRunTime(run: kind.best, best: true)
                        }
                        if !kind.best.note.isEmpty { Dim(kind.best.note, color: Palette.warning) }
                        if more > 0 { Dim(isOpen ? "Every run" : "\(more) more \(more == 1 ? "run" : "runs")") }
                        if isOpen {
                            ForEach(Array(kind.others.enumerated()), id: \.offset) { _, run in
                                HStack {
                                    Dim("at \(Self.clock(run.atSeconds))").frame(maxWidth: .infinity, alignment: .leading)
                                    Dim(Self.detail(run)).padding(.trailing, 12)
                                    SummaryRunTime(run: run, best: false)
                                }
                                if !run.note.isEmpty { Dim(run.note, color: Palette.warning) }
                            }
                            let unlisted = more - kind.others.count
                            if unlisted > 0 { Dim("and \(unlisted) slower") }
                        }
                    }
                    .padding(.vertical, 4)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(kind.others.isEmpty)
            }
            Dim("Standing starts without rollout. GPS speed.")
        }
        .accessibilityIdentifier("acceleration")
    }

    /// Trap speed and peak g, as far as the run has them.
    private static func detail(_ run: RunRow) -> String {
        [
            run.trapKmh.map { "\(Int($0.doubleValue.rounded())) km/h" },
            run.peakG.map { "\(CoreText.fixed($0.doubleValue, 2)) g" },
        ].compactMap { $0 }.joined(separator: " · ")
    }

    /// Session time as m:ss, or h:mm:ss.
    private static func clock(_ seconds: Double) -> String {
        let total = max(0, Int(seconds.rounded()))
        let s = total % 60, minutes = total / 60
        let h = minutes / 60, m = minutes % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}

private struct SummaryRunTime: View {
    let run: RunRow
    let best: Bool

    var body: some View {
        // "≈" marks a time the GPS data makes less certain.
        Text((run.note.isEmpty ? "" : "≈") + "\(CoreText.fixed(run.seconds, 2)) s")
            .fontWeight(best ? .semibold : .regular)
            .monospacedDigit()
            .foregroundStyle(!run.note.isEmpty ? Palette.warning : (best ? Palette.best : Palette.onBackground))
            .frame(width: 72, alignment: .trailing)
    }
}
