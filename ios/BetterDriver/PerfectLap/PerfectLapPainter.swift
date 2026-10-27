import BetterDriverCore
import CoreGraphics
import UIKit

/// Draws the perfect lap in 3D at one moment (FBBD-308, FBBD-316), as Android's PerfectLapPainter:
/// the shared scene (SceneFrame) gives every shape in screen pixels, this colours them and writes the
/// corner, the speed, the lap clock and the best lap's gap over them. The player and the saved video
/// both draw with it, so the video is what the player showed. Without a map photo on the iPhone the
/// road is drawn (UI-201 A).
final class PerfectLapPainter {
    let scene: PerfectLapScene
    private let ground: SceneGround

    /// How far the camera is from the car, times the normal distance: pinched in the player.
    var distance = 1.0

    init(scene: PerfectLapScene) {
        self.scene = scene
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
        for shape in frame.shapes { fill(cg, shape) }
        UIGraphicsPushContext(cg)
        defer { UIGraphicsPopContext() }
        for label in frame.labels { word(label) }
        hud(frame, seconds: seconds, size: size, clock: controlsHeight <= 0)
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
