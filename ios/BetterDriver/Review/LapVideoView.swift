import AVFoundation
import QuartzCore
import SwiftUI
import UIKit
import BetterDriverCore

// Two laps with their videos, as Android's lap review (FBBD-93, CompareView.kt), for the iPad and
// iPhone (FBBD-331). Both start at the line together and run on time since the line, so the faster
// car pulls ahead; one cursor moves both; drag or tap the chart to scrub. The timing is the shared
// core's TraceComparison and followStep, so the players follow the laps as Android's do.

/// Review speeds, one tap apart: real time, half and quarter.
private let speeds = [1.0, 0.5, 0.25]
private let speedLabels = ["1×", "½×", "¼×"]

/// One lap's video: an AVPlayer that follows a target frame given every display frame while the
/// review moves. While scrubbing it seeks loosely, which keeps dragging smooth; when the finger
/// lifts it seeks to the exact frame (LapVideoPlayer.kt).
@MainActor
final class LapPlayer: ObservableObject {
    let player = AVPlayer()
    /// There is video at the current place of the lap.
    @Published private(set) var showing = false

    private var file: String?
    /// Where the player was last sent; nil when the next frame must be sought again whatever it is.
    private var lastSeekMillis: Int64?
    private var lastSeekTime: CFTimeInterval = 0
    private var speed: Float = 1
    private var exact = true
    /// It played and has not been sent to its exact frame since it stopped (FBBD-214).
    private var stopping = false
    private var playingNow = false

    init(muted: Bool) {
        player.isMuted = muted
        player.automaticallyWaitsToMinimizeStalling = false
        player.actionAtItemEnd = .pause
    }

    /// Moves the player towards [target] of [lap]; returns whether a seek is still to be made.
    func follow(_ target: VideoTarget?, lap: VideoLap, rate: Double, playing: Bool, scrubbing: Bool, now: CFTimeInterval) -> Bool {
        let shown = target != nil
        if showing != shown { showing = shown }
        guard let target else {
            pause()
            return false
        }
        let path = lap.path(target: target)
        if path != file {
            file = path
            player.replaceCurrentItem(with: AVPlayerItem(url: URL(fileURLWithPath: path)))
            seek(target.mediaMillis, now: now)
        }
        precise(!scrubbing)
        if playing {
            stopping = false
        } else if playingNow {
            // Stopping now. The pause goes first, so the exact seek below is the player's last word.
            stopping = true
            lastSeekMillis = nil
            pause()
        }
        let position = Int64((player.currentTime().seconds.isFinite ? player.currentTime().seconds : 0) * 1000)
        let step = LapFollowKt.followStep(
            positionMillis: position, targetMillis: target.mediaMillis, rate: rate, playing: playing, justStopped: stopping
        )
        let gap = playing ? 0.3 : 0.05
        let last = lastSeekMillis.map { KotlinLong(value: $0) }
        // Wanted before this frame's seek, so the frame after a seek still runs and sees it land.
        let pending = LapFollowKt.seekPending(step: step, lastSeekMillis: last)
        if let ms = step.seekMillis?.int64Value, LapFollowKt.seekDue(targetMillis: ms, lastMillis: last), now - lastSeekTime >= gap {
            seek(ms, now: now)
            stopping = false
        }
        speed = step.speed
        if playing {
            if !playingNow || player.rate != speed { player.rate = speed }
            playingNow = true
        } else if playingNow {
            pause()
        }
        return pending
    }

    func pause() {
        player.pause()
        playingNow = false
    }

    private func seek(_ ms: Int64, now: CFTimeInterval) {
        let tolerance: CMTime = exact ? .zero : .positiveInfinity
        player.seek(to: CMTime(value: ms, timescale: 1000), toleranceBefore: tolerance, toleranceAfter: tolerance)
        lastSeekMillis = ms
        lastSeekTime = now
    }

    /// Exact seeks when still, loose seeks while scrubbing; going exact seeks the frame again.
    private func precise(_ on: Bool) {
        if on == exact { return }
        exact = on
        if on { lastSeekMillis = nil }
    }
}

/// The review cursor (time since the start line), play state and speed, moved one step a display
/// frame while the review moves; still, the display link stops (Android's followFrames).
@MainActor
final class ReviewTransport: ObservableObject {
    let review: LapVideoReview
    let a = LapPlayer(muted: false)
    let b = LapPlayer(muted: true)
    @Published var cursor: Int64
    @Published private(set) var playing = false
    @Published private(set) var scrubbing = false
    @Published private(set) var speed = speeds[0]

    private var link: CADisplayLink?
    private var lastFrame: CFTimeInterval = 0

    init(review: LapVideoReview) {
        self.review = review
        cursor = 0
    }

