import SwiftUI
import BetterDriverCore

/// The Compare tab (FBBD-102), as on Android: the time delta and both laps' speed by distance,
/// corners shaded behind; touch or drag to read a point.
struct CompareTab: View {
    let compare: LapCompare?
    let summary: SessionSummary
    /// Android's Compare model of the same two laps, for what the car is doing along them (FBBD-175).
    var states: CompareUi? = nil
    @State private var cursor: Double?

    var body: some View {
        if let compare {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 0) {
                        Text("Lap \(compare.otherNumber)").foregroundStyle(Palette.cold).fontWeight(.semibold)
                        Text(" vs best lap ").foregroundStyle(Palette.onSurfaceVariant)
                        Text("\(compare.referenceNumber)").foregroundStyle(Palette.best).fontWeight(.semibold)
                        Spacer()
                        // Both laps side by side on video, as Android's lap review (FBBD-93).
                        NavigationLink {
                            LapVideoLoader(summary: summary, lapIndex: compare.otherIndex)
                        } label: {
                            Label("Video", systemImage: "play.rectangle")
                        }
                        .foregroundStyle(Palette.accent)
                        .accessibilityIdentifier("openLapVideo")
                    }
                    Readout(compare: compare, cursor: cursor)
                    SectionLabel("Time delta")
                    LapChart(
                        lines: [ChartSeries(runs: compare.delta, color: Palette.warning)],
                        compare: compare,
                        zeroLine: true,
                        cursor: $cursor
                    )
                    .frame(height: 110)
                    .accessibilityIdentifier("deltaChart")
                    SectionLabel("Speed, km/h")
                    LapChart(
                        lines: [
                            ChartSeries(runs: compare.referenceSpeed, color: Palette.best),
                            ChartSeries(runs: compare.otherSpeed, color: Palette.cold),
                        ],
                        compare: compare,
                        zeroLine: false,
                        cursor: $cursor
                    )
                    .frame(height: 150)
                    .accessibilityIdentifier("speedChart")
                    HStack {
                        Text("0 m")
                        Spacer()
                        Text("\(Int(compare.lengthMeters.rounded())) m")
                    }
                    .font(.caption)
                    .foregroundStyle(Palette.onSurfaceVariant)
                    // Where each lap brakes, coasts, corners and drives (FBBD-175), on the same distance scale.
                    if let states, states.hasStates { DrivingStatesStrip(ui: states) }
                }
                .padding(16)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 16))
                .padding(20)
                .frame(maxWidth: 900)
                .frame(maxWidth: .infinity)
            }
            .onChange(of: compare.otherIndex) { cursor = nil }
        } else {
            Text("Nothing to compare: it needs a fastest lap and another counted lap.")
                .foregroundStyle(Palette.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(40)
        }
    }
}

/// Reads the two laps and their video off the main thread, then shows them.
private struct LapVideoLoader: View {
    let summary: SessionSummary
    let lapIndex: Int
    @State private var review: LapVideoReview?
    @State private var done = false

    var body: some View {
        Group {
            if let review {
                LapVideoView(review: review)
            } else if done {
                Text("These laps are not in the lap review.")
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .padding(40)
            } else {
                ProgressView().tint(Palette.accent)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.background)
        .task {
            let s = summary, i = lapIndex
            review = await Task.detached(priority: .userInitiated) { ReviewBridge.videoReview(s, lapIndex: i) }.value
            done = true
        }
    }
}

private struct SectionLabel: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text.uppercased())
            .font(.caption2.weight(.semibold))
            .tracking(0.8)
            .foregroundStyle(Palette.onSurfaceVariant)
            .padding(.top, 6)
    }
}

private struct Readout: View {
    let compare: LapCompare
    let cursor: Double?

    var body: some View {
        if let cursor {
            let delta = ChartMath.value(compare.delta, at: cursor)
            let corner = compare.corners.first { $0.contains(cursor) }?.name
            HStack(spacing: 12) {
                Text("\(Int(cursor.rounded())) m" + (corner.map { " · \($0)" } ?? ""))
                    .foregroundStyle(Palette.onSurfaceVariant)
                Text(delta.map(CoreBridge.delta) ?? "–")
                    .fontWeight(.semibold)
                    .foregroundStyle(delta.map { CoreBridge.isGain(seconds: $0) ? Palette.gain : Palette.loss } ?? Palette.onSurfaceVariant)
                Text(speed(compare.referenceSpeed, cursor)).foregroundStyle(Palette.best)
                Text(speed(compare.otherSpeed, cursor)).foregroundStyle(Palette.cold)
            }
            .font(.subheadline.monospacedDigit())
            .accessibilityIdentifier("compareReadout")
        } else {
            Text("Touch the charts to read a point. Shaded: corners.")
                .font(.subheadline)
                .foregroundStyle(Palette.onSurfaceVariant)
        }
    }

    private func speed(_ runs: [[ChartDot]], _ m: Double) -> String {
        ChartMath.value(runs, at: m).map { "\(Int($0.rounded()))" } ?? "–"
    }
}

struct ChartSeries {
    let runs: [[ChartDot]]
    let color: Color
}

/// Android's LapChart: values by distance, 0 to the lap length, corners shaded, a cursor line.
private struct LapChart: View {
    let lines: [ChartSeries]
    let compare: LapCompare
    let zeroLine: Bool
    @Binding var cursor: Double?

