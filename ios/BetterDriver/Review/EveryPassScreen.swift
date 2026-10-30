import AVFoundation
import SwiftUI
import UIKit
import BetterDriverCore

// Same corner across laps (FBBD-153, FBBD-93), as Android's Corner laps screen (CornerLapsScreen.kt):
// every counted lap's pass through one corner, fastest first, with every pass's speed, brake and
// throttle over the corner, the pass on video, and two ticked laps side by side in the lap review.
// The screen model and its words are the shared core's.

/// Opens every pass through a corner (its segment id); nil where nothing can show it, and then the
/// corners offer no "Every pass", as Android's LocalCornerLaps.
private struct EveryPassKey: EnvironmentKey {
    static let defaultValue: ((String) -> Void)? = nil
}

extension EnvironmentValues {
    var everyPass: ((String) -> Void)? {
        get { self[EveryPassKey.self] }
        set { self[EveryPassKey.self] = newValue }
    }
}

/// A corner of a session to show every pass through.
struct EveryPassLink: Identifiable {
    let summary: SessionSummary
    let cornerId: String
    var id: String { cornerId }
}

/// Android's "Every pass ›" on a corner: nothing where the screen cannot show the corner's passes.
struct EveryPassButton: View {
    let cornerId: String?
    @Environment(\.everyPass) private var everyPass

    var body: some View {
        if let everyPass, let cornerId, !cornerId.isEmpty {
            Button(CornerPassesTextKt.EveryPassText) { everyPass(cornerId) }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Palette.accent)
                .buttonStyle(.borderless)
                .accessibilityIdentifier("everyPass")
        }
    }
}

/// The two ticked laps, opened side by side in the lap review.
private struct EveryPassPair: Hashable, Identifiable {
    let a: Int
    let b: Int
    var id: String { "\(a)-\(b)" }
}

/// The Corner laps screen of [link]'s corner, read off the main thread.
struct EveryPassScreen: View {
    let link: EveryPassLink
    @Environment(\.dismiss) private var dismiss
    @State private var ui: CornerLapsUi?
    @State private var failed = false
    @State private var ticked: [Int] = []
    @State private var shown: Int?
    @State private var compared: EveryPassPair?
    @StateObject private var player = CornerClipPlayer()
    @Environment(\.horizontalSizeClass) private var size

