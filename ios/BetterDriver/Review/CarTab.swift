import SwiftUI
import BetterDriverCore

/// The Car tab, as on Android (CarTab.kt, FBBD-176): pedals with the FR-S brake pressure, the FR-S
/// steering wheel, temperatures and heart rate, each only when recorded.
struct CarTab: View {
    let car: CarUi?

    var body: some View {
        CardColumn {
            if let car, !car.empty {
                PedalCard(car: car)
                if let s = car.steering { SteeringCard(s: s) }
                TemperatureCard(car: car)
                HeartRateCard(hr: car.heartRate)
            } else {
                Card { Dim("No car data or heart rate in this session: no OBD adapter or heart rate strap recorded it.") }
            }
        }
        .accessibilityIdentifier("carTab")
    }
}

/// Android's PartAlpha, the part throttle share's shade of the full throttle colour.
private let partAlpha = 0.5
/// Pedal shares count above this speed (VehicleStats.kt PedalMinimumSpeedKmh).
private let pedalMinimumKmh = 20
private let chartHeight: CGFloat = 120

private func pct(_ share: Double) -> String { "\(Int((share * 100).rounded())) %" }

/// The lap's number and time, or "Whole drive" for a road drive.
private struct RowName: View {
    let row: CarRow

    var body: some View {
        if let lap = row.lapNumber {
            Dim("Lap \(lap.intValue)").frame(width: 48, alignment: .leading)
            Text(row.millis.map { CoreBridge.lapTime(millis: $0.int64Value) } ?? "")
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(row.best ? Palette.best : Palette.onBackground)
                .frame(width: 72, alignment: .leading)
        } else {
            Dim("Whole drive").frame(width: 120, alignment: .leading)
        }
    }
}

/// UI-158 C: one bar per lap, full throttle, part throttle, coasting and braking.
private struct PedalCard: View {
    let car: CarUi

    var body: some View {
        Card {
            SectionTitle(car.rows.contains { $0.lapNumber != nil } ? "Pedals by lap" : "Pedals · whole drive")
            if !car.hasPedals {
                Dim("Not recorded in this session")
                if let b = car.brakePressure { BrakeFigures(b: b) }
            } else {
                ForEach(Array(car.rows.enumerated()), id: \.offset) { _, row in
                    HStack(spacing: 0) {
                        RowName(row: row)
                        if let p = row.pedals { PedalBar(p: p) } else { Dim("Not recorded") }
                    }
                }
                let withBrake = car.rows.contains { $0.pedals?.braking != nil }
                Key(keys: withBrake
                    ? [(Palette.gain, "Full"), (Palette.gain.opacity(partAlpha), "Part"), (Palette.onSurfaceVariant, "Coast"), (Palette.loss, "Brake")]
                    : [(Palette.gain, "Full"), (Palette.gain.opacity(partAlpha), "Part"), (Palette.onSurfaceVariant, "Off throttle")])
                if let b = car.brakePressure { BrakeFigures(b: b) }
                Dim("Share of the time above \(pedalMinimumKmh) km/h. Full = pedal 90 % or more.")
            }
        }
    }
}

private struct PedalBar: View {
    let p: PedalShares

    var body: some View {
        var parts: [(Double, Color)] = [(p.full, Palette.gain), (p.part, Palette.gain.opacity(partAlpha)), (p.coasting, Palette.onSurfaceVariant)]
        if let b = p.braking { parts.append((b.doubleValue, Palette.loss)) }
        let text = "full \(pct(p.full)), part \(pct(p.part)), coast \(pct(p.coasting))" + (p.braking.map { ", brake \(pct($0.doubleValue))" } ?? "")
        return GeometryReader { geo in
            HStack(spacing: 0) {
                ForEach(Array(parts.enumerated()), id: \.offset) { _, part in
                    Rectangle().fill(part.1).frame(width: max(0, part.0 * geo.size.width))
                }
                Spacer(minLength: 0)
            }
        }
        .frame(height: 12)
        .clipShape(RoundedRectangle(cornerRadius: 3))
        .accessibilityElement()
        .accessibilityLabel(text)
    }
}

private struct Key: View {
    let keys: [(Color, String)]

    var body: some View {
        keys.enumerated().reduce(Text("")) { text, item in
            let (i, key) = item
            return text + Text(i > 0 ? "  " : "") + Text("■ ").foregroundColor(key.0) + Text(key.1)
        }
        .font(.caption)
        .foregroundStyle(Palette.onSurfaceVariant)
    }
}

/// A label dim, the value bold, the unit dim, as the heart rate's figures.
private struct Figures: View {
    let figures: [(String, String, String)]

