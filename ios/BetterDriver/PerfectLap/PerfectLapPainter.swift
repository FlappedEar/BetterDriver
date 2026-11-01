import BetterDriverCore
import CoreGraphics
import UIKit

/// Draws the perfect lap in 3D at one moment (FBBD-308, FBBD-316), as Android's PerfectLapPainter:
/// the shared scene (SceneFrame) gives every shape in screen pixels, this colours them and writes the
/// corner, the speed, the lap clock and the best lap's gap over them. The player and the saved video
/// both draw with it. [tiles] gives the satellite photo under the road in the player (UI-201 B, as
/// MapTilerGround on Android); without it the road is drawn (UI-201 A). [waitForTiles] reads or fetches
/// each tile before drawing it; the player draws the tiles at hand.
final class PerfectLapPainter {
    let scene: PerfectLapScene
    private let ground: SceneGround
    private let tiles: PerfectLapTiles?
    private let waitForTiles: Bool

    /// How far the camera is from the car, times the normal distance: pinched in the player.
    var distance = 1.0
    /// Satellite tiles in the last picture drawn: for the player's accessibility value and its UI test.
    private(set) var lastPhotos = 0

    init(scene: PerfectLapScene, tiles: PerfectLapTiles? = nil, waitForTiles: Bool = false) {
        self.scene = scene
        self.tiles = tiles
        self.waitForTiles = waitForTiles
        ground = SceneGround(scene: scene)
    }

    /// The picture at [seconds] in a [size] context with its origin top left. [controlsHeight] is how
    /// much of the bottom the player's bar covers (UI-204 A): the lap clock is then left to the bar.
    func draw(_ cg: CGContext, seconds: Double, size: CGSize, controlsHeight: CGFloat = 0) {
        let width = Int32(size.width.rounded()), height = Int32(size.height.rounded())
        guard width >= 1, height >= 1 else { return }
        cg.setFillColor(ScenePalette.ground)
        cg.fill(CGRect(origin: .zero, size: size))
        let frame = SceneFrameKt.sceneFrame(
            scene: scene, ground: ground, seconds: seconds, width: width, height: height, distance: distance
        )
        // The drawn road first, the photo over it where a tile is at hand, then the line and the cars.
        let shapes = frame.shapes
        for shape in shapes where Self.underMap(shape.paint) { fill(cg, shape) }
        let photos = photo(cg, frame.tiles)
        lastPhotos = photos
        for shape in shapes where !Self.underMap(shape.paint) { fill(cg, shape) }
        UIGraphicsPushContext(cg)
        defer { UIGraphicsPopContext() }
        for label in frame.labels { word(label) }
        hud(frame, seconds: seconds, size: size, clock: controlsHeight <= 0)
        if photos > 0, let tiles { credit(tiles.attribution, size: size, bottom: size.height - controlsHeight) }
    }

    /// The road and its edges go under the photo (the scene's ScenePaint.underMap).
    private static func underMap(_ paint: ScenePaint) -> Bool { paint.name == "Road" || paint.name == "RoadEdge" }

    /// The map tiles on the ground, each piece pinned at its four corners; how many were drawn.
    private func photo(_ cg: CGContext, _ pieces: [SceneTile]) -> Int {
        guard let tiles else { return 0 }
        var drawn = 0
        for t in pieces {
            let image = waitForTiles ? tiles.load(z: t.z, x: t.x, y: t.y) : tiles.tile(z: t.z, x: t.x, y: t.y)
            if let image, piece(cg, t, image) { drawn += 1 }
        }
        return drawn
    }

