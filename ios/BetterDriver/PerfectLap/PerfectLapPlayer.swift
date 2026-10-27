import BetterDriverCore
import QuartzCore
import SwiftUI

/// The perfect lap in 3D (FBBD-308, FBBD-316), as Android's player: the perfect lap followed from
/// behind and above the car (UI-200 A), its line a car-wide band coloured by the pedals with BRAKE
/// and GAS bars across the road (UI-202 C), the best lap as a see-through car at its own speed
/// (UI-183 C); play, pause, half speed and corner to corner in one bar on the picture (UI-204 A),
/// pinch to bring the camera nearer or farther, double tap to put it back, and Save video (UI-180 A).
struct PerfectLapPlayer: View {
    let scene: PerfectLapScene
    let onClose: () -> Void

    @StateObject private var clock: PlayClock
    @State private var distance = 1.0
    @State private var pinchFrom: Double?
    private let painter: PerfectLapPainter

    init(scene: PerfectLapScene, startCorner: Int = 0, onClose: @escaping () -> Void) {
        self.scene = scene
        self.onClose = onClose
        painter = PerfectLapPainter(scene: scene)
        let corners = scene.corners
        let start = corners.indices.contains(startCorner) ? corners[startCorner].startSeconds : 0
        _clock = StateObject(wrappedValue: PlayClock(seconds: start, duration: scene.durationSeconds))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Button("‹ Back", action: onClose).accessibilityIdentifier("perfectLapBack")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Perfect lap in 3D").font(.title3.weight(.semibold))
                    Text("\(LineUiKt.lineLapTime(seconds: scene.perfectSeconds)) · your best lap \(LineUiKt.lineLapTime(seconds: scene.bestSeconds))")
                        .font(.footnote)
                        .foregroundStyle(Palette.onSurfaceVariant)
                }
                Spacer()
                SaveVideoButton(scene: scene, distance: distance) { clock.playing = false }
            }
            picture
        }
        .padding(16)
        .background(Palette.background)
        .foregroundStyle(Palette.onBackground)
        .onAppear { clock.playing = true }
        .onDisappear { clock.playing = false }
        .accessibilityIdentifier("perfectLapPlayer")
    }

    /// The 16:9 picture, as large as fits, with the player's bar on it at the bottom.
    private var picture: some View {
        GeometryReader { geo in
            let fit = Self.fit(geo.size)
            ZStack(alignment: .bottom) {
                Canvas { ctx, size in
                    painter.distance = distance
                    let seconds = clock.seconds
                    ctx.withCGContext { cg in
                        painter.draw(cg, seconds: seconds, size: size, controlsHeight: Self.barHeight + 24)
                    }
                }
                .gesture(
                    MagnificationGesture()
                        .onChanged { scale in
                            let from = pinchFrom ?? distance
                            pinchFrom = from
                            distance = min(max(from / Double(scale), 0.5), 3.0)
                        }
                        .onEnded { _ in pinchFrom = nil }
                )
                .onTapGesture(count: 2) { distance = 1 }
                PlayerBar(scene: scene, clock: clock).padding(12)
            }
            .frame(width: fit.width, height: fit.height)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    static let barHeight: CGFloat = 64

    private static func fit(_ size: CGSize) -> CGSize {
        let aspect: CGFloat = 16 / 9
        return size.width / max(size.height, 1) > aspect
            ? CGSize(width: size.height * aspect, height: size.height)
            : CGSize(width: size.width, height: size.width / aspect)
    }
}

/// Where the player is in the lap, moved on every screen refresh while it plays.
@MainActor
final class PlayClock: ObservableObject {
    @Published var seconds: Double
    @Published var playing = false {
        didSet { playing ? begin() : end() }
    }
    /// As driven, or half (to see where the pedals change).
    @Published var speed = 1.0
    let duration: Double
    private var link: CADisplayLink?
    private var last: CFTimeInterval?

    init(seconds: Double, duration: Double) {
        self.seconds = seconds
        self.duration = duration
    }

    func play(_ on: Bool) {
        if on, seconds >= duration { seconds = 0 }
        playing = on
    }

    private func begin() {
        guard link == nil else { return }
        let l = CADisplayLink(target: self, selector: #selector(tick(_:)))
        l.add(to: .main, forMode: .common)
        link = l
        last = nil
    }

    private func end() {
        link?.invalidate()
        link = nil
    }

    @objc private func tick(_ l: CADisplayLink) {
        let now = l.timestamp
        if let last { seconds = min(seconds + (now - last) * speed, duration) }
        last = now
        if seconds >= duration { playing = false }
    }
}

/// The player's controls in one bar on the picture (UI-204 A): speed, previous corner, play, next
/// corner, the time, the lap to drag with a mark at each corner, and the lap time.
private struct PlayerBar: View {
    let scene: PerfectLapScene
    @ObservedObject var clock: PlayClock

    var body: some View {
        let at = Int(scene.cornerAt(seconds: clock.seconds))
        let corners = scene.corners
        HStack(spacing: 6) {
            Button(clock.speed == 1 ? "1×" : "½×") { clock.speed = clock.speed == 1 ? 0.5 : 1 }
                .font(.body.weight(.bold))
            Button("⏮") { clock.seconds = corners[max(at - 1, 0)].startSeconds }
            Button { clock.play(!clock.playing) } label: {
                Text(clock.playing ? "❚❚" : "▶").font(.body.weight(.bold)).frame(width: 40, height: 40)
                    .background(Circle().fill(Palette.accent)).foregroundStyle(Palette.onAccent)
            }
            .accessibilityLabel(clock.playing ? "Pause" : "Play")
            Button("⏭") { clock.seconds = corners[min(at + 1, corners.count - 1)].startSeconds }
            Text(LineUiKt.lineLapTime(seconds: clock.seconds)).font(.footnote.monospacedDigit())
            VStack(spacing: 0) {
                Slider(value: $clock.seconds, in: 0 ... max(scene.durationSeconds, 0.1)).tint(Palette.accent)
                GeometryReader { geo in
                    ForEach(Array(corners.enumerated()), id: \.offset) { i, c in
                        Text(LineUiKt.cornerTag(name: c.name))
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(i == at ? Palette.accent : Color.white.opacity(0.7))
                            .offset(x: geo.size.width * CGFloat(c.startSeconds / scene.durationSeconds))
                    }
                }
                .frame(height: 14)
            }
            Text(LineUiKt.lineLapTime(seconds: scene.durationSeconds))
                .font(.footnote.monospacedDigit())
                .foregroundStyle(Color.white.opacity(0.7))
        }
        .buttonStyle(.plain)
        .foregroundStyle(Color.white)
        .padding(.horizontal, 12)
        .frame(maxWidth: 900)
        .frame(height: PerfectLapPlayer.barHeight)
        .background(Capsule().fill(Color(.sRGB, red: 5 / 255, green: 7 / 255, blue: 9 / 255, opacity: 0xC8 / 255)))
    }
}

/// Save video in the header (UI-180 A), with its progress, and Send once saved.
private struct SaveVideoButton: View {
    let scene: PerfectLapScene
    let distance: Double
    let pause: () -> Void

    @State private var progress: Double?
    @State private var saved: URL?
    @State private var failure: String?
    @State private var job: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            HStack(spacing: 8) {
                if let progress {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Making the video…").font(.footnote).foregroundStyle(Palette.onSurfaceVariant)
                        ProgressView(value: progress).tint(Palette.accent)
                    }
                    .frame(width: 160)
                    Button("Stop") { job?.cancel() }
                } else {
                    if let saved {
                        ShareLink(item: saved) { Text("Send") }
                    }
                    Button("Save video") { save() }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("saveVideo")
                }
            }
            if let failure {
                Text("The video was not saved: \(failure)").font(.footnote).foregroundStyle(TrackColors.loss)
            } else if saved != nil {
                Text("Saved in the app. Send it to keep it.").font(.footnote).foregroundStyle(Palette.onSurfaceVariant)
            }
        }
    }

    private func save() {
        pause()
        failure = nil
        saved = nil
        progress = 0
        let scene = self.scene, distance = self.distance
        job = Task {
            do {
                let url = try await PerfectLapVideo.save(scene: scene, distance: distance) { p in
                    Task { @MainActor in if progress != nil { progress = p } }
                }
                saved = url
            } catch is CancellationError {
            } catch {
                failure = error.localizedDescription
            }
            progress = nil
        }
    }
}
