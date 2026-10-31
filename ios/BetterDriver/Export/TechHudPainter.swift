import BetterDriverCore
import CoreImage
import UIKit

/// The data overlay (FBBD-152) in FlappedEar Overlays' "Tech HUD" layout (FBBD-347), as Android's
/// TechHudPainter draws it: dark plates with two cut corners and an amber tab, Chakra Petch, the
/// template's places, sizes and colours. Places come from the shared HudGeometry, the values and texts
/// from HudMoment; a widget the session has no value for is left out. Called on AVFoundation's threads.
final class TechHudPainter: @unchecked Sendable {
    private let session: DataVideoSession
    private let cuts: [DataVideoCut]
    private let part: Int32
    private let outline: [Xy]
    private let lock = NSLock()
    private var shaped: (size: CGSize, hud: HudGeometry)?

    /// [part]: 0 for the whole video, else the lap whose video this is (FBBD-346).
    init(session: DataVideoSession, cuts: [DataVideoCut], part: Int32) {
        self.session = session
        self.cuts = cuts
        self.part = part
        outline = session.outline
        _ = TrackFonts.register
    }

    /// The HUD for the frame at [outputMicros] of the video on a picture of [size], in Core Image's
    /// coordinates (origin bottom left); nil outside the cuts. Only the strips with widgets are drawn.
    func overlay(at outputMicros: Int64, size: CGSize) -> CIImage? {
        guard size.width >= 1, size.height >= 1,
              let cut = cuts.last(where: { $0.outputStartMicros <= outputMicros }) ?? cuts.first else { return nil }
        let sessionMicros = cut.sessionStartMicros + (outputMicros - cut.outputStartMicros)
        let moment = session.moment(sessionMicros: sessionMicros, part: part)
        let hud = geometry(size)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        var out: CIImage?
        for strip in hud.strips {
            let top = CGFloat(strip.top)
            let height = CGFloat(max(1, strip.bottom - strip.top))
            let places = hud.places.filter { CGFloat($0.box.top) < top + height && CGFloat($0.box.bottom) > top }
            let image = UIGraphicsImageRenderer(size: CGSize(width: CGFloat(hud.width), height: height), format: format).image { ui in
                ui.cgContext.translateBy(x: 0, y: -top)
                self.draw(ui.cgContext, hud, places, moment)
            }
            guard let cg = image.cgImage else { continue }
            let placed = CIImage(cgImage: cg).transformed(by: CGAffineTransform(translationX: 0, y: CGFloat(hud.height) - top - height))
            out = out.map { placed.composited(over: $0) } ?? placed
        }
        return out
    }

    private func geometry(_ size: CGSize) -> HudGeometry {
        lock.lock()
        defer { lock.unlock() }
        if let shaped, shaped.size == size { return shaped.hud }
        let hud = session.hud(videoWidth: Int32(size.width.rounded()), videoHeight: Int32(size.height.rounded()))
        shaped = (size, hud)
        return hud
    }

    func draw(_ cg: CGContext, _ hud: HudGeometry, _ places: [HudPlace], _ m: HudMoment) {
        let s = CGFloat(hud.scale)
        for place in places {
            let b = Rect(place.box)
            // By the Kotlin entry's name: Kotlin/Native renames multi-word entries for Swift.
            switch place.widget.name {
            case "Tachometer": tachometer(cg, b, m)
            case "Lap": lap(cg, b, s, m)
            case "Pedals": pedals(cg, b, s, m)
            case "Oil", "Gearbox", "Coolant": row(cg, b, s, place.stack, place.widget, m)
            case "Tyres": tyres(cg, b, s, m)
            case "HeartRate": heartRate(cg, b, s, m)
            case "GRadar": radar(cg, b, m)
            case "GReadout": gReadout(cg, b, s, m)
            case "Steering": steering(cg, b, s, m)
            case "TrackMap": map(cg, b, s, m)
            default: break
            }
        }
    }

    // TechPanel.qml: a dark plate with the top right and bottom left corners cut, an amber tab on top.
    private func panel(_ cg: CGContext, _ b: Rect, _ s: CGFloat, _ stack: StackPosition = .single) {
        let cutTop = stack == .single || stack == .top
        let cutBottom = stack == .single || stack == .bottom
        let cut = min(18 * s, min(b.width / 3, b.height / 2))
        let path = CGMutablePath()
        path.move(to: CGPoint(x: b.left, y: b.top))
        path.addLine(to: CGPoint(x: cutTop ? b.right - cut : b.right, y: b.top))
        path.addLine(to: CGPoint(x: b.right, y: cutTop ? b.top + cut : b.top))
        path.addLine(to: CGPoint(x: b.right, y: b.bottom))
        path.addLine(to: CGPoint(x: cutBottom ? b.left + cut : b.left, y: b.bottom))
        path.addLine(to: CGPoint(x: b.left, y: cutBottom ? b.bottom - cut : b.bottom))
        path.closeSubpath()
        cg.setFillColor(Hud.plate.cgColor)
        cg.addPath(path)
        cg.fillPath()
        if cutTop {
            cg.setFillColor(Hud.amber.cgColor)
            cg.fill(CGRect(x: b.left, y: b.top, width: min(b.width * 0.3, 46 * s), height: max(2, 3.5 * s)))
        }
    }