    /// Draws the part of [image] that [t] says on its four screen corners; false when they make no shape.
    /// Core Graphics has no perspective transform (Android maps the four corners at once), so the piece
    /// is cut into cells through the exact perspective map of its corners, finer where the ground runs
    /// away from the camera, until each cell is flat to within a pixel; each cell is drawn as two
    /// triangles with the affine map of their corners.
    private func piece(_ cg: CGContext, _ t: SceneTile, _ image: CGImage) -> Bool {
        let dst = (0 ..< 4).map { k in CGPoint(x: CGFloat(t.xs.get(index: Int32(k))), y: CGFloat(t.ys.get(index: Int32(k)))) }
        guard let map = Perspective(dst) else { return false }
        let w = CGFloat(image.width), h = CGFloat(image.height)
        // A point (s, t) of the piece, 0...1 across and down, in the tile image's pixels (rows going down).
        let texel = { (s: CGFloat, v: CGFloat) in
            CGPoint(x: (CGFloat(t.u0) + s * CGFloat(t.u1 - t.u0)) * w, y: (CGFloat(t.v0) + v * CGFloat(t.v1 - t.v0)) * h)
        }
        cg.interpolationQuality = .medium
        // The picture, a seam wider: cells outside it are neither cut finer nor drawn. A perspective map
        // keeps lines straight, so a cell lands exactly inside its four corners' box.
        let view = cg.boundingBoxOfClipPath.insetBy(dx: -2 * Self.seamPx, dy: -2 * Self.seamPx)
        var drew = false
        func cell(_ s0: CGFloat, _ t0: CGFloat, _ s1: CGFloat, _ t1: CGFloat, depth: Int) {
            let st = [(s0, t0), (s1, t0), (s1, t1), (s0, t1)]
            let corners = st.map { map.point($0.0, $0.1) }
            let xs = corners.map(\.x), ys = corners.map(\.y)
            let box = CGRect(x: xs.min()!, y: ys.min()!, width: xs.max()! - xs.min()!, height: ys.max()! - ys.min()!)
            if !box.intersects(view) { return }
            let mid = map.point((s0 + s1) / 2, (t0 + t1) / 2)
            // How far the true middle is from where the two triangles would put it.
            let flatA = CGPoint(x: (corners[0].x + corners[2].x) / 2, y: (corners[0].y + corners[2].y) / 2)
            let flatB = CGPoint(x: (corners[1].x + corners[3].x) / 2, y: (corners[1].y + corners[3].y) / 2)
            let error = max(hypot(mid.x - flatA.x, mid.y - flatA.y), hypot(mid.x - flatB.x, mid.y - flatB.y))
            if error > Self.flatPx, depth < Self.maxDepth {
                let sm = (s0 + s1) / 2, tm = (t0 + t1) / 2
                cell(s0, t0, sm, tm, depth: depth + 1)
                cell(sm, t0, s1, tm, depth: depth + 1)
                cell(s0, tm, sm, t1, depth: depth + 1)
                cell(sm, tm, s1, t1, depth: depth + 1)
                return
            }
            let src = st.map { texel($0.0, $0.1) }
            for tri in [[0, 1, 2], [0, 2, 3]] {
                guard let affine = Self.affine(from: tri.map { src[$0] }, to: tri.map { corners[$0] }) else { continue }
                cg.saveGState()
                cg.addPath(Self.widened(tri.map { corners[$0] }))
                cg.clip()
                cg.concatenate(affine)
                // The image's rows go down in tile pixels, as the context's do; Core Graphics draws an image bottom up.
                cg.translateBy(x: 0, y: h)
                cg.scaleBy(x: 1, y: -1)
                cg.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
                cg.restoreGState()
                drew = true
            }
        }
        cell(0, 0, 1, 1, depth: 0)
        return drew
    }

    /// A cell is drawn flat once its middle is within this of the true one.
    private static let flatPx: CGFloat = 0.75
    /// At most 4^6 cells a piece, for a piece seen almost edge on.
    private static let maxDepth = 6

    /// The perspective map of the unit square onto four screen corners (top left, top right, bottom right,
    /// bottom left), as Android's Matrix.setPolyToPoly with four points (Heckbert's square to quad).
    private struct Perspective {
        let a, b, c, d, e, f, g, h: CGFloat

        init?(_ p: [CGPoint]) {
            let dx1 = p[1].x - p[2].x, dx2 = p[3].x - p[2].x, dx3 = p[0].x - p[1].x + p[2].x - p[3].x
            let dy1 = p[1].y - p[2].y, dy2 = p[3].y - p[2].y, dy3 = p[0].y - p[1].y + p[2].y - p[3].y
            let det = dx1 * dy2 - dx2 * dy1
            guard abs(det) > 1e-9 else { return nil }
            let g = (dx3 * dy2 - dx2 * dy3) / det, h = (dx1 * dy3 - dx3 * dy1) / det
            self.g = g
            self.h = h
            a = p[1].x - p[0].x + g * p[1].x
            b = p[3].x - p[0].x + h * p[3].x
            c = p[0].x
            d = p[1].y - p[0].y + g * p[1].y
            e = p[3].y - p[0].y + h * p[3].y
            f = p[0].y
        }

        func point(_ s: CGFloat, _ t: CGFloat) -> CGPoint {
            let q = g * s + h * t + 1
            return CGPoint(x: (a * s + b * t + c) / q, y: (d * s + e * t + f) / q)
        }
    }

