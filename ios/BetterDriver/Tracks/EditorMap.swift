import BetterDriverCore
import MapKit
import SwiftUI

/// Colours from the approved mockups (UI-23 to UI-26), as EditorColors on Android.
enum EditorColors {
    static let accent = UIColor(red: 0x4F / 255, green: 0xB8 / 255, blue: 0xD6 / 255, alpha: 1)
    static let line = UIColor(red: 0xF2 / 255, green: 0xF5 / 255, blue: 0xF7 / 255, alpha: 1)
    static let pit = UIColor(red: 0xC9 / 255, green: 0xD3 / 255, blue: 0xDA / 255, alpha: 1)
    static let halo = UIColor(red: 0x0B / 255, green: 0x0F / 255, blue: 0x12 / 255, alpha: 1)
    /// The recorded lap: a colour no satellite photo or street map has.
    static let lap = UIColor(red: 1, green: 0x3D / 255, blue: 0xD8 / 255, alpha: 1)
    /// The track's corners (amber) and straights (faint), FBBD-179.
    static let corner = UIColor(red: 0xF2 / 255, green: 0xB8 / 255, blue: 0x4B / 255, alpha: 1)
    static let straight = UIColor(red: 0xC9 / 255, green: 0xD3 / 255, blue: 0xDA / 255, alpha: 0x99 / 255)
}

/// The editor's map (UI-23, UI-24), as EditorMap on Android: the background tiles, the recorded lap,
/// every line with its direction arrow, and the selected line with its two draggable ends; with no
/// line selected, the track's corners and straights, the one being corrected with its draggable ends
/// (FBBD-366). A tap places the selected line, else picks the corner there; pinch and drag move the map.
struct EditorMap: UIViewRepresentable {
    @ObservedObject var model: EditorModel
    let source: TileSource

    func makeCoordinator() -> Coordinator { Coordinator(model) }

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.isRotateEnabled = false
        map.isPitchEnabled = false
        map.showsCompass = false
        map.pointOfInterestFilter = .excludingAll
        let drawing = EditorDrawing(map: map, model: model)
        drawing.frame = map.bounds
        drawing.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        map.addSubview(drawing)
        context.coordinator.drawing = drawing
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        map.addGestureRecognizer(tap)
        let drag = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.dragged(_:)))
        drag.delegate = context.coordinator
        map.addGestureRecognizer(drag)
        context.coordinator.show(source, on: map)
        context.coordinator.fit(map)
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        context.coordinator.show(source, on: map)
        context.coordinator.drawing?.setNeedsDisplay()
    }

    @MainActor
    final class Coordinator: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
        let model: EditorModel
        weak var drawing: EditorDrawing?
        private var overlay: CachedTileOverlay?
        /// The end being dragged: true for the left one.
        private var draggingLeft: Bool?
        /// The end of the corner being corrected that is dragged: true for its start.
        private var draggingStart: Bool?

        init(_ model: EditorModel) { self.model = model }

        func show(_ source: TileSource, on map: MKMapView) {
            // By address as well as id: a changed MapTiler key keeps the id but needs the new key.
            if let current = overlay?.source, current.id == source.id,
               current.url(z: 0, x: 0, y: 0) == source.url(z: 0, x: 0, y: 0) { return }
            if let old = overlay { map.removeOverlay(old) }
            let next = CachedTileOverlay(source: source)
            map.addOverlay(next, level: .aboveLabels)
            overlay = next
        }

        /// Fits the map to the recorded lap, else the start/finish line, else the track's area.
        func fit(_ map: MKMapView) {
            let editor = model.editor
            var points = editor.pathCoordinates
            if points.isEmpty, let sf = editor.track.startFinish {
                points = [sf.left.coordinate, sf.right.coordinate]
            }
            if points.count >= 2 {
                var rect = MKMapRect.null
                for p in points {
                    rect = rect.union(MKMapRect(origin: MKMapPoint(p), size: MKMapSize(width: 1, height: 1)))
                }
                map.setVisibleMapRect(rect, edgePadding: UIEdgeInsets(top: 48, left: 48, bottom: 48, right: 48), animated: false)
            } else {
                let c = editor.track.center.coordinate
                map.setRegion(MKCoordinateRegion(center: c, latitudinalMeters: 800, longitudinalMeters: 800), animated: false)
            }
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let tiles = overlay as? MKTileOverlay { return MKTileOverlayRenderer(tileOverlay: tiles) }
            return MKOverlayRenderer(overlay: overlay)
        }

        func mapViewDidChangeVisibleRegion(_ mapView: MKMapView) { drawing?.setNeedsDisplay() }

        @objc func tapped(_ tap: UITapGestureRecognizer) {
            guard let map = tap.view as? MKMapView else { return }
            let c = map.convert(tap.location(in: map), toCoordinateFrom: map)
            model.act { $0.onTap(lat: c.latitude, lon: c.longitude) }
        }

        /// The end of the selected line under [point], if one is within reach of a finger.
        private func end(at point: CGPoint, in map: MKMapView) -> Bool? {
            guard let line = model.editor.selectedLine else { return nil }
            let left = map.convert(line.left.coordinate, toPointTo: map)
            let right = map.convert(line.right.coordinate, toPointTo: map)
            let dl = hypot(left.x - point.x, left.y - point.y)
            let dr = hypot(right.x - point.x, right.y - point.y)
            let reach: CGFloat = 32
            if min(dl, dr) > reach { return nil }
            return dl <= dr
        }

        /// The handle of the corner being corrected under [point], if one is within reach: true for its start.
        private func handle(at point: CGPoint, in map: MKMapView) -> Bool? {
            let editor = model.editor
            guard editor.selected == nil, editor.corners.correctable, !editor.corners.correcting else { return nil }
            let reach: CGFloat = 32
            return editor.corners.handles()
                .map { ($0.start, map.convert($0.point.coordinate, toPointTo: map)) }
                .map { ($0.0, hypot($0.1.x - point.x, $0.1.y - point.y)) }
                .filter { $0.1 <= reach }
                .min { $0.1 < $1.1 }?.0
        }

        func gestureRecognizerShouldBegin(_ gesture: UIGestureRecognizer) -> Bool {
            guard let map = gesture.view as? MKMapView else { return false }
            let at = gesture.location(in: map)
            return end(at: at, in: map) != nil || handle(at: at, in: map) != nil
        }

        @objc func dragged(_ drag: UIPanGestureRecognizer) {
            guard let map = drag.view as? MKMapView else { return }
            switch drag.state {
            case .began:
                let at = drag.location(in: map)
                draggingLeft = end(at: at, in: map)
                draggingStart = draggingLeft == nil ? handle(at: at, in: map) : nil
                map.isScrollEnabled = false
            case .changed:
                let c = map.convert(drag.location(in: map), toCoordinateFrom: map)
                if let left = draggingLeft {
                    model.act { $0.moveEnd(left: left, lat: c.latitude, lon: c.longitude) }
                } else if let start = draggingStart {
                    let to = GeoPoint(lat: c.latitude, lon: c.longitude)
                    model.act { $0.corners.drag(start: start, to: to) }
                }
            default:
                if draggingStart != nil {
                    var edit: SegmentEdit?
                    model.act { edit = $0.corners.endDrag() }
                    model.store(edit)
                }
                draggingLeft = nil
                draggingStart = nil
                map.isScrollEnabled = true
            }
        }
    }
}