    var body: some View {
        GeometryReader { geo in
            let span = ChartMath.range(lines.map(\.runs), includeZero: zeroLine)
            let low = span.0, high = span.1
            let length = max(1, compare.lengthMeters)
            let w = geo.size.width, h = geo.size.height
            let x = { (m: Double) in CGFloat(m / length) * w }
            let y = { (v: Double) in CGFloat((high - v) / (high - low)) * h }
            Canvas { ctx, _ in
                for band in compare.corners {
                    let rects: [(Double, Double)] = band.end >= band.start
                        ? [(band.start, band.end)]
                        : [(band.start, length), (0.0, band.end)]
                    for (a, b) in rects {
                        ctx.fill(Path(CGRect(x: x(a), y: 0, width: x(b) - x(a), height: h)), with: .color(Palette.surfaceVariant))
                    }
                }
                if zeroLine && low < 0 && high > 0 {
                    var p = Path()
                    p.move(to: CGPoint(x: 0, y: y(0)))
                    p.addLine(to: CGPoint(x: w, y: y(0)))
                    ctx.stroke(p, with: .color(Palette.outline), style: StrokeStyle(lineWidth: 1, dash: [6, 6]))
                }
                for line in lines {
                    for run in line.runs where run.count > 1 {
                        var p = Path()
                        for (i, d) in run.enumerated() {
                            let pt = CGPoint(x: x(d.meters), y: y(d.value))
                            if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
                        }
                        ctx.stroke(p, with: .color(line.color), lineWidth: 2)
                    }
                }
                if let cursor {
                    var p = Path()
                    p.move(to: CGPoint(x: x(cursor), y: 0))
                    p.addLine(to: CGPoint(x: x(cursor), y: h))
                    ctx.stroke(p, with: .color(Palette.onBackground), lineWidth: 1)
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                cursor = min(length, max(0, Double(g.location.x / max(1, w)) * length))
            })
        }
    }
}

/// The chart arithmetic of Android's LapChart and ChartMath.
enum ChartMath {
    private static let minimumSpan = 0.2
    private static let rangePadding = 0.08

    /// The value of [runs] at [meters], interpolated within a run; nil outside every run.
    static func value(_ runs: [[ChartDot]], at meters: Double) -> Double? {
        for run in runs {
            guard let first = run.first, let last = run.last, meters >= first.meters, meters <= last.meters else { continue }
            var i = 1
            while i < run.count && run[i].meters < meters { i += 1 }
            if i >= run.count { return last.value }
            let a = run[i - 1], b = run[i]
            let span = b.meters - a.meters
            return span <= 0 ? b.value : a.value + (b.value - a.value) * (meters - a.meters) / span
        }
        return nil
    }

    /// The value range the lines span, padded so they do not touch the edges.
    static func range(_ lines: [[[ChartDot]]], includeZero: Bool) -> (Double, Double) {
        var low = includeZero ? 0 : Double.infinity
        var high = includeZero ? 0 : -Double.infinity
        for run in lines.joined() { for p in run { low = min(low, p.value); high = max(high, p.value) } }
        if !low.isFinite || !high.isFinite { return (0, 1) }
        if high - low < minimumSpan {
            let middle = (high + low) / 2
            return (middle - minimumSpan / 2, middle + minimumSpan / 2)
        }
        let pad = (high - low) * rangePadding
        return (low - pad, high + pad)
    }
}

/// What each compared lap's car was doing along the lap (FBBD-175, DrivingStatesStrip.kt): one thin
/// strip per lap on the speed chart's distance scale, a key, and how long each lap coasted. The
/// states, names and words are the shared core's.
private struct DrivingStatesStrip: View {
    let ui: CompareUi

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionLabel(CompareModelKt.drivingStatesLabel(ui: ui))
            strip("Lap \(ui.referenceNumber)", ui.referenceStates, Palette.best)
            strip("Lap \(ui.otherNumber)", ui.otherStates, Palette.cold)
            keyText
                .font(.caption)
                .foregroundStyle(Palette.onSurfaceVariant)
            if let coasting = CompareModelKt.coastingText(ui: ui) { Dim(coasting) }
        }
        .padding(.top, 8)
        .accessibilityIdentifier("drivingStates")
    }

    /// The key: a coloured square and the name of each state.
    private var keyText: Text {
        var text = Text("")
        for (i, key) in CompareModelKt.DrivingStateKeys.enumerated() {
            if i > 0 { text = text + Text("  ") }
            text = text + Text("■ ").foregroundColor(Self.color(key.state)) + Text(key.name)
        }
        return text
    }

    private func strip(_ label: String, _ runs: [DrivingStateRun], _ color: Color) -> some View {
        HStack(spacing: 0) {
            Text(label).font(.caption).foregroundStyle(color).frame(width: 52, alignment: .leading)
            Canvas { ctx, size in
                ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Palette.surfaceVariant))
                let length = ui.lengthMeters
                guard length > 0 else { return }
                for run in runs {
                    let from = min(max(run.startMeters / length, 0), 1) * size.width
                    let to = min(max(run.endMeters / length, 0), 1) * size.width
                    if to > from {
                        ctx.fill(Path(CGRect(x: from, y: 0, width: to - from, height: size.height)), with: .color(Self.color(run.state)))
                    }
                }
            }
            .frame(height: 10)
            .accessibilityLabel("\(label): what the car is doing along the lap")
        }
    }

    /// The pedals in the gain and loss colours, coasting in the warning one, as on Android. By the
    /// state's Kotlin name: a multi-word Kotlin enum entry has no reliable Swift spelling.
    static func color(_ state: DrivingState) -> Color {
        switch state.name {
        case "Braking": Palette.loss
        case "Coasting": Palette.warning
        case "Cornering": Palette.cold
        case "Accelerating": Palette.gain.opacity(0.5)
        default: Palette.gain
        }
    }
}
