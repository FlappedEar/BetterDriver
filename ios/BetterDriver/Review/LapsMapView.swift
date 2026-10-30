import SwiftUI
import BetterDriverCore

// The Map tab's Laps view (FBBD-149, FBBD-378), as Android's (MapTab.kt LapsMap, LapMapView.kt): the
// compared lap's GPS line coloured by speed or by time gained and lost against the best lap, corners
// tagged as on the Corners tab; touching the line reads speed and delta there. Beside it (below on a
// phone) the G-G diagram of the lap over the best lap's. The scale, strokes and words are the shared core's.

/// Speeds on the map are in this unit, as on the Compare tab.
private let mapSpeedUnit = "km/h"

/// The theme's gain, neutral and loss colours as the core takes them (ARGB).
private enum MapArgb {
    static let gain = Int32(bitPattern: 0xFF22D37A)
    static let neutral = Int32(bitPattern: 0xFF8C9AA5)
    static let loss = Int32(bitPattern: 0xFFFF6863)
}

struct LapsMapView: View {
    let ui: MapUi?
    @Environment(\.horizontalSizeClass) private var size

    var body: some View {
        if let ui {
            LapsMapContent(ui: ui, wide: size == .regular).id(ui.lapNumber)
        } else {
            Text("No map for this lap: it has too little GPS.")
                .foregroundStyle(Palette.onSurfaceVariant)
                .padding(40)
            Spacer()
        }
    }
}

private struct LapsMapContent: View {
    let ui: MapUi
    let wide: Bool
    @State private var coloring: MapColoring
    @State private var cursor: MapPoint?

    init(ui: MapUi, wide: Bool) {
        self.ui = ui
        self.wide = wide
        _coloring = State(initialValue: ui.referenceNumber != nil ? .delta : .speed)
    }

    var body: some View {
        let scale = MapScaleKt.mapScale(map: ui.map, coloring: coloring)
        ScrollView {
            Group {
                if wide {
                    HStack(alignment: .top, spacing: 12) {
                        mapCard(scale).frame(maxWidth: .infinity)
                        // A fixed column: with layout priorities the map took all but ~80 pt in
                        // portrait and the words broke per syllable (Arek, TestFlight 1441).
                        ggCard.frame(width: 320)
                    }
                } else {
                    VStack(spacing: 12) {
                        mapCard(scale)
                        ggCard
                    }
                }
            }
            .padding(16)
            .frame(maxWidth: 1200)
            .frame(maxWidth: .infinity)
        }
        .accessibilityIdentifier("lapsMap")
    }

    private func mapCard(_ scale: MapScale) -> some View {
        Card {
            title
            HStack(spacing: 8) {
                ForEach([MapColoring.speed, MapColoring.delta], id: \.self) { option in
                    let enabled = option == .speed || ui.referenceNumber != nil
                    Button { coloring = option } label: {
                        Text(option.title)
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .foregroundStyle(coloring == option ? Palette.onAccent : Palette.onBackground)
                            .background(coloring == option ? Palette.accent : Palette.surfaceVariant, in: Capsule())
                            .opacity(enabled ? 1 : 0.4)
                    }
                    .buttonStyle(.plain)
                    .disabled(!enabled)
                }
            }
            readout
            LapLineMap(map: ui.map, scale: scale, cursor: cursor) { cursor = $0 }
                .aspectRatio(CGFloat(MapUiKt.mapAspect(map: ui.map)), contentMode: .fit)
                .accessibilityIdentifier("lapLineMap")
            legend(scale)
            if ui.referenceNumber == nil {
                Dim(MapScaleKt.MapBestLapNote)
            } else if scale.coloring == .delta {
                Dim(MapScaleKt.MapDeltaNote)
            }
        }
    }

    private var title: some View {
        HStack(spacing: 0) {
            Text("Lap \(ui.lapNumber)")
                .fontWeight(.semibold)
                .foregroundStyle(ui.referenceNumber == nil ? Palette.best : Palette.cold)
            if let reference = ui.referenceNumber {
                Dim(" vs best lap ")
                Text("\(reference.intValue)").fontWeight(.semibold).foregroundStyle(Palette.best)
            } else {
                Dim(" (best lap)")
            }
        }
    }