    private func pad(_ s: CGFloat) -> CGFloat { 14 * s }

    // TechTachometer.qml: a 270° arc with a red zone, the scale in thousands; the gear and speed in the middle.
    private func tachometer(_ cg: CGContext, _ b: Rect, _ m: HudMoment) {
        let side = min(b.width, b.height)
        let cx = b.centerX, cy = b.centerY
        let r = side * 0.43
        let redFrom = rpmRatio(Hud.rpmWarning)
        func arc(_ from: CGFloat, _ sweep: CGFloat) {
            cg.addArc(center: CGPoint(x: cx, y: cy), radius: r, startAngle: radians(Hud.arcStart + Hud.arcSweep * from),
                      endAngle: radians(Hud.arcStart + Hud.arcSweep * (from + sweep)), clockwise: false)
            cg.strokePath()
        }
        cg.setLineWidth(max(2, side * 0.02))
        cg.setLineCap(.round)
        cg.setStrokeColor(Hud.white.withAlphaComponent(0.16).cgColor)
        arc(0, 1)
        cg.setLineCap(.butt)
        cg.setStrokeColor(Hud.red.withAlphaComponent(0.5).cgColor)
        arc(redFrom, 1 - redFrom)

        let steps = min(16, max(2, Int((Hud.rpmMax / 1000).rounded())))
        let label = semibold(max(8, side * 0.052))
        for i in 0 ... steps {
            let t = CGFloat(i) / CGFloat(steps)
            let a = radians(Hud.arcStart + Hud.arcSweep * t)
            cg.setStrokeColor((t >= redFrom ? Hud.red : Hud.white.withAlphaComponent(0.6)).cgColor)
            cg.setLineWidth(max(1, side * 0.009))
            cg.strokeLineSegments(between: [
                CGPoint(x: cx + cos(a) * r * 0.84, y: cy + sin(a) * r * 0.84),
                CGPoint(x: cx + cos(a) * r * 0.9, y: cy + sin(a) * r * 0.9),
            ])
            let text = String(Int((Hud.rpmMax * t / 1000).rounded()))
            centred(text, label, Hud.white.withAlphaComponent(0.7), cx + cos(a) * r * 0.72, cy + sin(a) * r * 0.72)
        }
        if let rpm = m.rpm?.doubleValue {
            cg.setLineCap(.round)
            cg.setLineWidth(max(2, side * 0.02))
            cg.setStrokeColor((rpm >= Hud.rpmWarning ? Hud.red : Hud.text).cgColor)
            arc(0, max(0.001, rpmRatio(rpm)))
        }

        tachometerText(cx, cy, side, m)
    }

    /** The gauge's middle: the gear and the speed (UI-310 A), or the speed alone without a gearbox. */
    private func tachometerText(_ cx: CGFloat, _ cy: CGFloat, _ side: CGFloat, _ m: HudMoment) {
        let gap = side * 0.012
        let kern = side * 0.008
        if m.gears {
            // UI-310 A (FBBD-397): the gear large in amber, the speed small under it; no RPM number.
            let gearFont = bold(side * 0.35)
            let speedFont = bold(side * 0.1)
            let unit = semibold(side * 0.045)
            let total = lineHeight(gearFont) + lineHeight(speedFont) + lineHeight(unit) + 2 * gap
            var top = cy + side * 0.02 - total / 2
            textTop(m.gear, gearFont, m.gearKnown ? Hud.amber : Hud.dim, x: cx - width(m.gear, gearFont) / 2, top: top)
            top += lineHeight(gearFont) + gap
            textTop(m.speed, speedFont, Hud.text, x: cx - width(m.speed, speedFont) / 2, top: top)
            top += lineHeight(speedFont) + gap
            textTop("KM/H", unit, Hud.dim, x: cx - width("KM/H", unit, kern) / 2, top: top, kern: kern)
            return
        }
        // No gearbox in the session (no engine speed, a hybrid): the speed as before, without the RPM number.
        let big = bold(side * 0.25)
        let unit = semibold(side * 0.055)
        var top = cy + side * 0.04 - (lineHeight(big) + lineHeight(unit) + gap) / 2
        textTop(m.speed, big, Hud.text, x: cx - width(m.speed, big) / 2, top: top)
        top += lineHeight(big) + gap
        textTop("KM/H", unit, Hud.dim, x: cx - width("KM/H", unit, kern) / 2, top: top, kern: kern)
    }

