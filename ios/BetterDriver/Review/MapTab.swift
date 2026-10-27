import SwiftUI

/// The Map tab's "Where to find time" (FBBD-306) on the iPad, as on Android: the whole lap with a
/// badge per corner with time to find, and the corners ranked by it (UI-164 A). Tapping a corner
/// zooms the map to it and the list becomes what to do there (UI-166 A). Your best lap is coloured
/// by the pedals and the target is a thin green line (UI-165 C).
struct MapTab: View {
    let line: WhereToFindTime?
    @State private var picked: String?

    var body: some View {
        if let line {
            let corner = line.corners.first { $0.tag == picked }
            GeometryReader { geo in
                let wide = geo.size.width > geo.size.height
                let layout = wide ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
                layout {
                    VStack(alignment: .leading, spacing: 6) {
                        LineMap(line: line, corner: corner) { picked = $0 }
                        Legend(corner: corner, line: line)
                    }
                    .padding(16)
                    Group {
                        if let corner {
                            CornerCard(corner: corner) { picked = nil }
                        } else {
                            RankedList(line: line) { picked = $0 }
                        }
                    }
                    .frame(width: wide ? min(400, geo.size.width * 0.4) : nil)
                    .frame(maxHeight: wide ? .infinity : geo.size.height * 0.45)
                }
            }
        } else {
            Text("No line analysis for this session: it needs a lap through the track's corners with GPS all the way round.")
                .foregroundStyle(Palette.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(40)
        }
    }
}

enum LineColors {
    static let gain = Color(hex: 0x22D37A)
    static let loss = Color(hex: 0xFF6863)
    static let dim = Color(hex: 0x96A5B0)
    static let road = Color(hex: 0x232B31)

    static func pedal(_ p: Pedal?) -> Color {
        switch p {
        case .braking: loss
        case .accelerating: gain
        case .coasting, nil: dim
        }
    }
}

private struct RankedList: View {
    let line: WhereToFindTime
    let pick: (String) -> Void

    var body: some View {
        List {
            Section {
                ForEach(line.ranked) { c in
                    Button { pick(c.tag) } label: {
                        HStack(alignment: .top, spacing: 12) {
                            Text(c.tag).font(.headline).frame(width: 52, alignment: .leading)
                            VStack(alignment: .leading, spacing: 2) {
                                ForEach(c.todo, id: \.self) { Text($0).font(.subheadline) }
                            }
                            Spacer(minLength: 8)
                            Text(c.gain)
                                .font(.headline.monospacedDigit())
                                .foregroundStyle(c.gainSeconds > 0 ? Palette.accent : Palette.onSurfaceVariant)
                        }
                        .foregroundStyle(Palette.onBackground)
                    }
                    .listRowBackground(Palette.background)
                    .accessibilityIdentifier("corner\(c.tag)")
                }
            } header: {
                HStack {
                    Text("Time to find").font(.caption.weight(.semibold))
                    Spacer()
                    Text("≈ \(line.total) s").font(.title3.weight(.bold).monospacedDigit()).foregroundStyle(Palette.accent)
                }
                .foregroundStyle(Palette.onSurfaceVariant)
            } footer: {
                Text("Your best lap \(line.bestLap) (lap \(line.bestLapNumber)) against your fastest passes through each corner: \(line.laps) laps in \(line.sessions) sessions here. Tap a corner.")
                    .foregroundStyle(Palette.onSurfaceVariant)
            }
        }
        .scrollContentBackground(.hidden)
        .accessibilityIdentifier("whereToFindTime")
    }
}

private struct CornerCard: View {
    let corner: LineCorner
    let back: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Button("‹ All corners", action: back)
                    .foregroundStyle(Palette.accent)
                    .accessibilityIdentifier("allCorners")
                HStack(alignment: .firstTextBaseline) {
                    Text(corner.tag).font(.title.weight(.bold))
                    Spacer()
                    Text("\(corner.gain) s").font(.title.weight(.bold).monospacedDigit()).foregroundStyle(Palette.accent)
                }
                Text("to find, against your best lap. Target: \(corner.targetFrom).")
                    .font(.footnote)
                    .foregroundStyle(Palette.onSurfaceVariant)
                ForEach(corner.todo, id: \.self) { t in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("›").foregroundStyle(Palette.accent)
                        Text(t)
                    }
                    .font(.body)
                }
                Text("Lines closer than 1.5 m are treated as the same line: the RaceBox repeats a line to about that.")
                    .font(.caption)
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .padding(.top, 8)
            }
            .foregroundStyle(Palette.onBackground)
            .padding(20)
        }
    }
}

private struct Legend: View {
    let corner: LineCorner?
    let line: WhereToFindTime

    var body: some View {
        HStack(spacing: 14) {
            key(LineColors.loss, "Braking")
            key(LineColors.dim, "Coasting")
            key(LineColors.gain, "Throttle")
            key(LineColors.gain, "Target", thin: true)
        }
        .font(.caption)
        .foregroundStyle(Palette.onSurfaceVariant)
    }

    private func key(_ color: Color, _ name: String, thin: Bool = false) -> some View {
        HStack(spacing: 4) {
            RoundedRectangle(cornerRadius: 1).fill(color).frame(width: 12, height: thin ? 1.5 : 3)
            Text(name)
        }
    }
}