    /// The affine map taking the three points [s] to [d]; nil when [s] or [d] lie on a line.
    private static func affine(from s: [CGPoint], to d: [CGPoint]) -> CGAffineTransform? {
        let ux = s[1].x - s[0].x, uy = s[1].y - s[0].y, vx = s[2].x - s[0].x, vy = s[2].y - s[0].y
        let px = d[1].x - d[0].x, py = d[1].y - d[0].y, qx = d[2].x - d[0].x, qy = d[2].y - d[0].y
        let det = ux * vy - vx * uy
        guard abs(det) > 1e-9, abs(px * qy - qx * py) > 1e-6 else { return nil }
        let a = (px * vy - qx * uy) / det, c = (qx * ux - px * vx) / det
        let b = (py * vy - qy * uy) / det, dd = (qy * ux - py * vx) / det
        return CGAffineTransform(
            a: a, b: b, c: c, d: dd,
            tx: d[0].x - (a * s[0].x + c * s[0].y), ty: d[0].y - (b * s[0].x + dd * s[0].y)
        )
    }

    /// The triangle [p] a pixel wider than it is, so neighbours overlap: cut exactly, the edges of two
    /// pieces each half cover their pixels and the ground shows through as a seam (as on Android).
    private static func widened(_ p: [CGPoint]) -> CGPath {
        let cx = p.map(\.x).reduce(0, +) / CGFloat(p.count), cy = p.map(\.y).reduce(0, +) / CGFloat(p.count)
        let path = CGMutablePath()
        for (k, q) in p.enumerated() {
            let dx = q.x - cx, dy = q.y - cy
            let l = max(hypot(dx, dy), 1e-6)
            let at = CGPoint(x: q.x + dx / l * seamPx, y: q.y + dy / l * seamPx)
            if k == 0 { path.move(to: at) } else { path.addLine(to: at) }
        }
        path.closeSubpath()
        return path
    }

    private static let seamPx: CGFloat = 1

    /// The map's credit, bottom right above the player's bar, whenever Apple's photo is shown.
    private func credit(_ text: String, size: CGSize, bottom: CGFloat) {
        let u = size.height / 100
        Self.text(text, UIFont.systemFont(ofSize: 2.6 * u, weight: .bold), ScenePalette.text,
                  baseline: CGPoint(x: size.width - 2 * u, y: bottom - 2 * u), align: .right)
    }

    private func fill(_ cg: CGContext, _ s: SceneShape) {
        let n = Int(s.xs.size)
        guard n >= 3 else { return }
        cg.beginPath()
        cg.move(to: CGPoint(x: CGFloat(s.xs.get(index: 0)), y: CGFloat(s.ys.get(index: 0))))
        for i in 1 ..< n {
            cg.addLine(to: CGPoint(x: CGFloat(s.xs.get(index: Int32(i))), y: CGFloat(s.ys.get(index: Int32(i)))))
        }
        cg.closePath()
        cg.setFillColor(ScenePalette.colour(s.paint))
        cg.fillPath()
    }

    /// BRAKE and GAS beside their bars, white with a dark edge so they read on any ground.
    private func word(_ l: SceneLabel) {
        let font = UIFont.systemFont(ofSize: CGFloat(l.size), weight: .bold)
        let text = NSAttributedString(string: l.text, attributes: [
            .font: font,
            .foregroundColor: UIColor.white,
            .strokeColor: UIColor(cgColor: ScenePalette.panelOpaque),
            // A sixth of the text's height, as on Android; negative strokes and fills.
            .strokeWidth: -100.0 / 6,
        ])
        let w = text.size().width
        // The scene gives the text's baseline centre.
        text.draw(at: CGPoint(x: CGFloat(l.x) - w / 2, y: CGFloat(l.y) - font.ascender))
    }