    private func rpmRatio(_ v: Double) -> CGFloat { CGFloat(min(1, max(0, v / Hud.rpmMax))) }

    // TechLapTime.qml: "LAP" and the lap number in amber, the running time, the best lap, the delta.
    private func lap(_ cg: CGContext, _ b: Rect, _ s: CGFloat, _ m: HudMoment) {
        panel(cg, b, s)
        let p = pad(s)
        let h = b.height
        textTop(m.lapCaption, semibold(h * 0.1), Hud.dim, x: b.left + p, top: b.top + p * 1.2, kern: h * 0.012)
        let numberFont = bold(h * 0.15)
        textTop(m.lapNumber, numberFont, Hud.amber, x: b.right - p * 1.4 - width(m.lapNumber, numberFont), top: b.top + p)

        let timeFont = bold(min(h * 0.3, (b.width - 2 * p) / 4.6))
        textMiddle(m.lapTimeText, timeFont, m.lapFinished ? Hud.green : Hud.text, x: b.left + p, centerY: b.centerY + h * 0.03)

        let bottom = b.bottom - p * 0.9
        let small = semibold(h * 0.1)
        let x = textBottom("BEST ", small, Hud.dim, x: b.left + p, bottom: bottom)
        textBottom(m.bestText, small, Hud.text, x: x, bottom: bottom)
        if let delta = m.deltaText {
            let f = bold(h * 0.13)
            textBottom(delta, f, m.deltaAhead ? Hud.green : Hud.orange, x: b.right - p * 1.4 - width(delta, f), bottom: bottom)
        }
    }

    // TechPedals.qml: throttle and brake, each a label, its % and a slanted bar.
    private func pedals(_ cg: CGContext, _ b: Rect, _ s: CGFloat, _ m: HudMoment) {
        panel(cg, b, s)
        let p = pad(s)
        let left = b.left + p, right = b.right - p
        let top = b.top + p * 1.2
        let height = b.bottom - p - top
        let rowH = height * 0.42
        let spacing = height - 2 * rowH
        let rows: [(String, Double?, String, UIColor)] = [
            ("THROTTLE", m.throttle?.doubleValue, m.throttleText, Hud.green),
            ("BRAKE", m.brake?.doubleValue, m.brakeText, Hud.red),
        ]
        for (i, row) in rows.enumerated() {
            let rowTop = top + CGFloat(i) * (rowH + spacing)
            textTop(row.0, semibold(rowH * 0.3), Hud.dim, x: left, top: rowTop, kern: rowH * 0.03)
            let f = bold(rowH * 0.34)
            textTop(row.2, f, Hud.text, x: right - width(row.2, f), top: rowTop)
            let barH = rowH * 0.4
            let barTop = rowTop + rowH - barH
            let barLeft = left + barH * 0.33
            cg.setFillColor(Hud.white.withAlphaComponent(0.1).cgColor)
            slanted(cg, barLeft, right, barTop, barH)
            let fraction = CGFloat(min(1, max(0, (row.1 ?? 0) / 100)))
            if fraction > 0 {
                cg.setFillColor(row.3.cgColor)
                slanted(cg, barLeft, barLeft + (right - barLeft) * fraction, barTop, barH)
            }
        }
    }

    /// A bar from [x0] to [x1] whose bottom edge sits a third of its height further left.
    private func slanted(_ cg: CGContext, _ x0: CGFloat, _ x1: CGFloat, _ top: CGFloat, _ h: CGFloat) {
        let shift = h * 0.33
        cg.move(to: CGPoint(x: x0, y: top))
        cg.addLine(to: CGPoint(x: x1, y: top))
        cg.addLine(to: CGPoint(x: x1 - shift, y: top + h))
        cg.addLine(to: CGPoint(x: x0 - shift, y: top + h))
        cg.closePath()
        cg.fillPath()
    }