    /// What the map cursor reads: distance, corner, speed, delta and the rate of gain or loss there.
    @ViewBuilder private var readout: some View {
        if let cursor {
            let corner = MapScaleKt.cursorCorner(map: ui.map, cursor: cursor)
            HStack(spacing: 12) {
                Dim("\(Int(cursor.distanceMeters.rounded())) m" + (corner.map { " · \(LineUiKt.cornerTag(name: $0.name))" } ?? ""))
                Text(cursor.speed.map { "\(Int($0.doubleValue.rounded())) \(mapSpeedUnit)" } ?? CoreText.missing)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.onBackground)
                if let delta = cursor.deltaSeconds?.doubleValue {
                    Text(CoreBridge.delta(seconds: delta))
                        .fontWeight(.semibold)
                        .foregroundStyle(CoreBridge.isGain(seconds: delta) ? Palette.gain : Palette.loss)
                }
            }
            .font(.body.monospacedDigit())
            if let rate = cursor.deltaRate?.doubleValue {
                Dim(MapScaleKt.deltaRateText(rate: rate), color: rate < 0 ? Palette.gain : Palette.loss)
            }
        } else {
            Dim(MapScaleKt.MapCursorHint)
        }
    }

    /// The colour bar under the map and what its ends mean.
    private func legend(_ scale: MapScale) -> some View {
        let delta = scale.coloring == .delta
        let colors = delta
            ? [Palette.gain, Palette.onSurfaceVariant, Palette.loss]
            : MapUiKt.SpeedScaleColors.map { Color(argb: $0.int32Value) }
        return VStack(spacing: 2) {
            RoundedRectangle(cornerRadius: 4)
                .fill(LinearGradient(colors: colors, startPoint: .leading, endPoint: .trailing))
                .frame(height: 8)
            HStack {
                if delta {
                    let limit = MapScaleKt.gainLossLimitText(scale: scale)
                    Dim("Gaining \(limit) s", color: Palette.gain)
                    Spacer()
                    Dim("per 100 m")
                    Spacer()
                    Dim("Losing \(limit) s", color: Palette.loss)
                } else {
                    Dim(scale.speed.map { "\(Int($0.low)) \(mapSpeedUnit)" } ?? CoreText.missing)
                    Spacer()
                    Dim("slow to fast")
                    Spacer()
                    Dim(scale.speed.map { "\(Int($0.high)) \(mapSpeedUnit)" } ?? "")
                }
            }
        }
    }

    private var ggCard: some View {
        Card {
            SectionTitle("G-G · traction circle")
            HStack(spacing: 12) {
                key(ui.referenceNumber != nil ? Palette.cold : Palette.best, "Lap \(ui.lapNumber)")
                if let reference = ui.referenceNumber { key(Palette.best, "Best lap \(reference.intValue)") }
            }
            if let gg = ui.gg, !gg.points.isEmpty {
                let at = cursor.flatMap {
                    MapUiKt.ggPointAt(trace: gg, distanceMeters: $0.distanceMeters, withinMeters: MapUiKt.GgCursorMeters)
                }
                SummaryGgChart(
                    trace: gg, reference: ui.referenceGg,
                    color: ui.referenceNumber != nil ? Palette.cold : Palette.best,
                    referenceColor: Palette.best, cursor: at
                )
                .aspectRatio(1, contentMode: .fit)
                .accessibilityIdentifier("lapGgChart")
                Dim(TimeGoesTextKt.ggPeaksText(trace: gg))
                Dim(TimeGoesTextKt.ggSourceNote(source: gg.source))
            } else {
                Dim("No acceleration for this lap: it has too little GPS.")
            }
        }
    }

    private func key(_ color: Color, _ label: String) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Dim(label)
        }
    }
}

/// The lap on the map: the track outline wide and dim, the lap's line in coloured stretches, its
/// corners tagged T1, T2 … outside each turn, the start/finish ringed. Touching or dragging along the
/// line moves the cursor.
private struct LapLineMap: View {
    let map: LapMap
    let scale: MapScale
    let cursor: MapPoint?
    let onCursor: (MapPoint) -> Void
    /// Space kept round the track, so corner labels fit; a touch this close to the line reads it.
    private let padding: CGFloat = 32
    private let touchRadius: CGFloat = 32