    var body: some View {
        HStack(spacing: 16) {
            ForEach(Array(figures.enumerated()), id: \.offset) { _, f in
                (Text("\(f.0) ").foregroundColor(Palette.onSurfaceVariant)
                    + Text(f.1).fontWeight(.semibold)
                    + Text(f.2).foregroundColor(Palette.onSurfaceVariant))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(Palette.onBackground)
            }
        }
    }
}

/// UI-251 A: the hardest press, the typical stop and the time at half braking or more.
private struct BrakeFigures: View {
    let b: BrakePressureUi

    var body: some View {
        var figures = [("Hardest brake", "\(b.hardest)", " \(b.unit)")]
        if let t = b.typicalStop { figures.append(("Typical brake", "\(t.intValue)", " \(b.unit)")) }
        figures.append(("Brake 50 % +", CarUiKt.figureSeconds(s: b.hardSeconds), b.perLap ? " s/lap" : " s"))
        return Figures(figures: figures)
    }
}

/// UI-250 A: the most lock each way, the time over 90°, and the best lap's wheel (up = left).
private struct SteeringCard: View {
    let s: SteeringUi

    var body: some View {
        Card {
            SectionTitle("Steering · FR-S")
            Figures(figures: [
                ("Most left", "\(s.mostLeft)", "°"),
                ("Most right", "\(s.mostRight)", "°"),
                ("Over 90°", CarUiKt.figureSeconds(s: s.largeLockSeconds), s.perLap ? " s/lap" : " s"),
            ])
            if !s.trace.isEmpty {
                SteeringChart(s: s).frame(height: chartHeight)
                let lap = s.traceLapMillis.map { "Best lap, \(CoreBridge.lapTime(millis: $0.int64Value))." } ?? "Best lap."
                Dim("\(lap) Up = left, down = right.")
            }
        }
    }
}

private struct SteeringChart: View {
    let s: SteeringUi
    private let minDegrees = 200.0
    private let gridDegrees = 90.0

    var body: some View {
        let points = s.trace.flatMap { $0 }
        let most = points.map { abs($0.value) }.max() ?? 0
        // At least ±200° so a quiet lap does not look busy; a lap with more lock gets room for it.
        let limit = max(minDegrees, (most / gridDegrees).rounded(.up) * gridDegrees)
        // The whole lap is the axis, so a reading that stopped early leaves the rest blank.
        let span = max(1, s.traceLapMillis.map { Double($0.int64Value) / 1000 } ?? (points.map(\.time).max() ?? 1))
        let lines = Int(limit / gridDegrees)
        return ZStack(alignment: .topLeading) {
            Canvas { ctx, size in
                func x(_ t: Double) -> CGFloat { CGFloat(t / span) * size.width }
                func y(_ v: Double) -> CGFloat { CGFloat((limit - v) / (2 * limit)) * size.height }
                for i in 1...max(1, lines) {
                    for v in [Double(i) * gridDegrees, -Double(i) * gridDegrees] {
                        ctx.stroke(Path { $0.move(to: CGPoint(x: 0, y: y(v))); $0.addLine(to: CGPoint(x: size.width, y: y(v))) }, with: .color(Palette.outline), lineWidth: 1)
                    }
                }
                ctx.stroke(Path { $0.move(to: CGPoint(x: 0, y: y(0))); $0.addLine(to: CGPoint(x: size.width, y: y(0))) }, with: .color(Palette.onSurfaceVariant), lineWidth: 1)
                for segment in s.trace {
                    var path = Path()
                    for (i, p) in segment.enumerated() {
                        let pt = CGPoint(x: x(p.time), y: y(p.value))
                        if i == 0 { path.move(to: pt) } else { path.addLine(to: pt) }
                    }
                    ctx.stroke(path, with: .color(Palette.cold), lineWidth: 1.5)
                }
            }
            VStack(alignment: .leading) {
                Text("\(Int(limit.rounded()))° L")
                Spacer()
                Text("\(Int(limit.rounded()))° R")
            }
            .font(.caption)
            .foregroundStyle(Palette.onSurfaceVariant)
        }
        .accessibilityElement()
        .accessibilityLabel("Steering wheel over the best lap, \(s.mostLeft)° left to \(s.mostRight)° right")
    }
}

/// UI-159 B: the lowest and highest of each recorded temperature, per lap.
private struct TemperatureCard: View {
    let car: CarUi
    private let width: CGFloat = 66