    // TechCustomValue.qml: label, range bar with the normal band, value turning red at the warning.
    private func row(_ cg: CGContext, _ b: Rect, _ s: CGFloat, _ stack: StackPosition, _ w: HudWidget, _ m: HudMoment) {
        guard let spec = m.rowSpec(w: w) else { return }
        panel(cg, b, s, stack)
        let p = pad(s)
        let h = b.height
        let inner = b.width - 2 * p
        if stack == .middle || stack == .bottom {
            cg.setFillColor(Hud.white.withAlphaComponent(0.08).cgColor)
            cg.fill(CGRect(x: b.left + p, y: b.top, width: b.width - 2 * p, height: max(1, s)))
        }
        let value = m.rowValue(w: w)?.doubleValue
        let hot = m.rowHot(w: w)
        let spacing = h * 0.3
        let labelFont = semibold(h * 0.28)
        let kern = h * 0.02
        let labelW = max(inner * 0.24, min(width(spec.label, labelFont, kern), inner * 0.36))
        textMiddle(spec.label, labelFont, hot ? Hud.red : Hud.dim, x: b.left + p, centerY: b.centerY, kern: kern)

        let number = m.rowText(w: w)
        let numberFont = bold(h * 0.36)
        let unitFont = semibold(h * 0.36 * Hud.unitShare)
        let unit = " °C"
        let numberW = width(number, numberFont), unitW = width(unit, unitFont)
        let valueW = max(numberW + unitW, h * 1.5)
        let valueRight = b.right - p
        let baseline = middle(numberFont, b.centerY)
        put(unit, unitFont, Hud.dim, x: valueRight - unitW, baseline: baseline)
        put(number, numberFont, hot ? Hud.red : value != nil ? Hud.text : Hud.dim, x: valueRight - unitW - numberW, baseline: baseline)

        let barLeft = b.left + p + labelW + spacing
        let barRight = valueRight - valueW - spacing
        guard barRight > barLeft else { return }
        let barH = max(2, h * 0.13)
        let barTop = b.centerY - barH / 2
        let barW = barRight - barLeft
        func at(_ v: Double) -> CGFloat { CGFloat(spec.fraction(v: v)) }
        cg.setFillColor(Hud.white.withAlphaComponent(0.13).cgColor)
        cg.fill(CGRect(x: barLeft, y: barTop, width: barW, height: barH))
        cg.setFillColor(Hud.green.withAlphaComponent(0.35).cgColor)
        let low = barLeft + barW * at(spec.normalLow)
        cg.fill(CGRect(x: low, y: barTop, width: barLeft + barW * at(spec.normalHigh) - low, height: barH))
        if let value {
            let markW = max(2, h * 0.08)
            let x = barLeft + barW * at(value)
            cg.setFillColor((hot ? Hud.red : Hud.text).cgColor)
            cg.fill(CGRect(x: x - markW / 2, y: b.centerY - barH * 1.2, width: markW, height: barH * 2.4))
        }
    }

    // TechTyres.qml: a block per tyre coloured by temperature, its temperature and pressure beside it.
    private func tyres(_ cg: CGContext, _ b: Rect, _ s: CGFloat, _ m: HudMoment) {
        panel(cg, b, s)
        let p = pad(s)
        let h = b.height
        let title = semibold(h * 0.09)
        let titleTop = b.top + p * 1.2
        textTop("TYRES · °C / BAR", title, Hud.dim, x: b.left + p, top: titleTop, kern: h * 0.01)
        let gridTop = titleTop + lineHeight(title) + p * 0.6
        let gridW = b.width - 2 * p
        let gridH = b.bottom - p - gridTop
        let colGap = gridW * 0.1, rowGap = gridH * 0.08
        let cellW = (gridW - colGap) / 2, cellH = (gridH - rowGap) / 2
        for i in 0 ..< 4 {
            let right = i % 2 == 1
            let x0 = b.left + p + CGFloat(i % 2) * (cellW + colGap)
            let y0 = gridTop + CGFloat(i / 2) * (cellH + rowGap)
            let blockW = cellH * 0.42, blockH = cellH * 0.92
            let blockX = right ? x0 + cellW - blockW : x0
            let blockY = y0 + (cellH - blockH) / 2
            cg.setFillColor(tyreColor(m.tyreTemp(i: Int32(i))?.doubleValue).cgColor)
            cg.addPath(CGPath(roundedRect: CGRect(x: blockX, y: blockY, width: blockW, height: blockH),
                              cornerWidth: blockW * 0.2, cornerHeight: blockW * 0.2, transform: nil))
            cg.fillPath()
            let tText = m.tyreTempText(i: Int32(i)), pText = m.tyrePressureText(i: Int32(i))
            let tFont = bold(cellH * 0.44), pFont = semibold(cellH * 0.32)
            let top = y0 + (cellH - lineHeight(tFont) - lineHeight(pFont)) / 2
            let gap = cellH * 0.18
            func x(_ t: String, _ f: UIFont) -> CGFloat { right ? blockX - gap - width(t, f) : blockX + blockW + gap }
            textTop(tText, tFont, Hud.text, x: x(tText, tFont), top: top)
            textTop(pText, pFont, Hud.dim, x: x(pText, pFont), top: top + lineHeight(tFont))
        }
    }

