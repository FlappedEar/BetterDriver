import SwiftUI

/// The G radar on the second screen (FBBD-318, UI-240 A), as the Android app's GRadarView: the 0.5 g
/// and 1 g rings only (UI-241 B), no readouts (UI-242 C). The dot moves the way the driver is pushed
/// (UI-243 A): braking moves it up, a right turn moves it left. The trail is the half second before the
/// dot, smaller and fainter the older it is. Drawn at the screen's 4 Hz with nothing animated between.
/// Beyond [edgeG] the dot stays on the edge.
struct GRadarView: View {
    let radar: TrackScreenModel.Radar?

    /// The radar reaches this many g at its edge: Arek's laps reach about 1.0 g braking and cornering.
    static let edgeG: CGFloat = 1.2
    private static let halfG: CGFloat = 0.5
    /// The axes stop short of the edge, so the Brake and Gas labels sit clear of them.
    private static let axisG: CGFloat = 1.1
    /// The oldest trail point's opacity; the newest nears the dot's.
    private static let trailMinAlpha = 0.3
    /// The newest trail point's size as a part of the dot's.
    private static let trailShare: CGFloat = 0.6
    private static let padding: CGFloat = 8
    private static let ringWidth: CGFloat = 1.5
    private static let dotRadius: CGFloat = 9
    private static let trailMinRadius: CGFloat = 2
    private static let labelGap: CGFloat = 3
    private static let labelRise: CGFloat = 9

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            // One g from the centre.
            let g = min(size.width, size.height) / 2 / Self.edgeG
            ZStack {
                Canvas { context, size in draw(&context, size) }
                VStack {
                    TrackLabel(text: "Brake")
                    Spacer(minLength: 0)
                    TrackLabel(text: "Gas")
                }
                // Each ring's label starts just right of where it crosses the axis, above the axis.
                ringLabel("0.5", x: size.width / 2 + g * Self.halfG + Self.labelGap, y: size.height / 2 - Self.labelRise)
                ringLabel("1 g", x: size.width / 2 + g + Self.labelGap, y: size.height / 2 - Self.labelRise)
            }
        }
        .padding(Self.padding)
        .background(TrackColors.surface, in: RoundedRectangle(cornerRadius: 8))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("G radar")
        .accessibilityIdentifier("gRadar")
    }

    /// A label whose leading edge is at [x], its middle at [y].
    private func ringLabel(_ text: String, x: CGFloat, y: CGFloat) -> some View {
        Color.clear
            .frame(width: 0, height: 0)
            .overlay(alignment: .leading) { TrackLabel(text: text).fixedSize() }
            .position(x: x, y: y)
    }

    private func draw(_ context: inout GraphicsContext, _ size: CGSize) {
        let centre = CGPoint(x: size.width / 2, y: size.height / 2)
        let g = min(size.width, size.height) / 2 / Self.edgeG
        func ring(_ r: CGFloat) -> Path {
            Path(ellipseIn: CGRect(x: centre.x - r, y: centre.y - r, width: r * 2, height: r * 2))
        }
        let stroke = StrokeStyle(lineWidth: Self.ringWidth)
        context.stroke(ring(g), with: .color(TrackColors.line), style: stroke)
        context.stroke(ring(g * Self.halfG), with: .color(TrackColors.line), style: stroke)
        let reach = g * Self.axisG
        var axes = Path()
        axes.move(to: CGPoint(x: centre.x - reach, y: centre.y))
        axes.addLine(to: CGPoint(x: centre.x + reach, y: centre.y))
        axes.move(to: CGPoint(x: centre.x, y: centre.y - reach))
        axes.addLine(to: CGPoint(x: centre.x, y: centre.y + reach))
        context.stroke(axes, with: .color(TrackColors.line), style: stroke)
        func dot(_ p: TrackScreenModel.RadarPoint, _ radius: CGFloat, _ color: Color, _ alpha: Double) {
            let at = Self.position(p, centre: centre, g: g)
            let rect = CGRect(x: at.x - radius, y: at.y - radius, width: radius * 2, height: radius * 2)
            context.fill(Path(ellipseIn: rect), with: .color(color.opacity(alpha)))
        }
        let trail = radar?.trail ?? []
        for (i, p) in trail.enumerated() {
            let age = CGFloat(i + 1) / CGFloat(trail.count + 1)
            let radius = Self.trailMinRadius + (Self.dotRadius - Self.trailMinRadius) * age * Self.trailShare
            dot(p, radius, TrackColors.textDim, Self.trailMinAlpha + (1 - Self.trailMinAlpha) * Double(age))
        }
        if let now = radar?.now { dot(now, Self.dotRadius, TrackColors.text, 1) }
    }

    /// Where [p] is drawn: braking (forward < 0) up, a right turn (left < 0) left, held on the edge
    /// beyond [edgeG].
    static func position(_ p: TrackScreenModel.RadarPoint, centre: CGPoint, g: CGFloat) -> CGPoint {
        let forward = CGFloat(p.forward), left = CGFloat(p.left)
        let r = hypot(forward, left)
        let k = r > edgeG ? edgeG / r : 1
        return CGPoint(x: centre.x + left * k * g, y: centre.y + forward * k * g)
    }
}
