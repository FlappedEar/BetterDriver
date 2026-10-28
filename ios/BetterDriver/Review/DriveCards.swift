import SwiftUI
import BetterDriverCore

/// A session with no laps (FBBD-342), a road drive for example, as Android's DriveCards: its
/// figures and route on the map, coloured by speed as the Map tab colours a lap, then its speed
/// along the drive. Touching the map or the chart reads the same point on both.
struct DriveCards: View {
    let drive: DriveSummary
    @State private var cursor: Double?

    var body: some View {
        Card {
            SectionTitle("Drive · whole session")
            HStack(alignment: .top, spacing: 12) {
                DriveFigure(label: "Distance", value: drive.figures.distance)
                DriveFigure(label: "Time", value: drive.figures.time)
                DriveFigure(label: "Average", value: drive.figures.averageSpeed)
                DriveFigure(label: "Top", value: drive.figures.topSpeed)
            }
            readout
            DriveRoute(drive: drive, cursor: $cursor)
                .aspectRatio(drive.aspect, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("driveMap")
            legend
            Dim("Average while moving. No laps, so the route is the whole session; GPS gaps leave it open.")
        }
        if !drive.speed.isEmpty && drive.distanceMeters > 0 {
            Card {
                SectionTitle("Speed, km/h")
                DriveChart(drive: drive, cursor: $cursor)
                    .frame(height: 150)
                    .accessibilityIdentifier("driveSpeedChart")
                HStack {
                    Dim("0")
                    Spacer()
                    Dim(DriveUiKt.distanceText(meters: drive.distanceMeters))
                }
            }
        }
    }

    /// What the cursor reads: how far into the drive, and the speed there.
    @ViewBuilder private var readout: some View {
        if let cursor, let dot = drive.nearest(meters: cursor) {
            HStack(spacing: 12) {
                Dim(DriveUiKt.distanceText(meters: dot.meters))
                Text(dot.speed.map { "\(Int($0.rounded())) km/h" } ?? "–")
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.onBackground)
            }
            .accessibilityIdentifier("driveReadout")
        } else {
            Dim("Touch or drag along the line or the chart to read a point.")
        }
    }

    /// The Map tab's speed legend: the scale's colours, slow to fast.
    private var legend: some View {
        VStack(spacing: 4) {
            RoundedRectangle(cornerRadius: 4)
                .fill(LinearGradient(
                    colors: MapUiKt.SpeedScaleColors.map { Color(argb: $0.int32Value) },
                    startPoint: .leading,
                    endPoint: .trailing
                ))
                .frame(height: 8)
            HStack {
                Dim(drive.scale.map { "\(Int($0.low)) km/h" } ?? "–")
                Spacer()
                Dim("slow to fast")
                Spacer()
                Dim(drive.scale.map { "\(Int($0.high)) km/h" } ?? "")
            }
        }
    }
}

private struct DriveFigure: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            SectionTitle(label)
            Text(value)
                .font(.system(size: 18, weight: .semibold).monospacedDigit())
                .foregroundStyle(Palette.onBackground)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

extension DriveSummary {
    /// The route's point nearest [meters] into the drive.
    func nearest(meters: Double) -> DriveDot? {
        runs.joined().min { abs($0.meters - meters) < abs($1.meters - meters) }
    }
}

/// The route, each stretch coloured by its speed; touching it moves the cursor to the point under
/// the finger, as Android's LapMapView.
private struct DriveRoute: View {
    let drive: DriveSummary
    @Binding var cursor: Double?
    private let padding: CGFloat = 16
    private let touchRadius: CGFloat = 32

    var body: some View {
        GeometryReader { geo in
            let frame = MapFrame(dots: dots, size: geo.size, padding: padding, margin: 0)
            Canvas { ctx, _ in
                for run in drive.runs where run.count > 1 {
                    for i in 1..<run.count {
                        var p = Path()
                        p.move(to: frame.point(dot(run[i - 1])))
                        p.addLine(to: frame.point(dot(run[i])))
                        ctx.stroke(p, with: .color(run[i].color), style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    }
                }
                if let cursor, let at = drive.nearest(meters: cursor) {
                    let c = frame.point(dot(at))
                    ctx.fill(Path(ellipseIn: CGRect(x: c.x - 6, y: c.y - 6, width: 12, height: 12)), with: .color(Palette.onBackground))
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                var best: (DriveDot, CGFloat)?
                for d in drive.runs.joined() {
                    let p = frame.point(dot(d))
                    let distance = hypot(p.x - g.location.x, p.y - g.location.y)
                    if distance <= touchRadius && distance < (best?.1 ?? .infinity) { best = (d, distance) }
                }
                if let best { cursor = best.0.meters }
            })
        }
    }

    private var dots: [MapDot] { drive.runs.joined().map(dot) }

    private func dot(_ d: DriveDot) -> MapDot { MapDot(east: d.east, north: d.north, pedal: nil) }
}

/// The speed along the drive, as the Compare tab draws it, with the shared cursor.
private struct DriveChart: View {
    let drive: DriveSummary
    @Binding var cursor: Double?

    var body: some View {
        GeometryReader { geo in
            let span = ChartMath.range([drive.speed], includeZero: false)
            let low = span.0, high = span.1
            let length = max(1, drive.distanceMeters)
            let w = geo.size.width, h = geo.size.height
            let x = { (m: Double) in CGFloat(m / length) * w }
            let y = { (v: Double) in CGFloat((high - v) / (high - low)) * h }
            Canvas { ctx, _ in
                for run in drive.speed where run.count > 1 {
                    var p = Path()
                    for (i, d) in run.enumerated() {
                        let pt = CGPoint(x: x(d.meters), y: y(d.value))
                        if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
                    }
                    ctx.stroke(p, with: .color(Palette.best), lineWidth: 2)
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
