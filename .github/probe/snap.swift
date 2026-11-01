import AppKit
import MapKit

// Temporary probe: Apple's satellite picture of Silesia Ring, printed as base64 JPEG.
let center = CLLocationCoordinate2D(latitude: 50.5272, longitude: 18.0960)
func snap(_ meters: Double, _ px: CGFloat, _ name: String) {
    let o = MKMapSnapshotter.Options()
    o.region = MKCoordinateRegion(center: center, latitudinalMeters: meters, longitudinalMeters: meters)
    o.size = CGSize(width: px, height: px)
    o.preferredConfiguration = MKImageryMapConfiguration()
    var done = false
    MKMapSnapshotter(options: o).start { s, e in
        if let s, let tiff = s.image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
           let jpg = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.7]) {
            try? jpg.write(to: URL(fileURLWithPath: name))
            print("ok \(name) \(jpg.count)")
        } else { print("fail \(name) \(String(describing: e))") }
        done = true
    }
    while !done { RunLoop.main.run(until: Date().addingTimeInterval(0.1)) }
}
snap(1400, 800, "apple-wide.jpg")
snap(250, 800, "apple-close.jpg")