/// Draws the lap and the lines over the map, projected with the map's own conversion.
final class EditorDrawing: UIView {
    private weak var map: MKMapView?
    private let model: EditorModel

    init(map: MKMapView, model: EditorModel) {
        self.map = map
        self.model = model
        super.init(frame: .zero)
        isOpaque = false
        isUserInteractionEnabled = false
        contentMode = .redraw
    }

    required init?(coder: NSCoder) { nil }

    override func draw(_ rect: CGRect) {
        guard let map, let ctx = UIGraphicsGetCurrentContext() else { return }
        let editor = model.editor
        func project(_ p: GeoPoint) -> CGPoint { map.convert(p.coordinate, toPointTo: self) }
        drawLap(ctx, editor.pathCoordinates.map { map.convert($0, toPointTo: self) }, highlight: editor.selected != nil)
        if editor.selected == nil { drawCorners(editor.corners, project) }
        for item in editor.lines() {
            guard let line = item.line else { continue }
            drawLine(ctx, project(line.left), project(line.right), item)
        }
        if let first = editor.firstTap {
            let p = project(first)
            ctx.setFillColor(EditorColors.accent.cgColor)
            ctx.fillEllipse(in: CGRect(x: p.x - 5, y: p.y - 5, width: 10, height: 10))
        }
    }

