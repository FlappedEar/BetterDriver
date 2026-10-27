import SwiftUI
import UIKit

/// The in-car track screen on the iPhone (FBBD-332): the Android layout (UI-10 A with the tile row of
/// UI-120 A) over the full landscape width, the notch over the left edge (UI-283 B). Status strip,
/// delta left and lap times right, the coach line, then one row of six tiles, which take the coach
/// line's place when the coach is off (UI-142 B). A 3 s hold anywhere stops the session (UI-16).
struct TrackScreenView: View {
    let model: TrackScreenModel?
    let onStop: () -> Void

    @State private var holdStart: Date?

    private static let gap: CGFloat = 6
    private static let topHeight: CGFloat = 30
    /// UI-120 A: one row of tiles; the coach line above it (UI-09 A).
    private static let panelHeight: CGFloat = 80
    private static let coachHeight: CGFloat = 44
    static let holdSeconds = 3.0

    var body: some View {
        ZStack {
            TrackColors.background
            if let model {
                VStack(spacing: Self.gap) {
                    TopStrip(model: model).frame(height: Self.topHeight)
                    GeometryReader { geo in
                        HStack(spacing: Self.gap) {
                            DeltaView(seconds: model.deltaSeconds)
                                .frame(width: (geo.size.width - Self.gap) * 0.62)
                            LapTimesView(model: model)
                        }
                    }
                    if let coach = model.coach {
                        CoachLineView(coach: coach).frame(height: Self.coachHeight)
                        TileRow(tiles: model.tiles, valueSize: 34).frame(height: Self.panelHeight)
                    } else {
                        // Without the coach line the tiles grow into its place (UI-142 B).
                        TileRow(tiles: model.tiles, valueSize: 50)
                            .frame(height: Self.panelHeight + Self.coachHeight + Self.gap)
                    }
                }
                .padding(Self.gap)
            } else {
                Text("Starting recording")
                    .font(TrackFonts.text(18))
                    .foregroundStyle(TrackColors.textDim)
            }
            if let holdStart {
                TimelineView(.animation) { context in
                    HoldRing(elapsed: context.date.timeIntervalSince(holdStart), total: Self.holdSeconds)
                }
            }
        }
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .onLongPressGesture(minimumDuration: Self.holdSeconds, maximumDistance: 24) {
            holdStart = nil
            onStop()
        } onPressingChanged: { pressing in
            holdStart = pressing ? Date() : nil
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .accessibilityIdentifier("trackScreen")
        .onAppear {
            OrientationLock.set(.landscape)
            ScreenLight.full(SettingsModel.shared.fullBrightness)
        }
        .onDisappear {
            OrientationLock.set(.portrait)
            ScreenLight.restore()
        }
    }
}

/// Keeps the track screen in landscape while it shows, the phone's mount position, and returns to
/// portrait after; the app delegate hands [mask] to iOS.
enum OrientationLock {
    /// What the app allows outside the track screen (project.yml's orientations).
    static let normal: UIInterfaceOrientationMask =
        UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
    static var mask = normal

    static func set(_ wanted: UIInterfaceOrientationMask) {
        mask = wanted == .portrait ? normal : wanted
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        scene?.requestGeometryUpdate(.iOS(interfaceOrientations: wanted)) { _ in }
        scene?.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }
}

/// The status strip (UI-141 B), or in its place the one alert (UI-08), with the recording clock.
private struct TopStrip: View {
    let model: TrackScreenModel