    var body: some View {
        NavigationStack {
            Group {
                if let ui {
                    VStack(spacing: 0) {
                        if size == .regular { wide(ui) } else { narrow(ui) }
                        compareBar(ui)
                    }
                } else if failed {
                    Dim("This corner could not be read.").padding(40)
                } else {
                    ProgressView().tint(Palette.accent)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Palette.background)
            .navigationTitle(CornerPassesTextKt.EveryPassTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }.accessibilityIdentifier("everyPassDone")
                }
            }
            .navigationDestination(item: $compared) { pair in
                EveryPassReview(summary: link.summary, cornerId: link.cornerId, pair: pair)
            }
        }
        .preferredColorScheme(.dark)
        .onDisappear { player.stop() }
        .task {
            let summary = link.summary, corner = link.cornerId
            ui = await Task.detached(priority: .userInitiated) {
                ReviewBridge.everyPass(summary, cornerId: corner)
            }.value
            failed = ui == nil
        }
    }

    /// The tablet: the list on the left, the player and the traces beside it.
    private func wide(_ ui: CornerLapsUi) -> some View {
        HStack(alignment: .top, spacing: 16) {
            ScrollView {
                VStack(spacing: 12) {
                    EveryPassHeader(ui: ui)
                    lapList(ui, inline: false)
                }
            }
            .frame(maxWidth: .infinity)
            ScrollView {
                VStack(spacing: 12) {
                    // Without video the traces have the column to themselves.
                    if ui.anyVideo { playerPane(ui) }
                    if let traces = ui.traces { EveryPassTraces(ui: ui, traces: traces, highlight: highlight(ui)) }
                }
            }
            .frame(maxWidth: .infinity)
            .layoutPriority(1.3)
        }
        .padding(16)
    }

    /// The phone: the header, the traces, then the list with the pass playing under its row.
    private func narrow(_ ui: CornerLapsUi) -> some View {
        ScrollView {
            VStack(spacing: 12) {
                EveryPassHeader(ui: ui)
                if let traces = ui.traces { EveryPassTraces(ui: ui, traces: traces, highlight: highlight(ui)) }
                lapList(ui, inline: true)
            }
            .padding(16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
    }

    @ViewBuilder private func lapList(_ ui: CornerLapsUi, inline: Bool) -> some View {
        if !ui.rows.isEmpty {
            Card {
                SectionTitle("Time through the corner")
                Dim("Brakes: metres before the corner · Min: lowest speed in it")
                ForEach(ui.rows, id: \.lapIndex) { row in
                    let lap = Int(row.lapIndex)
                    EveryPassRow(
                        ui: ui, row: row, ticked: ticked.contains(lap), shown: shown == lap,
                        tick: { tick(lap) }, play: { play(row) }
                    )
                    if inline, shown == lap, row.video != nil {
                        CornerClipView(player: player)
                            .aspectRatio(16 / 9, contentMode: .fit)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                }
                Dim("Tick two laps to compare them in the lap review.")
            }
        }
    }

    /// The tablet's player beside the list: the pass picked to play, or what to do to play one.
    private func playerPane(_ ui: CornerLapsUi) -> some View {
        Card {
            if let lap = shown, let row = ui.row(lapIndex: Int32(lap)), row.video != nil {
                HStack {
                    Text("Lap \(row.lapNumber)").fontWeight(.semibold).foregroundStyle(Palette.onBackground)
                    Spacer()
                    Text(CornerPassesTextKt.passSecondsText(row: row))
                        .fontWeight(.semibold)
                        .foregroundStyle(row.best ? Palette.best : Palette.onBackground)
                }
                CornerClipView(player: player)
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                Dim("From just before the braking point to the corner exit. Tap the picture to pause.")
            } else {
                SectionTitle(ui.name)
                Dim(ui.anyVideo ? "Press Play on a lap to watch its pass through the corner." : "No video to play.")
            }
        }
    }

    /// Under the list: Compare, once two laps are ticked.
    @ViewBuilder private func compareBar(_ ui: CornerLapsUi) -> some View {
        if ui.rows.contains(where: { $0.reviewable }) {
            let pair = CornerLapsUiKt.comparedPair(ui: ui, ticked: kotlinTicked)
            HStack {
                Dim(CornerPassesTextKt.compareText(ui: ui, pair: pair)).frame(maxWidth: .infinity, alignment: .leading)
                Button("Compare") {
                    if let a = pair?.first?.intValue, let b = pair?.second?.intValue {
                        player.stop()
                        compared = EveryPassPair(a: Int(a), b: Int(b))
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(Palette.accent)
                .foregroundStyle(Palette.onAccent)
                .disabled(pair == nil)
                .accessibilityIdentifier("everyPassCompare")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Palette.surface)
        }
    }

    private var kotlinTicked: [KotlinInt] { ticked.map { KotlinInt(value: Int32($0)) } }

    private func highlight(_ ui: CornerLapsUi) -> Highlight {
        CornerLapsUiKt.highlightedPasses(ui: ui, ticked: kotlinTicked)
    }

    private func tick(_ lap: Int) {
        ticked = CornerLapsUiKt.toggleTicked(ticked: kotlinTicked, lapIndex: Int32(lap)).map { Int($0.intValue) }
    }

    private func play(_ row: CornerLapRow) {
        shown = Int(row.lapIndex)
        if let video = row.video { player.play(video) }
    }
}

/// The corner's name and how many laps went through it.
private struct EveryPassHeader: View {
    let ui: CornerLapsUi

    var body: some View {
        Card {
            Text(ui.name).font(.title2.weight(.semibold)).foregroundStyle(Palette.onBackground)
            Dim(count)
            if !ui.anyVideo && !ui.rows.isEmpty { Dim(CornerPassesTextKt.NoVideoText) }
            if ui.anyMarked { Dim(CoreText.legend) }
        }
        .accessibilityIdentifier("everyPassHeader")
    }

    private var count: String {
        if ui.notInSession { return CornerPassesTextKt.NotInSessionText }
        switch ui.rows.count {
        case 0: return "No counted lap went through this corner."
        case 1: return "1 counted lap through this corner."
        default: return "\(ui.rows.count) counted laps, fastest through the corner first."
        }
    }
}

/// One lap's pass: its tick, time, delta to the fastest, braking point and minimum speed, and Play.
private struct EveryPassRow: View {
    let ui: CornerLapsUi
    let row: CornerLapRow
    let ticked: Bool
    let shown: Bool
    let tick: () -> Void
    let play: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button(action: tick) {
                HStack(spacing: 8) {
                    Image(systemName: ticked ? "checkmark.square.fill" : "square")
                        .font(.title3)
                        .foregroundStyle(ticked ? Palette.accent : Palette.onSurfaceVariant)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Circle().fill(row.best ? Palette.best : .clear).frame(width: 8, height: 8)
                            Text("Lap \(row.lapNumber)").foregroundStyle(Palette.onSurfaceVariant).frame(width: 56, alignment: .leading)
                            Text(CornerPassesTextKt.passSecondsText(row: row))
                                .fontWeight(.semibold)
                                .foregroundStyle(row.best ? Palette.best : Palette.onBackground)
                                .frame(width: 72, alignment: .leading)
                            if row.best {
                                Text("Best").foregroundStyle(Palette.best)
                            } else if let delta = row.deltaSeconds {
                                Text(CoreBridge.delta(seconds: delta.doubleValue)).foregroundStyle(Palette.loss)
                            }
                        }
                        .font(.body.monospacedDigit())
                        Dim(CornerPassesTextKt.passDetailText(ui: ui, row: row)).padding(.leading, 14)
                        if row.confidence.doubtful {
                            Dim(row.confidence.note, color: Palette.warning).padding(.leading, 14)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!row.reviewable)
            .accessibilityIdentifier("passRow")
            if row.video == nil {
                Dim("No video")
            } else {
                Button(shown ? "Again" : "Play", action: play)
                    .buttonStyle(.bordered)
                    .tint(shown ? Palette.accent : Palette.onSurfaceVariant)
            }
        }
        .padding(.vertical, 4)
        .padding(.horizontal, 4)
        .background(shown ? Palette.surfaceVariant : .clear, in: RoundedRectangle(cornerRadius: 8))
    }
}

/// Every pass's speed, brake and throttle over the corner and its approach, the corner shaded; the
/// highlighted two strong in lap A's and lap B's colours, the rest thin and dim (CornerTracesCard.kt).
private struct EveryPassTraces: View {
    let ui: CornerLapsUi
    let traces: CornerTraces
    let highlight: Highlight

    var body: some View {
        Card {
            chart(CornerPassesTextKt.speedLabel(ui: ui, traces: traces), height: 120) { $0.speed }
            if traces.hasBrake {
                chart(CoreText.marked("Brake", traces.brakeProvenance), height: 56) { $0.brake }
            }
            if traces.hasThrottle {
                chart(CoreText.marked("Throttle", traces.throttleProvenance), height: 56) { $0.throttle }
            }
            HStack(spacing: 12) {
                if let a = highlight.a, let row = ui.row(lapIndex: a.int32Value) {
                    key(CornerPassesTextKt.passName(row: row), PassColor.a)
                }
                if let b = highlight.b, let row = ui.row(lapIndex: b.int32Value) {
                    key(CornerPassesTextKt.passName(row: row), PassColor.b)
                }
                if ui.rows.count > [highlight.a, highlight.b].compactMap({ $0 }).count {
                    key("Other laps", PassColor.other)
                }
            }
            Dim("Shaded: the corner, with \(CornerPassesTextKt.approachText(traces: traces)) of approach before it.")
        }
        .accessibilityIdentifier("cornerTraces")
    }

    private func chart(_ label: String, height: CGFloat, _ channel: @escaping (PassTrace) -> [[ChartPoint]]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            SectionTitle(label)
            EveryPassChart(traces: traces, highlight: highlight, channel: channel).frame(height: height)
        }
    }

    private func key(_ text: String, _ color: Color) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 8, height: 8)
            Dim(text)
        }
    }
}