    var traces: TraceComparison { review.traces }
    var lengthMicros: Int64 { traces.lengthMicros }

    func playPause() {
        if cursor >= lengthMicros { cursor = 0 }
        playing.toggle()
        wake()
    }

    func nextSpeed() {
        speed = speeds[((speeds.firstIndex(of: speed) ?? 0) + 1) % speeds.count]
        wake()
    }

    func scrub(to fraction: Double, ended: Bool) {
        if !scrubbing && !ended {
            playing = false
            scrubbing = true
        }
        let length = max(1, review.a.trace.lengthMeters)
        cursor = traces.microsAtDistance(meters: min(max(fraction, 0), 1) * length)
        if ended { scrubbing = false }
        wake()
    }

    func stop() {
        playing = false
        a.pause()
        b.pause()
        link?.invalidate()
        link = nil
    }

    /// Starts the frame loop if it is not running.
    func wake() {
        guard link == nil else { return }
        lastFrame = 0
        let l = CADisplayLink(target: DisplayTarget { [weak self] in self?.frame($0) }, selector: #selector(DisplayTarget.tick(_:)))
        l.add(to: .main, forMode: .common)
        link = l
    }

    private func frame(_ now: CFTimeInterval) {
        if playing && lastFrame != 0 {
            cursor = LapFollowKt.advance(
                lapMicros: cursor, elapsedNanos: Int64((now - lastFrame) * 1e9), speed: speed, lengthMicros: lengthMicros
            )
            if cursor >= lengthMicros { playing = false }
        }
        lastFrame = now
        let t = cursor
        let playA = playing && t < review.a.trace.lap.timeMicros
        let playB = playing && t < review.b.trace.lap.timeMicros
        let seekA = a.follow(review.a.videoAt(lapMicros: traces.aMicros(micros: t)), lap: review.a, rate: speed, playing: playA, scrubbing: scrubbing, now: now)
        let seekB = b.follow(review.b.videoAt(lapMicros: traces.bMicros(micros: t)), lap: review.b, rate: speed, playing: playB, scrubbing: scrubbing, now: now)
        if !LapFollowKt.frameLoopNeeded(playing: playing, scrubbing: scrubbing, seekPending: seekA || seekB) {
            link?.invalidate()
            link = nil
        }
    }
}

/// CADisplayLink holds its target strongly; this keeps the transport out of that cycle.
private final class DisplayTarget: NSObject {
    let onFrame: (CFTimeInterval) -> Void
    init(_ onFrame: @escaping (CFTimeInterval) -> Void) { self.onFrame = onFrame }
    @objc func tick(_ link: CADisplayLink) { onFrame(link.timestamp) }
}

/// Colours of lap A and lap B, the same on the chart, the map and the labels (LapColors).
private enum LapColors {
    static let a = Palette.onBackground
    static let b = Palette.cold
}

/// "Lap 7 · 1:41.89".
private func lapName(_ l: VideoLap) -> String { "Lap \(l.number) · \(CoreBridge.lapTime(millis: l.timeMillis))" }

/// The lap review screen: the videos on the left with the readout, map and chart on the right on a
/// wide screen (UI-59 B); stacked on a phone.
struct LapVideoView: View {
    @StateObject private var st: ReviewTransport
    @Environment(\.horizontalSizeClass) private var size
    @Environment(\.scenePhase) private var phase

    /// Where the cursor starts, as a share of lap A; nil for the start line.
    private let startFraction: Double?

    init(review: LapVideoReview, startFraction: Double? = nil) {
        _st = StateObject(wrappedValue: ReviewTransport(review: review))
        self.startFraction = startFraction
    }

    var body: some View {
        let r = st.review
        VStack(alignment: .leading, spacing: 0) {
            Text("\(lapName(r.a))  vs  \(lapName(r.b))")
                .font(.headline)
                .foregroundStyle(Palette.onBackground)
                .lineLimit(1)
                .frame(height: 56)
            if size == .regular {
                HStack(spacing: 12) {
                    VStack(spacing: 8) {
                        LapPane(lap: r.a, letter: "A", color: LapColors.a, player: st.a)
                        LapPane(lap: r.b, letter: "B", color: LapColors.b, player: st.b)
                    }
                    .frame(maxWidth: .infinity)
                    .layoutPriority(1.4)
                    VStack(spacing: 12) {
                        TransportRow(st: st)
                        LapMapView(st: st).frame(maxHeight: .infinity)
                        DeltaChart(st: st).frame(height: 180)
                    }
                    .frame(maxWidth: .infinity)
                }
                .padding(.bottom, 12)
            } else {
                ScrollView {
                    VStack(spacing: 8) {
                        LapPane(lap: r.a, letter: "A", color: LapColors.a, player: st.a).frame(height: 200)
                        LapPane(lap: r.b, letter: "B", color: LapColors.b, player: st.b).frame(height: 200)
                        TransportRow(st: st)
                        DeltaChart(st: st).frame(height: 180)
                        LapMapView(st: st).frame(height: 180).padding(.bottom, 12)
                    }
                }
            }
        }
        .padding(.horizontal, 12)
        .background(Palette.background)
        .navigationTitle("Lap review")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            // Opened on a corner (FBBD-153): the cursor a little before it, as Android's review route.
            if let startFraction, st.cursor == 0 { st.scrub(to: startFraction, ended: true) }
            st.wake()
        }
        .onDisappear { st.stop() }
        // Never plays unseen.
        .onChange(of: phase) { _, p in if p != .active { st.stop() } }
        .accessibilityIdentifier("lapVideo")
    }
}