    var body: some View {
        if let alert = model.alert {
            let fg = alert.critical ? TrackColors.onLoss : TrackColors.onWarning
            HStack(spacing: 12) {
                Text(alert.title).font(TrackFonts.text(15)).foregroundStyle(fg).lineLimit(1)
                Text(alert.detail).font(TrackFonts.text(14, .medium)).foregroundStyle(fg).lineLimit(1)
                Spacer(minLength: 0)
                Text("● \(model.recordingTime)").font(TrackFonts.number(15, .semibold)).foregroundStyle(fg)
            }
            .padding(.horizontal, 14)
            .frame(maxHeight: .infinity)
            .background(alert.critical ? TrackColors.loss : TrackColors.warning, in: RoundedRectangle(cornerRadius: 6))
        } else {
            HStack(spacing: 18) {
                ForEach(model.status) { item in
                    HStack(spacing: 6) {
                        let tint = item.ok ? TrackColors.gain : TrackColors.warning
                        if let icon = item.icon {
                            Image(systemName: icon)
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(tint)
                                .accessibilityLabel(item.label)
                        } else {
                            Dot(color: tint)
                            Text(item.label).font(TrackFonts.text(13, .medium)).foregroundStyle(TrackColors.textDim)
                        }
                        if !item.ok || item.keepValue {
                            Text(item.value)
                                .font(TrackFonts.number(15, .semibold))
                                .foregroundStyle(item.ok ? TrackColors.text : TrackColors.warning)
                        }
                    }
                    .lineLimit(1)
                }
                Spacer(minLength: 0)
                HStack(spacing: 6) {
                    Dot(color: TrackColors.loss)
                    TrackLabel(text: "REC", size: 13)
                    Text(model.recordingTime).font(TrackFonts.number(15, .semibold)).foregroundStyle(TrackColors.text)
                }
            }
            .padding(.horizontal, 8)
        }
    }
}

private struct Dot: View {
    let color: Color

    var body: some View { Circle().fill(color).frame(width: 9, height: 9) }
}

/// The predictive delta to the best lap, as large as its slot allows up to 150 (DeltaDisplay).
private struct DeltaView: View {
    let seconds: Double?