/// The map: the whole lap with corner badges, or one corner zoomed with the road the laps used.
private struct LineMap: View {
    let line: WhereToFindTime
    let corner: LineCorner?
    let pick: (String?) -> Void

    private let padding: CGFloat = 36
    private let badgeOffset: CGFloat = 26

    var body: some View {
        GeometryReader { geo in
            let frame = MapFrame(dots: extentDots, size: geo.size, padding: padding, margin: corner == nil ? 0 : 10)
            ZStack {
                Canvas { ctx, _ in
                    if let corner {
                        drawCorner(corner, frame, &ctx)
                    } else {
                        drawOverview(frame, &ctx)
                    }
                }
                if corner == nil {
                    ForEach(line.corners.filter { $0.gainSeconds > 0 && $0.anchor != nil }) { c in
                        Button { pick(c.tag) } label: {
                            Text("\(c.tag) \(c.gain)")
                                .font(.caption.weight(.bold).monospacedDigit())
                                .padding(.horizontal, 6)
                                .padding(.vertical, 3)
                                .background(Palette.accent, in: Capsule())
                                .foregroundStyle(Palette.onAccent)
                        }
                        .buttonStyle(.plain)
                        .position(badge(frame, c.anchor!))
                    }
                }
            }
        }
        .accessibilityIdentifier("lineMap")
    }

    private var all: [MapDot] { line.corners.flatMap(\.reference) }

    private var extentDots: [MapDot] {
        guard let corner else { return all }
        return corner.roadLeft + corner.roadRight + corner.reference + corner.target
    }

    private var middle: CGPoint {
        CGPoint(x: all.map(\.east).reduce(0, +) / Double(max(1, all.count)),
                y: all.map(\.north).reduce(0, +) / Double(max(1, all.count)))
    }

    private func badge(_ frame: MapFrame, _ anchor: MapDot) -> CGPoint {
        let p = frame.point(anchor)
        let m = frame.point(MapDot(east: middle.x, north: middle.y, pedal: nil))
        let dx = p.x - m.x, dy = p.y - m.y
        let n = max(1, hypot(dx, dy))
        return CGPoint(x: p.x + dx / n * badgeOffset, y: p.y + dy / n * badgeOffset)
    }

    private func path(_ frame: MapFrame, _ dots: [MapDot]) -> Path {
        var p = Path()
        for (i, d) in dots.enumerated() {
            if i == 0 { p.move(to: frame.point(d)) } else { p.addLine(to: frame.point(d)) }
        }
        return p
    }

    private func drawByPedal(_ dots: [MapDot], _ frame: MapFrame, _ width: CGFloat, _ ctx: inout GraphicsContext) {
        for i in dots.indices.dropFirst() {
            var p = Path()
            p.move(to: frame.point(dots[i - 1]))
            p.addLine(to: frame.point(dots[i]))
            ctx.stroke(p, with: .color(LineColors.pedal(dots[i - 1].pedal)), style: StrokeStyle(lineWidth: width, lineCap: .round))
        }
    }

    private func drawOverview(_ frame: MapFrame, _ ctx: inout GraphicsContext) {
        ctx.stroke(path(frame, all), with: .color(LineColors.road), style: StrokeStyle(lineWidth: 12, lineCap: .round, lineJoin: .round))
        for c in line.corners { drawByPedal(c.reference, frame, 4, &ctx) }
        for c in line.corners {
            ctx.stroke(path(frame, c.target), with: .color(LineColors.gain), style: StrokeStyle(lineWidth: 1.5, lineJoin: .round))
        }
    }

    private func drawCorner(_ c: LineCorner, _ frame: MapFrame, _ ctx: inout GraphicsContext) {
        if c.roadLeft.count > 1 {
            var road = path(frame, c.roadLeft)
            for d in c.roadRight.reversed() { road.addLine(to: frame.point(d)) }
            road.closeSubpath()
            ctx.fill(road, with: .color(LineColors.road))
        }
        drawByPedal(c.reference, frame, 5, &ctx)
        ctx.stroke(path(frame, c.target), with: .color(LineColors.gain), style: StrokeStyle(lineWidth: 2, lineJoin: .round))
    }
}

/// Fits metres east and north into the view, north up, one scale on both axes, centred.
struct MapFrame {
    let minEast: Double
    let minNorth: Double
    let scale: Double
    let offset: CGPoint
    let height: CGFloat

    init(dots: [MapDot], size: CGSize, padding: CGFloat, margin: Double) {
        let east = dots.map(\.east), north = dots.map(\.north)
        minEast = (east.min() ?? 0) - margin
        minNorth = (north.min() ?? 0) - margin
        let w = max(1, (east.max() ?? 1) + margin - minEast)
        let h = max(1, (north.max() ?? 1) + margin - minNorth)
        let room = CGSize(width: max(1, size.width - 2 * padding), height: max(1, size.height - 2 * padding))
        scale = min(room.width / w, room.height / h)
        offset = CGPoint(x: padding + (room.width - w * scale) / 2, y: padding + (room.height - h * scale) / 2)
        height = size.height
    }

    func point(_ d: MapDot) -> CGPoint {
        CGPoint(x: offset.x + (d.east - minEast) * scale, y: height - offset.y - (d.north - minNorth) * scale)
    }
}