private struct LapPane: View {
    let lap: VideoLap
    let letter: String
    let color: Color
    @ObservedObject var player: LapPlayer

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Circle().fill(color).frame(width: 10, height: 10)
                Text("\(letter)  \(lapName(lap))").font(.subheadline.weight(.semibold)).foregroundStyle(color)
            }
            ZStack {
                Color.black
                PlayerLayer(player: player.player)
                if !player.showing {
                    Color.black
                    Text(lap.hasVideo ? "No video here" : "No video for this lap")
                        .font(.subheadline)
                        .foregroundStyle(Palette.onSurfaceVariant)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
    }
}

/// The picture, letterboxed in the space it gets.
private struct PlayerLayer: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerUIView {
        let v = PlayerUIView()
        v.playerLayer.player = player
        v.playerLayer.videoGravity = .resizeAspect
        return v
    }

    func updateUIView(_ uiView: PlayerUIView, context: Context) {}

    final class PlayerUIView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}

/// Play and speed on one row, the readout under them.
private struct TransportRow: View {
    @ObservedObject var st: ReviewTransport

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Button(st.playing ? "Pause" : "Play") { st.playPause() }
                    .buttonStyle(.bordered)
                    .tint(Palette.accent)
                    .frame(width: 104)
                    .accessibilityIdentifier("reviewPlay")
                Button(speedLabels[speeds.firstIndex(of: st.speed) ?? 0]) { st.nextSpeed() }
                    .foregroundStyle(Palette.accent)
            }
            Readout(st: st)
        }
        .padding(.vertical, 8)
    }
}

/// Both laps' clocks and speeds, and how far lap B is behind or ahead.
private struct Readout: View {
    @ObservedObject var st: ReviewTransport

    var body: some View {
        let t = st.cursor
        let ta = st.traces.aMicros(micros: t)
        let tb = st.traces.bMicros(micros: t)
        let gap = Int(st.traces.gapMetersAt(micros: t).rounded())
        HStack(alignment: .center, spacing: 16) {
            Clock(letter: "A", lapMicros: ta, speed: st.review.a.speedAt(lapMicros: ta)?.doubleValue, color: LapColors.a)
            Clock(letter: "B", lapMicros: tb, speed: st.review.b.speedAt(lapMicros: tb)?.doubleValue, color: LapColors.b)
            Spacer()
            Text(gap > 0 ? "B \(gap) m behind" : gap < 0 ? "B \(-gap) m ahead" : "Level")
                .font(.system(size: 24, weight: .semibold).monospacedDigit())
                .foregroundStyle(gap <= 0 ? Palette.gain : Palette.loss)
                .lineLimit(1)
                .padding(.horizontal, 10)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 8))
        }
    }
}

private struct Clock: View {
    let letter: String
    let lapMicros: Int64
    let speed: Double?
    let color: Color

    var body: some View {
        VStack(alignment: .leading) {
            Text("\(letter)  \(TimeFormatKt.formatRunningTime(millis: lapMicros / 1000))")
                .font(.system(size: 18, weight: .semibold).monospacedDigit())
                .foregroundStyle(color)
            Text(speed.map { "\(CoreText.fixed($0, 0)) km/h" } ?? "")
                .font(.caption)
                .foregroundStyle(Palette.onSurfaceVariant)
        }
    }
}

/// Delta (top) and both laps' speeds (bottom) over lap A's distance, with each car's cursor.
/// Delta below the zero line is time lap B gained.
private struct DeltaChart: View {
    @ObservedObject var st: ReviewTransport
    /// Share of the height for the delta; the rest is speed.
    private let deltaShare = 0.55
    /// The delta axis spans at least ±0.5 s, so a near-equal pair does not look dramatic.
    private let minDeltaMicros = 500_000.0