    var body: some View {
        let color: Color = switch seconds {
        case nil: TrackColors.textDim
        case let s? where CoreBridge.isGain(seconds: s): TrackColors.gain
        default: TrackColors.loss
        }
        GeometryReader { geo in
            VStack(alignment: .leading, spacing: 8) {
                TrackLabel(text: "Delta vs best")
                Text(seconds.map { CoreBridge.delta(seconds: $0) } ?? "\u{2014}")
                    .font(TrackFonts.number(min(150, max(24, (geo.size.height - 20 - 25) / 1.2))))
                    .foregroundStyle(color)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .monospacedDigit()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(TrackColors.background, in: RoundedRectangle(cornerRadius: 8))
    }
}

/// Lap number and running lap time, then last and best (LapTimes).
private struct LapTimesView: View {
    let model: TrackScreenModel

    var body: some View {
        GeometryReader { geo in
            let hero = min(56, max(56 * 0.55, (geo.size.height - 2) / 1.2 - 56 * 0.55 * 2))
            let small = hero * 0.55
            VStack(spacing: 0) {
                HStack {
                    Text(model.lapNumber.map(String.init) ?? "")
                        .font(TrackFonts.number(max(hero * 0.8, small)))
                        .foregroundStyle(TrackColors.text)
                    Spacer(minLength: 8)
                    time(model.lapMillis, hero, .bold, TrackColors.text)
                }
                row("Last", model.lastMillis, small, TrackColors.text)
                row("Best", model.bestMillis, small, TrackColors.best)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(.horizontal, 16)
        .background(TrackColors.surface, in: RoundedRectangle(cornerRadius: 8))
    }

    private func row(_ label: String, _ millis: Int64?, _ size: CGFloat, _ color: Color) -> some View {
        HStack {
            TrackLabel(text: label)
            Spacer(minLength: 8)
            time(millis, size, .semibold, color)
        }
    }

    private func time(_ millis: Int64?, _ size: CGFloat, _ weight: Font.Weight, _ color: Color) -> some View {
        Text(millis.map { CoreBridge.lapTime(millis: $0) } ?? "-:--.--")
            .font(TrackFonts.number(size, weight))
            .foregroundStyle(millis == nil ? TrackColors.textDim : color)
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .monospacedDigit()
    }
}

/// The coach's last cue (CoachLine, UI-09 A): a speaker, the corner and the text; an empty line keeps
/// its slot, so the layout never moves. A praise or a cue that worked is green, one not done yet grey.
private struct CoachLineView: View {
    let coach: TrackScreenModel.Coach

    var body: some View {
        HStack(spacing: 10) {
            if let text = coach.text {
                Image(systemName: "speaker.wave.2.fill")
                    .font(.system(size: 16))
                    .foregroundStyle(TrackColors.textDim)
                    .frame(width: 20, height: 20)
                if let corner = coach.corner {
                    Text(corner).font(TrackFonts.number(20)).foregroundStyle(TrackColors.textDim)
                }
                Text(text)
                    .font(TrackFonts.text(20, .medium))
                    .foregroundStyle(coach.praise ? TrackColors.gain : coach.dim ? TrackColors.textDim : TrackColors.text)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(TrackColors.surface, in: RoundedRectangle(cornerRadius: 6))
        .accessibilityIdentifier("coachLine")
    }
}

/// One row of tiles (UI-120 A); an empty slot is a dashed outline (UI-125 B). Values are fitted to
/// the row, at most [valueSize]: 34 under the coach line, 50 without it (UI-142 B).
private struct TileRow: View {
    let tiles: [TrackScreenModel.Tile?]
    let valueSize: CGFloat

    var body: some View {
        HStack(spacing: 6) {
            ForEach(Array(tiles.enumerated()), id: \.offset) { _, tile in
                if let tile {
                    TileView(tile: tile, valueSize: valueSize)
                } else {
                    RoundedRectangle(cornerRadius: 8)
                        .strokeBorder(TrackColors.line, style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
                }
            }
        }
    }
}

/// One value with its label and unit (ValueTile), the value as large as the row allows up to [valueSize].
private struct TileView: View {
    let tile: TrackScreenModel.Tile
    let valueSize: CGFloat

    var body: some View {
        GeometryReader { geo in
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .lastTextBaseline, spacing: 6) {
                    TrackLabel(text: tile.label)
                    // "G" needs no "g" beside it.
                    if !tile.unit.isEmpty && tile.unit.lowercased() != tile.label.lowercased() {
                        Text(tile.unit).font(TrackFonts.label(11)).foregroundStyle(TrackColors.textDim).lineLimit(1)
                    }
                }
                Text(tile.value)
                    .font(TrackFonts.number(min(valueSize, max(14, (geo.size.height - 12 - 14 - 1) / 1.2)), .semibold))
                    .foregroundStyle(tile.dim ? TrackColors.textDim : TrackColors.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .monospacedDigit()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .padding(.vertical, 6)
        .background(TrackColors.surface, in: RoundedRectangle(cornerRadius: 8))
    }
}

/// The ring that fills while the finger holds (UI-16), with the seconds left.
private struct HoldRing: View {
    let elapsed: TimeInterval
    let total: TimeInterval

    var body: some View {
        let progress = min(max(elapsed / total, 0), 1)
        ZStack {
            Circle().fill(Color.black.opacity(0.92))
            Circle().stroke(Color.white.opacity(0.18), lineWidth: 14).padding(7)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(TrackColors.loss, lineWidth: 14)
                .rotationEffect(.degrees(-90))
                .padding(7)
            Text("Hold to stop\n" + String(format: "%.1f s", max(total - elapsed, 0)))
                .font(TrackFonts.text(18))
                .foregroundStyle(TrackColors.text)
                .multilineTextAlignment(.center)
        }
        .frame(width: 150, height: 150)
        .allowsHitTesting(false)
    }
}

/// Hands iOS the orientations the app allows now (OrientationLock).
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        OrientationLock.mask
    }
}

/// The track screen at full brightness while it shows (FBBD-63), when Settings › Track screen has it
/// on (the default), as on Android; the brightness before comes back when it closes.
@MainActor
enum ScreenLight {
    private static var before: CGFloat?

    static func full(_ on: Bool) {
        guard on, before == nil else { return }
        before = UIScreen.main.brightness
        UIScreen.main.brightness = 1
    }

    static func restore() {
        guard let level = before else { return }
        UIScreen.main.brightness = level
        before = nil
    }
}