    private func tyreColor(_ t: Double?) -> UIColor {
        guard let t else { return UIColor(rgb: 0x3A3D44) }
        if t < Hud.tyreCold { return UIColor(rgb: 0x3D8BFF) }
        if t > Hud.tyreHot { return Hud.red }
        return blend(Hud.green, Hud.amber, CGFloat((t - Hud.tyreCold) / (Hud.tyreHot - Hud.tyreCold)))
    }

    // TechHeartRate.qml: a red heart and "BPM" over the number.
    private func heartRate(_ cg: CGContext, _ b: Rect, _ s: CGFloat, _ m: HudMoment) {
        panel(cg, b, s)
        let h = b.height
        let icon = h * 0.14, gap = h * 0.05
        let label = semibold(h * 0.1)
        let kern = h * 0.012
        let rowH = max(icon, lineHeight(label))
        let value = bold(h * 0.36)
        let top = b.centerY - (rowH + h * 0.04 + lineHeight(value)) / 2
        let x = b.centerX - (icon + gap + width("BPM", label, kern)) / 2
        heart(cg, x, top + (rowH - icon) / 2, icon)
        textTop("BPM", label, Hud.dim, x: x + icon + gap, top: top + (rowH - lineHeight(label)) / 2, kern: kern)
        textTop(m.heartRateText, value, Hud.text, x: b.centerX - width(m.heartRateText, value) / 2, top: top + rowH + h * 0.04)
    }

    private func heart(_ cg: CGContext, _ x: CGFloat, _ y: CGFloat, _ size: CGFloat) {
        let k = size / 24
        func pt(_ px: CGFloat, _ py: CGFloat) -> CGPoint { CGPoint(x: x + px * k, y: y + py * k) }
        cg.move(to: pt(12, 21))
        cg.addCurve(to: pt(2.4, 11.7), control1: pt(12, 21), control2: pt(4.5, 16.4))
        cg.addCurve(to: pt(6.7, 4.5), control1: pt(0.9, 8.2), control2: pt(3.1, 4.5))
        cg.addCurve(to: pt(12, 7.6), control1: pt(8.9, 4.5), control2: pt(10.3, 5.7))
        cg.addCurve(to: pt(17.3, 4.5), control1: pt(13.7, 5.7), control2: pt(15.1, 4.5))
        cg.addCurve(to: pt(21.6, 11.7), control1: pt(20.9, 4.5), control2: pt(23.1, 8.2))
        cg.addCurve(to: pt(12, 21), control1: pt(19.5, 16.4), control2: pt(12, 21))
        cg.closePath()
        cg.setFillColor(Hud.red.cgColor)
        cg.fillPath()
    }

    // TechRadar.qml: rings every 0.5 g to 1.5 g, a crosshair, an amber dot and its last second.
    private func radar(_ cg: CGContext, _ b: Rect, _ m: HudMoment) {
        let side = min(b.width, b.height)
        let half = side / 2
        let cx = b.centerX, cy = b.centerY
        let field = half * 0.92
        let scale = field / Hud.maxG
        cg.setFillColor(UIColor(rgb: 0x08090B).withAlphaComponent(0.93).cgColor)
        circle(cg, cx, cy, half, fill: true)
        cg.setStrokeColor(Hud.white.withAlphaComponent(0.16).cgColor)
        cg.setLineWidth(max(1, side * 0.006))
        var g = Hud.ringStep
        while g <= Hud.maxG + 1e-6 {
            circle(cg, cx, cy, g * scale, fill: false)
            g += Hud.ringStep
        }
        cg.strokeLineSegments(between: [
            CGPoint(x: cx, y: cy - field), CGPoint(x: cx, y: cy + field),
            CGPoint(x: cx - field, y: cy), CGPoint(x: cx + field, y: cy),
        ])
        put("1.0g", semibold(max(7, side * 0.055)), Hud.white.withAlphaComponent(0.5), x: cx + scale * 0.72, baseline: cy - scale * 0.72)
        for dot in m.radar {
            let fade = CGFloat(dot.fade)
            let at = place(dot.g, cx, cy, scale)
            cg.setFillColor(Hud.amber.withAlphaComponent(fade * 0.5).cgColor)
            circle(cg, at.x, at.y, side * (0.01 + 0.02 * fade), fill: true)
        }
        guard let now = m.g else { return }
        let at = place(now, cx, cy, scale)
        cg.setFillColor(Hud.amber.cgColor)
        circle(cg, at.x, at.y, side * 0.04, fill: true)
        cg.setStrokeColor(UIColor(rgb: 0x1A1200).cgColor)
        cg.setLineWidth(max(1, side * 0.01))
        circle(cg, at.x, at.y, side * 0.04, fill: false)
    }