    var body: some View {
        let kinds = car.temperatureKinds
        let byLap = car.rows.contains { $0.lapNumber != nil }
        return Card {
            SectionTitle(byLap ? "Temperatures by lap · min–max" : "Temperatures · min–max")
            if kinds.isEmpty {
                Dim("Not recorded in this session")
            } else {
                let units = unitsByKind()
                let shared = Set(units.values).count == 1 ? units.values.first : nil
                HStack(spacing: 0) {
                    SectionTitle(shared ?? "").frame(maxWidth: .infinity, alignment: .leading)
                    ForEach(Array(kinds.enumerated()), id: \.offset) { _, k in
                        let name = CarUiKt.temperatureName(kind: k)
                        SectionTitle(shared == nil ? "\(name) \(units[k.name] ?? "")" : name)
                            .frame(width: width, alignment: .trailing)
                    }
                }
                ForEach(Array(car.rows.enumerated()), id: \.offset) { _, row in
                    HStack(spacing: 0) {
                        // Only the lap's number, as on Android.
                        Text(row.lapNumber.map { "Lap \($0.intValue)" } ?? "Whole drive")
                            .foregroundStyle(row.best ? Palette.best : Palette.onSurfaceVariant)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        ForEach(Array(kinds.enumerated()), id: \.offset) { _, k in
                            let r = row.temperatures.first { $0.kind == k }
                            Text(r.map { "\(Int($0.min.rounded()))–\(Int($0.max.rounded()))" } ?? "—")
                                .foregroundStyle(r == nil ? Palette.onSurfaceVariant : Palette.onBackground)
                                .frame(width: width, alignment: .trailing)
                        }
                    }
                    .font(.subheadline.monospacedDigit())
                }
                let missing = car.missingTemperatures
                if !missing.isEmpty {
                    Dim("Not recorded: " + missing.map { CarUiKt.temperatureName(kind: $0).lowercased() }.joined(separator: ", "))
                }
            }
        }
    }

    /// Each temperature's unit, by the channel's name.
    private func unitsByKind() -> [String: String] {
        var units: [String: String] = [:]
        for row in car.rows {
            for t in row.temperatures where units[t.kind.name] == nil { units[t.kind.name] = t.unit }
        }
        return units
    }
}

/// UI-160 A: the heart rate figures with a chart of the session, laps shaded, gaps blank.
private struct HeartRateCard: View {
    let hr: HeartRateChartUi?

    var body: some View {
        Card {
            SectionTitle(hr?.stats.device.map { "Heart rate · \($0)" } ?? "Heart rate")
            if let hr {
                Figures(figures: [
                    ("Average", "\(hr.stats.averageBpm)", " bpm"),
                    ("Max", "\(hr.stats.maxBpm)", " bpm"),
                    ("Min", "\(hr.stats.minBpm)", " bpm"),
                ])
                HeartRateChart(hr: hr).frame(height: chartHeight)
                Dim("Whole session, \(hr.stats.minutes) min.\(hr.laps.isEmpty ? "" : " Laps shaded.") Gaps stay blank.")
            } else {
                Dim("Not recorded in this session")
            }
        }
    }
}

private struct HeartRateChart: View {
    let hr: HeartRateChartUi
    private let padding = 5.0

    var body: some View {
        let values = hr.segments.flatMap { $0 }.map(\.value)
        let low = (values.min() ?? 0) - padding
        let high = (values.max() ?? 0) + padding
        let span = max(1, hr.toSeconds - hr.fromSeconds)
        return ZStack(alignment: .topLeading) {
            Canvas { ctx, size in
                func x(_ t: Double) -> CGFloat { CGFloat((t - hr.fromSeconds) / span) * size.width }
                func y(_ v: Double) -> CGFloat { size.height - CGFloat((v - low) / (high - low)) * size.height }
                for (i, lap) in hr.lapSpans.enumerated() where i % 2 == 0 && lap.count == 2 {
                    let from = lap[0].doubleValue, to = lap[1].doubleValue
                    ctx.fill(Path(CGRect(x: x(from), y: 0, width: x(to) - x(from), height: size.height)), with: .color(Palette.surfaceVariant))
                }
                for segment in hr.segments {
                    if segment.count == 1, let p = segment.first {
                        ctx.fill(Path(ellipseIn: CGRect(x: x(p.time) - 1.5, y: y(p.value) - 1.5, width: 3, height: 3)), with: .color(Palette.loss))
                        continue
                    }
                    var path = Path()
                    for (i, p) in segment.enumerated() {
                        let pt = CGPoint(x: x(p.time), y: y(p.value))
                        if i == 0 { path.move(to: pt) } else { path.addLine(to: pt) }
                    }
                    ctx.stroke(path, with: .color(Palette.loss), lineWidth: 1.5)
                }
            }
            VStack(alignment: .leading) {
                Text("\(hr.stats.maxBpm)")
                Spacer()
                Text("\(hr.stats.minBpm)")
            }
            .font(.caption)
            .foregroundStyle(Palette.onSurfaceVariant)
        }
        .accessibilityElement()
        .accessibilityLabel("Heart rate over the session, \(hr.stats.minBpm) to \(hr.stats.maxBpm) bpm")
    }
}