/// Lap A's and lap B's colours, as in the lap review, and every other lap's.
private enum PassColor {
    static let a = Palette.onBackground
    static let b = Palette.cold
    static let other = Palette.onSurfaceVariant.opacity(0.35)
}

/// One channel of every pass by distance, the highlighted ones drawn last so they are on top.
private struct EveryPassChart: View {
    let traces: CornerTraces
    let highlight: Highlight
    let channel: (PassTrace) -> [[ChartPoint]]

    var body: some View {
        Canvas { ctx, size in
            let length = max(1, traces.lengthMeters)
            let all = traces.passes.flatMap { channel($0).flatMap { $0 } }.map(\.value)
            let low = all.min() ?? 0, high = all.max() ?? 1
            let span = max(high - low, 1e-6)
            func point(_ p: ChartPoint) -> CGPoint {
                CGPoint(x: size.width * p.distanceMeters / length, y: size.height * (1 - (p.value - low) / span))
            }
            let band = CGRect(
                x: size.width * traces.entryMeters / length, y: 0,
                width: size.width * max(0, traces.exitMeters - traces.entryMeters) / length, height: size.height
            )
            ctx.fill(Path(band), with: .color(Palette.outline.opacity(0.6)))
            func draw(_ pass: PassTrace, _ color: Color, _ width: CGFloat) {
                for run in channel(pass) where run.count > 1 {
                    var path = Path()
                    path.move(to: point(run[0]))
                    for p in run.dropFirst() { path.addLine(to: point(p)) }
                    ctx.stroke(path, with: .color(color), lineWidth: width)
                }
            }
            let a = highlight.a?.int32Value, b = highlight.b?.int32Value
            for pass in traces.passes where pass.lapIndex != a && pass.lapIndex != b { draw(pass, PassColor.other, 1) }
            if let pass = traces.passes.first(where: { $0.lapIndex == b }) { draw(pass, PassColor.b, 2.5) }
            if let pass = traces.passes.first(where: { $0.lapIndex == a }) { draw(pass, PassColor.a, 2.5) }
        }
    }
}