    /// Where the radar's dot goes, as Overlays puts it: braking up, speeding up down, and a turn to the
    /// left (acceleration to the left) to the right, the way the body is pushed.
    private func place(_ g: GForce, _ cx: CGFloat, _ cy: CGFloat, _ scale: CGFloat) -> CGPoint {
        let left = -g.lateral, forward = g.longitudinal
        let total = hypot(left, forward)
        let k = total > Double(Hud.maxG) ? Double(Hud.maxG) / total : 1
        return CGPoint(x: cx + CGFloat(left * k) * scale, y: cy + CGFloat(forward * k) * scale)
    }

    // TechGForce.qml: "G", the combined G, then lateral and longitudinal.
    private func gReadout(_ cg: CGContext, _ b: Rect, _ s: CGFloat, _ m: HudMoment) {
        panel(cg, b, s)
        let p = pad(s)
        let h = b.height
        textTop("G", semibold(h * 0.16), Hud.dim, x: b.left + p, top: b.top + h * 0.2, kern: h * 0.015)
        let big = bold(h * 0.36)
        textTop(m.gText, big, Hud.text, x: b.right - p - width(m.gText, big), top: b.top + h * 0.1)
        let bottom = b.bottom - h * 0.14
        let f = semibold(h * 0.15)
        var x = textBottom("LAT ", f, Hud.dim, x: b.left + p, bottom: bottom)
        x = textBottom(m.gLateralText, f, Hud.text, x: x, bottom: bottom)
        x = textBottom("LON ", f, Hud.dim, x: x + h * 0.2, bottom: bottom)
        textBottom(m.gLongitudinalText, f, Hud.text, x: x, bottom: bottom)
    }

    // TechSteeringWheel.qml: "STEER" and the angle ("90° L") over a wheel turned by it.
    private func steering(_ cg: CGContext, _ b: Rect, _ s: CGFloat, _ m: HudMoment) {
        panel(cg, b, s)
        let p = pad(s)
        let left = b.left + p, right = b.right - p
        let top = b.top + p * 1.2, bottom = b.bottom - p
        let rowH = (bottom - top) * 0.42
        textTop("STEER", semibold(rowH * 0.3), Hud.dim, x: left, top: top, kern: rowH * 0.03)
        let f = bold(rowH * 0.34)
        textTop(m.steeringLabel, f, Hud.text, x: right - width(m.steeringLabel, f), top: top)

        let stageTop = top + rowH * 0.5
        let size = max(0, min(right - left, bottom - stageTop))
        let cx = (left + right) / 2, cy = (stageTop + bottom) / 2
        cg.saveGState()
        cg.translateBy(x: cx, y: cy)
        cg.rotate(by: -radians(CGFloat(m.steering?.doubleValue ?? 0)))
        let half = size / 2
        let rim = max(2, size * 0.07)
        cg.setStrokeColor(Hud.text.cgColor)
        cg.setLineWidth(rim)
        circle(cg, 0, 0, half - rim / 2, fill: false)
        cg.setFillColor(Hud.text.cgColor)
        let spoke = size * 0.07
        cg.fill(CGRect(x: -size * 0.45, y: -spoke / 2, width: size * 0.9, height: spoke))
        cg.fill(CGRect(x: -spoke / 2, y: 0, width: spoke, height: size * 0.4))
        circle(cg, 0, 0, size * 0.1, fill: true)
        cg.setFillColor(Hud.amber.cgColor)
        cg.fill(CGRect(x: -size * 0.045, y: -half, width: size * 0.09, height: size * 0.16))
        cg.restoreGState()
    }

    // TechTrackMap.qml + TrackMapCore.qml: "TRACK" and the lap, the whole track, the lap's trail, the car.
    private func map(_ cg: CGContext, _ b: Rect, _ s: CGFloat, _ m: HudMoment) {
        panel(cg, b, s)
        let p = pad(s)
        let h = b.height
        let cut = min(18 * s, min(b.width / 3, h / 2))
        let title = semibold(h * 0.055)
        let titleTop = b.top + p * 1.2
        textTop("TRACK", title, Hud.dim, x: b.left + p, top: titleTop, kern: h * 0.006)
        if let lap = m.mapLap {
            let f = bold(h * 0.065)
            textTop(lap, f, Hud.text, x: b.right - p - cut * 0.6 - width(lap, f), top: titleTop)
        }
        let area = Rect(left: b.left + p, top: titleTop + lineHeight(title) + p * 0.4, right: b.right - p, bottom: b.bottom - p)
        guard let view = MapView(outline, area) else { return }
        let side = max(1, min(area.width, area.height))
        let lw = max(2, side * 0.014)
        cg.setLineCap(.round)
        cg.setLineJoin(.round)
        let track = view.path([outline])
        cg.setLineWidth(lw + 3)
        cg.setStrokeColor(UIColor.black.withAlphaComponent(0.5).cgColor)
        cg.addPath(track)
        cg.strokePath()
        cg.setLineWidth(lw)
        cg.setStrokeColor(Hud.mapLine.cgColor)
        cg.addPath(track)
        cg.strokePath()

        startLine(cg, view, side)
        if m.trail.contains(where: { $0.count > 1 }) {
            cg.setLineWidth(lw)
            cg.setStrokeColor(Hud.amber.cgColor)
            cg.addPath(view.path(m.trail))
            cg.strokePath()
        }
        if let position = m.position { car(cg, view.point(position), side) }
    }