    var body: some View {
        let series = st.traces.series
        let length = max(series.last?.distanceMeters ?? 1, 1)
        let maxDelta = max(series.map { abs(Double($0.deltaMicros)) }.max() ?? 0, minDeltaMicros)
        let speedsAll = series.flatMap { [$0.speedA?.doubleValue, $0.speedB?.doubleValue].compactMap { $0 } }
        let lo = speedsAll.min() ?? 0
        let hiRaw = speedsAll.max() ?? 1
        let hi = hiRaw > lo ? hiRaw : lo + 1
        let fa = st.traces.aFraction(micros: st.cursor)
        let fb = st.traces.bFraction(micros: st.cursor)
        GeometryReader { geo in
            Canvas { ctx, size in
                let split = size.height * deltaShare
                let mid = (split - 4) / 2
                func x(_ d: Double) -> CGFloat { CGFloat(d / length) * size.width }
                ctx.stroke(line(0, mid, size.width, mid), with: .color(Palette.outline), lineWidth: 1)
                var prev: CGPoint?
                for p in series {
                    let o = CGPoint(x: x(p.distanceMeters), y: mid - CGFloat(Double(p.deltaMicros) / maxDelta) * mid)
                    if let prev { ctx.stroke(line(prev.x, prev.y, o.x, o.y), with: .color(p.deltaMicros < 0 ? Palette.gain : Palette.loss), lineWidth: 2) }
                    prev = o
                }
                let top = split + 4
                let h = size.height - top
                func y(_ v: Double) -> CGFloat { top + h - CGFloat((v - lo) / (hi - lo)) * h }
                var pa = Path(), pb = Path()
                var startedA = false, startedB = false
                for p in series {
                    if let v = p.speedA?.doubleValue {
                        let o = CGPoint(x: x(p.distanceMeters), y: y(v))
                        if startedA { pa.addLine(to: o) } else { pa.move(to: o) }
                        startedA = true
                    }
                    if let v = p.speedB?.doubleValue {
                        let o = CGPoint(x: x(p.distanceMeters), y: y(v))
                        if startedB { pb.addLine(to: o) } else { pb.move(to: o) }
                        startedB = true
                    }
                }
                ctx.stroke(pb, with: .color(LapColors.b), lineWidth: 2)
                ctx.stroke(pa, with: .color(LapColors.a), lineWidth: 2)
                let xb = CGFloat(min(max(fb, 0), 1)) * size.width
                let xa = CGFloat(min(max(fa, 0), 1)) * size.width
                ctx.stroke(line(xb, 0, xb, size.height), with: .color(LapColors.b), lineWidth: 2)
                ctx.stroke(line(xa, 0, xa, size.height), with: .color(LapColors.a), lineWidth: 2)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { st.scrub(to: $0.location.x / geo.size.width, ended: false) }
                    .onEnded { st.scrub(to: $0.location.x / geo.size.width, ended: true) }
            )
            .accessibilityIdentifier("reviewChart")
        }
    }

    private func line(_ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat) -> Path {
        Path { $0.move(to: CGPoint(x: x0, y: y0)); $0.addLine(to: CGPoint(x: x1, y: y1)) }
    }
}

/// Lap A's line with both cars on it, north up.
private struct LapMapView: View {
    @ObservedObject var st: ReviewTransport

    var body: some View {
        let points = st.review.a.trace.points.map { $0.xy }
        let t = st.cursor
        let pa = st.review.a.trace.at(lapMicros: st.traces.aMicros(micros: t)).xy
        let pb = st.review.b.trace.at(lapMicros: st.traces.bMicros(micros: t)).xy
        Canvas { ctx, size in
            guard let minX = points.map(\.x).min(), let maxX = points.map(\.x).max(),
                  let minY = points.map(\.y).min(), let maxY = points.map(\.y).max() else { return }
            let pad: CGFloat = 10
            let w = CGFloat(max(maxX - minX, 1)), h = CGFloat(max(maxY - minY, 1))
            let k = min((size.width - 2 * pad) / w, (size.height - 2 * pad) / h)
            let ox = (size.width - w * k) / 2, oy = (size.height - h * k) / 2
            func at(_ p: Xy) -> CGPoint {
                CGPoint(x: ox + CGFloat(p.x - minX) * k, y: size.height - oy - CGFloat(p.y - minY) * k)
            }
            var path = Path()
            for (i, p) in points.enumerated() {
                if i == 0 { path.move(to: at(p)) } else { path.addLine(to: at(p)) }
            }
            ctx.stroke(path, with: .color(Palette.onSurfaceVariant.opacity(0.5)), lineWidth: 4)
            for (p, c) in [(pb, LapColors.b), (pa, LapColors.a)] {
                let o = at(p)
                ctx.fill(Path(ellipseIn: CGRect(x: o.x - 6, y: o.y - 6, width: 12, height: 12)), with: .color(c))
            }
        }
    }
}