/// One pass on video, from just before its braking point to the corner exit, then it stops; tapping
/// the picture pauses or plays it again (CornerClipView.kt).
@MainActor
final class CornerClipPlayer: ObservableObject {
    let player = AVPlayer()
    private var file: String?
    private var end: Any?

    init() {
        player.isMuted = true
        player.actionAtItemEnd = .pause
    }

    func play(_ video: CornerVideoUi) {
        if video.path != file {
            file = video.path
            player.replaceCurrentItem(with: AVPlayerItem(url: URL(fileURLWithPath: video.path)))
        }
        if let end { player.removeTimeObserver(end) }
        let stop = CMTime(value: video.endMillis, timescale: 1000)
        end = player.addBoundaryTimeObserver(forTimes: [NSValue(time: stop)], queue: .main) { [weak self] in
            MainActor.assumeIsolated { self?.player.pause() }
        }
        player.seek(to: CMTime(value: video.startMillis, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            MainActor.assumeIsolated { self?.player.play() }
        }
    }

    func toggle() {
        if player.rate == 0 { player.play() } else { player.pause() }
    }

    func stop() { player.pause() }
}

/// The picture of [player], letterboxed; a tap pauses or plays.
private struct CornerClipView: View {
    @ObservedObject var player: CornerClipPlayer

    var body: some View {
        CornerClipLayer(player: player.player)
            .background(Color.black)
            .onTapGesture { player.toggle() }
            .accessibilityIdentifier("cornerClip")
    }
}

private struct CornerClipLayer: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> LayerView {
        let v = LayerView()
        v.playerLayer.player = player
        v.playerLayer.videoGravity = .resizeAspect
        return v
    }

    func updateUIView(_ uiView: LayerView, context: Context) {}

    final class LayerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}

/// Two ticked passes side by side in the lap review, cued a little before the corner, read off the main thread.
private struct EveryPassReview: View {
    let summary: SessionSummary
    let cornerId: String
    let pair: EveryPassPair
    @State private var review: LapVideoReview?
    @State private var start: Double?
    @State private var done = false

    var body: some View {
        Group {
            if let review {
                LapVideoView(review: review, startFraction: start)
            } else if done {
                Dim("These laps are not in the lap review.").padding(40)
            } else {
                ProgressView().tint(Palette.accent)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.background)
        .task {
            let s = summary, p = pair, corner = cornerId
            let read = await Task.detached(priority: .userInitiated) {
                (ReviewBridge.lapsOnVideo(s, a: p.a, b: p.b), ReviewBridge.reviewStart(s, cornerId: corner))
            }.value
            start = read.1
            review = read.0
            done = true
        }
    }
}