    /// The start/finish line: a short dashed tick across the track.
    private func startLine(_ cg: CGContext, _ view: MapView, _ side: CGFloat) {
        guard let a = session.startLineFrom, let z = session.startLineTo else { return }
        let pa = view.point(a), pz = view.point(z)
        let length = hypot(pz.x - pa.x, pz.y - pa.y)
        guard length > 0 else { return }
        let reach = side * 0.03
        let mx = (pa.x + pz.x) / 2, my = (pa.y + pz.y) / 2
        let ux = (pz.x - pa.x) / length * reach, uy = (pz.y - pa.y) / length * reach
        cg.saveGState()
        cg.setLineDash(phase: 0, lengths: [3, 3])
        cg.setLineWidth(max(2, side * 0.01))
        cg.setLineCap(.butt)
        cg.setStrokeColor(Hud.text.cgColor)
        cg.strokeLineSegments(between: [CGPoint(x: mx - ux, y: my - uy), CGPoint(x: mx + ux, y: my + uy)])
        cg.restoreGState()
    }

    /// The car: an amber dot with a dark rim in a faint halo.
    private func car(_ cg: CGContext, _ at: CGPoint, _ side: CGFloat) {
        let dot = max(4, side * 0.03)
        cg.setFillColor(Hud.amber.withAlphaComponent(0.25).cgColor)
        circle(cg, at.x, at.y, dot * 1.6, fill: true)
        cg.setFillColor(Hud.amber.cgColor)
        circle(cg, at.x, at.y, dot, fill: true)
        cg.setStrokeColor(Hud.plate.withAlphaComponent(1).cgColor)
        cg.setLineWidth(max(1.5, dot * 0.3))
        circle(cg, at.x, at.y, dot, fill: false)
    }

    private func circle(_ cg: CGContext, _ x: CGFloat, _ y: CGFloat, _ r: CGFloat, fill: Bool) {
        let rect = CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)
        if fill { cg.fillEllipse(in: rect) } else { cg.strokeEllipse(in: rect) }
    }

    // Text placed as Android's painter places it: by the top, the middle or the bottom of its line box.

    private func semibold(_ size: CGFloat) -> UIFont { Hud.font("ChakraPetch-SemiBold", size, .semibold) }

    private func bold(_ size: CGFloat) -> UIFont { Hud.font("ChakraPetch-Bold", size, .bold) }

    private func lineHeight(_ f: UIFont) -> CGFloat { f.ascender - f.descender }

    private func middle(_ f: UIFont, _ centerY: CGFloat) -> CGFloat { centerY + (f.ascender + f.descender) / 2 }

    private func width(_ s: String, _ f: UIFont, _ kern: CGFloat = 0) -> CGFloat {
        (s as NSString).size(withAttributes: [.font: f, .kern: kern]).width
    }

    private func put(_ s: String, _ f: UIFont, _ color: UIColor, x: CGFloat, baseline: CGFloat, kern: CGFloat = 0) {
        (s as NSString).draw(at: CGPoint(x: x, y: baseline - f.ascender), withAttributes: [.font: f, .foregroundColor: color, .kern: kern])
    }

    private func textTop(_ s: String, _ f: UIFont, _ color: UIColor, x: CGFloat, top: CGFloat, kern: CGFloat = 0) {
        put(s, f, color, x: x, baseline: top + f.ascender, kern: kern)
    }

    private func textMiddle(_ s: String, _ f: UIFont, _ color: UIColor, x: CGFloat, centerY: CGFloat, kern: CGFloat = 0) {
        put(s, f, color, x: x, baseline: middle(f, centerY), kern: kern)
    }

    /// Returns where the next run starts.
    @discardableResult
    private func textBottom(_ s: String, _ f: UIFont, _ color: UIColor, x: CGFloat, bottom: CGFloat) -> CGFloat {
        put(s, f, color, x: x, baseline: bottom + f.descender)
        return x + width(s, f)
    }

    private func centred(_ s: String, _ f: UIFont, _ color: UIColor, _ cx: CGFloat, _ cy: CGFloat) {
        textMiddle(s, f, color, x: cx - width(s, f) / 2, centerY: cy)
    }

    private func radians(_ degrees: CGFloat) -> CGFloat { degrees * .pi / 180 }

    /// [a] tinted towards [b] by [t], 0 to 1, as Qt.tint with [b] at alpha [t].
    private func blend(_ a: UIColor, _ b: UIColor, _ t: CGFloat) -> UIColor {
        var ar: CGFloat = 0, ag: CGFloat = 0, ab: CGFloat = 0, aa: CGFloat = 0
        var br: CGFloat = 0, bg: CGFloat = 0, bb: CGFloat = 0, ba: CGFloat = 0
        a.getRed(&ar, green: &ag, blue: &ab, alpha: &aa)
        b.getRed(&br, green: &bg, blue: &bb, alpha: &ba)
        let k = min(1, max(0, t))
        return UIColor(red: ar + (br - ar) * k, green: ag + (bg - ag) * k, blue: ab + (bb - ab) * k, alpha: 1)
    }
}