    var body: some View {
        let strokes = MapScaleKt.mapStrokes(map: map, scale: scale, gain: MapArgb.gain, neutral: MapArgb.neutral, loss: MapArgb.loss)
        GeometryReader { geo in
            let frame = LapMapFrame(map: map, size: geo.size, padding: padding)
            Canvas { ctx, _ in
                if map.outline.count > 1 {
                    var path = Path()
                    for (i, p) in map.outline.enumerated() {
                        let o = frame.point(p.eastMeters, p.northMeters)
                        if i == 0 { path.move(to: o) } else { path.addLine(to: o) }
                    }
                    // A point-to-point track's outline ends at the finish (FBBD-155).
                    if map.closed { path.closeSubpath() }
                    ctx.stroke(path, with: .color(Palette.outline), style: StrokeStyle(lineWidth: 10, lineCap: .round, lineJoin: .round))
                    // Start/finish: where the axis starts.
                    let s = frame.point(map.outline[0].eastMeters, map.outline[0].northMeters)
                    ctx.fill(Path(ellipseIn: CGRect(x: s.x - 4, y: s.y - 4, width: 8, height: 8)), with: .color(Palette.onBackground))
                    ctx.fill(Path(ellipseIn: CGRect(x: s.x - 2, y: s.y - 2, width: 4, height: 4)), with: .color(Palette.background))
                }
                for stroke in strokes {
                    var line = Path()
                    line.move(to: frame.point(stroke.from.eastMeters, stroke.from.northMeters))
                    line.addLine(to: frame.point(stroke.to.eastMeters, stroke.to.northMeters))
                    ctx.stroke(line, with: .color(Color(argb: stroke.argb)), style: StrokeStyle(lineWidth: 4, lineCap: .round))
                }
                for corner in map.corners {
                    let anchor = frame.point(corner.anchor.eastMeters, corner.anchor.northMeters)
                    // North up on screen: a northward vector points up.
                    let at = CGPoint(x: anchor.x + CGFloat(corner.outward.eastMeters) * 16,
                                     y: anchor.y - CGFloat(corner.outward.northMeters) * 16)
                    ctx.draw(
                        Text(LineUiKt.cornerTag(name: corner.name))
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(Palette.onBackground),
                        at: at
                    )
                }
                if let cursor {
                    let o = frame.point(cursor.eastMeters, cursor.northMeters)
                    let ring = Path(ellipseIn: CGRect(x: o.x - 8, y: o.y - 8, width: 16, height: 16))
                    ctx.fill(ring, with: .color(Palette.background))
                    ctx.stroke(ring, with: .color(Palette.onBackground), lineWidth: 2.5)
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                let east = frame.east(g.location.x), north = frame.north(g.location.y)
                if let p = LapMapKt.nearestMapPoint(runs: map.runs, east: east, north: north, withinMeters: Double(touchRadius) / frame.scale) {
                    onCursor(p)
                }
            })
        }
    }
}

/// [map]'s outline and line fitted into [size] less [padding] on every side, north up, one scale on
/// both axes and centred, as the core's MapFrame.
private struct LapMapFrame {
    let scale: Double
    private let offsetX: Double
    private let offsetY: Double

    init(map: LapMap, size: CGSize, padding: CGFloat) {
        let east = map.outline.map(\.eastMeters) + map.runs.flatMap { $0.map(\.eastMeters) }
        let north = map.outline.map(\.northMeters) + map.runs.flatMap { $0.map(\.northMeters) }
        let e0 = east.min() ?? 0, e1 = east.max() ?? 1
        let n0 = north.min() ?? 0, n1 = north.max() ?? 1
        let spanEast = max(e1 - e0, 1), spanNorth = max(n1 - n0, 1)
        let w = Double(size.width), h = Double(size.height), p = Double(padding)
        scale = max(min((w - 2 * p) / spanEast, (h - 2 * p) / spanNorth), 1e-6)
        offsetX = (w - spanEast * scale) / 2 - e0 * scale
        offsetY = (h - spanNorth * scale) / 2 + n1 * scale
    }

    func point(_ east: Double, _ north: Double) -> CGPoint {
        CGPoint(x: east * scale + offsetX, y: offsetY - north * scale)
    }

    func east(_ x: CGFloat) -> Double { (Double(x) - offsetX) / scale }
    func north(_ y: CGFloat) -> Double { (offsetY - Double(y)) / scale }
}
