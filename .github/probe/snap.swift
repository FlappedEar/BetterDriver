import AppKit
import MapKit

// Temporary probe: Apple's satellite picture over z/x0..x1/y0..y1 tiles, one PNG pixel-aligned with them.
let a = CommandLine.arguments.dropFirst().map { Int($0)! }
let (z, x0, y0, nx, ny) = (a[0], a[1], a[2], a[3], a[4])
let tile = MKMapSize.world.width / Double(1 << z)
let o = MKMapSnapshotter.Options()
o.mapRect = MKMapRect(x: Double(x0) * tile, y: Double(y0) * tile, width: Double(nx) * tile, height: Double(ny) * tile)
o.size = CGSize(width: nx * 256, height: ny * 256)
o.scale = 1
o.preferredConfiguration = MKImageryMapConfiguration()
var done = false
MKMapSnapshotter(options: o).start { s, e in
    if let s, let tiff = s.image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
       let png = rep.representation(using: .png, properties: [:]) {
        try? png.write(to: URL(fileURLWithPath: "apple.png"))
        print("apple ok \(rep.pixelsWide)x\(rep.pixelsHigh)")
    } else { print("apple fail \(String(describing: e))") }
    done = true
}
while !done { RunLoop.main.run(until: Date().addingTimeInterval(0.1)) }