    private func hud(_ frame: SceneFrame, seconds: Double, size: CGSize, clock: Bool) {
        let u = size.height / 100
        let pad = 3 * u
        let corner = scene.corners[Int(scene.cornerAt(seconds: seconds))]
        let session = corner.sessionLabel.isEmpty ? "this session" : corner.sessionLabel
        let cornerText = LineUiKt.cornerTag(name: corner.name)
        let from = "Target from \(session), lap \(corner.lapNumber)"
        // Corner and where its target is from, top left.
        let cornerFont = UIFont.systemFont(ofSize: 5 * u, weight: .bold)
        let fromFont = UIFont.systemFont(ofSize: 3 * u, weight: .bold)
        let cornerW = max(Self.width(cornerText, cornerFont), Self.width(from, fromFont))
        panel(CGRect(x: pad, y: pad, width: cornerW + 4 * u, height: 13 * u), radius: 2 * u)
        Self.text(cornerText, cornerFont, ScenePalette.text, baseline: CGPoint(x: pad + 2 * u, y: pad + 6.5 * u))
        Self.text(from, fromFont, ScenePalette.textDim, baseline: CGPoint(x: pad + 2 * u, y: pad + 11 * u))
        // The perfect lap's speed, top right.
        let speed = scene.perfectAt(seconds: seconds).speedKmh.map { "\(Int($0.doubleValue.rounded()))" } ?? "–"
        let speedFont = UIFont.systemFont(ofSize: 8 * u, weight: .bold)
        let speedW = max(Self.width(speed, speedFont), 12 * u)
        panel(CGRect(x: size.width - pad - speedW - 4 * u, y: pad, width: speedW + 4 * u, height: 13 * u), radius: 2 * u)
        Self.text(speed, speedFont, ScenePalette.text, baseline: CGPoint(x: size.width - pad - 2 * u, y: pad + 8.5 * u), align: .right)
        Self.text("km/h", UIFont.systemFont(ofSize: 2.6 * u, weight: .bold), ScenePalette.textDim,
                  baseline: CGPoint(x: size.width - pad - 2 * u, y: pad + 11.8 * u), align: .right)
        // The lap clock, bottom left, unless the player's bar shows it.
        if clock {
            let text = "\(LineUiKt.lineLapTime(seconds: seconds)) / \(LineUiKt.lineLapTime(seconds: scene.perfectSeconds))"
            Self.text(text, UIFont.systemFont(ofSize: 3.4 * u, weight: .bold), ScenePalette.text,
                      baseline: CGPoint(x: pad, y: size.height - pad))
        }
        // The best lap, labelled beside its car.
        if let at = frame.ghostLabel, let x = at.first, let y = at.second {
            Self.text(Self.ghostText(scene.gapAt(seconds: seconds)), UIFont.systemFont(ofSize: 3.2 * u, weight: .bold),
                      ScenePalette.text, baseline: CGPoint(x: CGFloat(x.floatValue), y: CGFloat(y.floatValue) - u), align: .center)
        }
    }

    /// Gap of the best lap behind the perfect lap's car, as the label beside the ghost says it.
    static func ghostText(_ gap: Double) -> String {
        let s = String(format: "%.2f", abs(gap))
        return gap >= 0 ? "Your best lap +\(s) s" : "Your best lap −\(s) s"
    }

    private func panel(_ rect: CGRect, radius: CGFloat) {
        UIColor(cgColor: ScenePalette.panel).setFill()
        UIBezierPath(roundedRect: rect, cornerRadius: radius).fill()
    }

    private static func width(_ s: String, _ font: UIFont) -> CGFloat {
        (s as NSString).size(withAttributes: [.font: font]).width
    }

    private enum Align { case left, center, right }

    private static func text(_ s: String, _ font: UIFont, _ colour: CGColor, baseline: CGPoint, align: Align = .left) {
        let w = width(s, font)
        let x: CGFloat = switch align {
        case .left: baseline.x
        case .center: baseline.x - w / 2
        case .right: baseline.x - w
        }
        (s as NSString).draw(
            at: CGPoint(x: x, y: baseline.y - font.ascender),
            withAttributes: [.font: font, .foregroundColor: UIColor(cgColor: colour)]
        )
    }
}

/// The colours of the perfect lap in 3D, as Android's: the Map tab's pedal colours, the app's accent
/// for the car, and the drawn track where there is no photo (UI-201 A).
enum ScenePalette {
    static let ground = rgb(0x2F5E36)
    static let road = rgb(0x3B3F43)
    static let edge = rgb(0xF2F4F5)
    static let panel = rgb(0x050709, alpha: 0xC8)
    static let panelOpaque = rgb(0x050709)
    static let braking = rgb(0xFF6863)
    static let coasting = rgb(0x96A5B0)
    static let accelerating = rgb(0x22D37A)
    static let car = rgb(0xFCB203)
    /// The car colour at 35 %, its sides (CarDark).
    static let carDark = rgb(0x583E01)
    static let text = rgb(0xF2F5F7)
    static let textDim = rgb(0x96A5B0)

    /// A shape's colour, by its paint's name in the shared scene.
    static func colour(_ paint: ScenePaint) -> CGColor {
        switch paint.name {
        case "Road": road
        case "RoadEdge": edge
        case "LineEdge": rgb(0x050709, alpha: 150)
        case "Braking", "BrakeBar": braking
        case "Coasting", "NoPedal": coasting
        case "Accelerating", "GasBar": accelerating
        case "CarHalo": car.copy(alpha: 70 / 255) ?? car
        case "Car": car
        case "CarDark": carDark
        case "GhostHalo": text.copy(alpha: 70 / 255) ?? text
        case "Ghost": text.copy(alpha: 110 / 255) ?? text
        default: coasting
        }
    }

    private static func rgb(_ hex: UInt32, alpha: UInt32 = 0xFF) -> CGColor {
        CGColor(
            srgbRed: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: CGFloat(alpha) / 255
        )
    }
}