/// A box in pixels, y down, from the shared layout's Box.
private struct Rect {
    let left: CGFloat, top: CGFloat, right: CGFloat, bottom: CGFloat

    init(left: CGFloat, top: CGFloat, right: CGFloat, bottom: CGFloat) {
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    init(_ b: Box) {
        self.init(left: CGFloat(b.left), top: CGFloat(b.top), right: CGFloat(b.right), bottom: CGFloat(b.bottom))
    }

    var width: CGFloat { right - left }
    var height: CGFloat { bottom - top }
    var centerX: CGFloat { (left + right) / 2 }
    var centerY: CGFloat { (top + bottom) / 2 }
}

/// The whole track fitted into a box, north up, at 94 % as Overlays fits it.
private struct MapView {
    let cx: CGFloat, cy: CGFloat, midX: Double, midY: Double, k: Double

    init?(_ outline: [Xy], _ area: Rect) {
        guard outline.count > 1, area.width > 0, area.height > 0 else { return nil }
        let xs = outline.map(\.x), ys = outline.map(\.y)
        guard let minX = xs.min(), let maxX = xs.max(), let minY = ys.min(), let maxY = ys.max() else { return nil }
        k = min(Double(area.width) / max(1, maxX - minX), Double(area.height) / max(1, maxY - minY)) * 0.94
        cx = area.centerX
        cy = area.centerY
        midX = (minX + maxX) / 2
        midY = (minY + maxY) / 2
    }

    func point(_ p: Xy) -> CGPoint { CGPoint(x: cx + CGFloat((p.x - midX) * k), y: cy - CGFloat((p.y - midY) * k)) }

    func path(_ runs: [[Xy]]) -> CGPath {
        let path = CGMutablePath()
        for run in runs where run.count > 1 {
            path.addLines(between: run.map(point))
        }
        return path
    }
}

/// The Tech style's colours and the template's gauge settings (Overlays' TechTheme and widget defaults).
private enum Hud {
    static let white = UIColor.white
    static let text = UIColor(rgb: 0xF4F4F4)
    static let dim = UIColor(rgb: 0xA3A6AD)
    static let amber = UIColor(rgb: 0xFCB203)
    static let green = UIColor(rgb: 0x4FD17A)
    static let red = UIColor(rgb: 0xFF4D3D)
    static let orange = UIColor(rgb: 0xFF9F1A)
    static let mapLine = UIColor(rgb: 0x4A545C)
    /// #0c0e11 at 86 %.
    static let plate = UIColor(rgb: 0x0C0E11).withAlphaComponent(CGFloat(0xDB) / 255)

    static let arcStart: CGFloat = 135
    static let arcSweep: CGFloat = 270
    static let rpmMax = 9000.0
    static let rpmWarning = 7500.0
    static let maxG: CGFloat = 1.5
    static let ringStep: CGFloat = 0.5
    static let tyreCold = 60.0
    static let tyreHot = 100.0
    /// The unit after a row's value, in Qt's "size 2" against the value's size.
    static let unitShare: CGFloat = 0.55

    /// Chakra Petch at [size] with tabular figures; the system font when it is not there.
    static func font(_ name: String, _ size: CGFloat, _ weight: UIFont.Weight) -> UIFont {
        let base = UIFont(name: name, size: max(1, size)) ?? .systemFont(ofSize: max(1, size), weight: weight)
        let tabular = base.fontDescriptor.addingAttributes([
            .featureSettings: [[UIFontDescriptor.FeatureKey.type: kNumberSpacingType,
                                UIFontDescriptor.FeatureKey.selector: kMonospacedNumbersSelector]],
        ])
        return UIFont(descriptor: tabular, size: max(1, size))
    }
}

private extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}