    /// The corners (amber) and straights (faint) along the centreline, numbered at each corner; the one
    /// being corrected bold, with its ends as round handles (Android's IdentityLayer).
    private func drawCorners(_ corners: CornerCorrector, _ project: (GeoPoint) -> CGPoint) {
        let strokes = corners.strokes()
        let anySelected = strokes.contains { $0.selected && corners.correctable }
        for s in strokes {
            let points = s.points.map(project)
            if s.selected && corners.correctable {
                stroke(points, EditorColors.halo, width: 8)
                stroke(points, EditorColors.accent, width: 5)
            } else if s.corner {
                stroke(points, EditorColors.corner.withAlphaComponent(anySelected ? 0.55 : 0.9), width: 3)
            } else {
                stroke(points, EditorColors.straight, width: 1.5)
            }
        }
        if corners.correctable {
            for h in corners.handles() {
                let p = project(h.point)
                for (r, color) in [(CGFloat(9), EditorColors.halo), (7, EditorColors.accent), (3, EditorColors.halo)] {
                    color.setFill()
                    UIBezierPath(ovalIn: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r)).fill()
                }
            }
        }
        let shadow = NSShadow()
        shadow.shadowColor = EditorColors.halo
        shadow.shadowBlurRadius = 4
        for s in strokes {
            guard let number = s.number, !s.points.isEmpty else { continue }
            let at = project(s.points[s.points.count / 2])
            let selected = s.selected && corners.correctable
            NSAttributedString(string: number, attributes: [
                .font: UIFont.systemFont(ofSize: 11, weight: .semibold),
                .foregroundColor: selected ? EditorColors.accent : EditorColors.corner,
                .shadow: shadow,
            ]).draw(at: CGPoint(x: at.x + 6, y: at.y - 16))
        }
    }

    private func stroke(_ points: [CGPoint], _ color: UIColor, width: CGFloat) {
        guard points.count >= 2 else { return }
        let path = UIBezierPath()
        path.move(to: points[0])
        var last = points[0]
        for p in points.dropFirst() where hypot(p.x - last.x, p.y - last.y) >= 2 {
            path.addLine(to: p)
            last = p
        }
        // The end exactly, however close to the last point drawn.
        path.addLine(to: points[points.count - 1])
        path.lineCapStyle = .round
        path.lineJoinStyle = .round
        path.lineWidth = width
        color.setStroke()
        path.stroke()
    }

    private func drawLap(_ ctx: CGContext, _ points: [CGPoint], highlight: Bool) {
        guard points.count >= 2 else { return }
        let path = UIBezierPath()
        var last: CGPoint?
        for p in points {
            if let prev = last {
                if hypot(p.x - prev.x, p.y - prev.y) < 2 { continue }
                path.addLine(to: p)
            } else {
                path.move(to: p)
            }
            last = p
        }
        path.lineCapStyle = .round
        path.lineJoinStyle = .round
        let width: CGFloat = highlight ? 4.5 : 3.5
        EditorColors.halo.withAlphaComponent(0.8).setStroke()
        path.lineWidth = width + 3
        path.stroke()
        EditorColors.lap.setStroke()
        path.lineWidth = width
        path.stroke()
    }

    private func drawLine(_ ctx: CGContext, _ a: CGPoint, _ b: CGPoint, _ item: EditorLine) {
        let color: UIColor = item.selected ? EditorColors.accent : item.pit ? EditorColors.pit : EditorColors.line
        let width: CGFloat = item.selected ? 3.2 : 2.4
        let segment = UIBezierPath()
        segment.move(to: a)
        segment.addLine(to: b)
        segment.lineCapStyle = .round
        EditorColors.halo.setStroke()
        segment.lineWidth = width + 2
        segment.stroke()
        color.setStroke()
        segment.lineWidth = width
        if item.pit { segment.setLineDash([8, 6], count: 2, phase: 0) }
        segment.stroke()
        // The way the car crosses: square to the line, towards the driver's front (left end on the left).
        let dx = b.x - a.x, dy = b.y - a.y
        let n = hypot(dx, dy)
        if n > 0 {
            let fx = dy / n, fy = -dx / n
            let mid = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
            let tip = CGPoint(x: mid.x + fx * 12, y: mid.y + fy * 12)
            let shaft = UIBezierPath()
            shaft.move(to: mid)
            shaft.addLine(to: tip)
            shaft.lineWidth = 1.6
            shaft.stroke()
            let head: CGFloat = 3
            let arrow = UIBezierPath()
            arrow.move(to: CGPoint(x: tip.x + fx * head, y: tip.y + fy * head))
            arrow.addLine(to: CGPoint(x: tip.x - fy * head, y: tip.y + fx * head))
            arrow.addLine(to: CGPoint(x: tip.x + fy * head, y: tip.y - fx * head))
            arrow.close()
            color.setFill()
            arrow.fill()
        }
        if item.selected {
            for end in [a, b] {
                let r = CGRect(x: end.x - 6, y: end.y - 6, width: 12, height: 12)
                ctx.setFillColor(EditorColors.halo.cgColor)
                ctx.fillEllipse(in: r)
                ctx.setStrokeColor(EditorColors.accent.cgColor)
                ctx.setLineWidth(2)
                ctx.strokeEllipse(in: r)
            }
        }
        let shadow = NSShadow()
        shadow.shadowColor = EditorColors.halo
        shadow.shadowBlurRadius = 4
        let label = NSAttributedString(string: item.mapLabel, attributes: [
            .font: UIFont.systemFont(ofSize: 11, weight: .semibold),
            .foregroundColor: color,
            .shadow: shadow,
        ])
        label.draw(at: CGPoint(x: a.x + 8, y: a.y - 18))
    }
}

extension GeoPoint {
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: lat, longitude: lon) }
}

extension TrackEditor {
    /// The recorded lap as map coordinates.
    var pathCoordinates: [CLLocationCoordinate2D] {
        let flat = pathPoints.map(\.doubleValue)
        return stride(from: 0, to: flat.count - 1, by: 2).map {
            CLLocationCoordinate2D(latitude: flat[$0], longitude: flat[$0 + 1])
        }
    }
}
