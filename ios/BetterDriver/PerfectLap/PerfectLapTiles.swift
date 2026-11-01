import BetterDriverCore
import Combine
import MapKit
import UIKit

/// Apple's satellite photo under the perfect lap in 3D (FBBD-316, UI-201 B), in place of Android's
/// MapTilerGround: every map on iOS is Apple's, so no key is needed. The scene asks for Web Mercator
/// tiles; MapKit's map points are the same projection, so each block of 4 × 4 tiles is one
/// MKMapSnapshotter picture cut into its tiles.
///
/// Apple's terms let map data be kept only for a while, to make the app faster, so the tiles live in
/// memory while the player or the video is open, never on disk. Without signal a snapshot fails, no
/// tile is drawn and the road is drawn instead (UI-201 A), as on Android without a key.
final class PerfectLapTiles: ObservableObject {
    /// Shown on the picture whenever a tile is: the snapshots' own logo is cut off with their edges.
    let attribution = "Apple Maps"
    /// Goes up each time a block of tiles arrives, so the paused picture is drawn again. Main queue.
    @Published private(set) var loaded = 0

    private let images = NSCache<NSString, CGImage>()
    private let lock = NSLock()
    private var pending = Set<String>()
    /// Blocks that did not come, and until when they are not asked for again; all of them after two
    /// failures in a row (no signal), so neither the player nor the video waits on them every frame.
    private var failedUntil: [String: Date] = [:]
    private var failuresInARow = 0
    private var offlineUntil = Date.distantPast
    /// At most two snapshots at once.
    private let slots = DispatchSemaphore(value: 2)

    private static let retryAfter: TimeInterval = 600
    /// Tiles a block has across and down.
    private static let block: Int32 = 4
    /// A tile's size in points; snapshots are taken at twice that, so a tile is 512 pixels as on Android.
    private static let tilePoints: CGFloat = 256
    private static let scale: CGFloat = 2
    /// Map round each block, cut off afterwards: Apple's logo and Legal link sit at a snapshot's edge.
    private static let marginPoints: CGFloat = 64

    static func make() -> PerfectLapTiles? { PerfectLapTiles() }

    private init() {
        // About 128 decoded tiles of 1 MB: the ones within reach of the camera.
        images.totalCostLimit = 128 << 20
    }

    /// The tile if it is at hand; otherwise nil, and its block is fetched for a later frame.
    func tile(z: Int32, x: Int32, y: Int32) -> CGImage? {
        if let image = images.object(forKey: Self.key(z, x, y) as NSString) { return image }
        let block = Self.blockKey(z, x, y)
        lock.lock()
        let start = pending.insert(block).inserted
        lock.unlock()
        if start {
            DispatchQueue.global(qos: .userInitiated).async {
                let got = self.load(z: z, x: x, y: y) != nil
                self.lock.lock()
                self.pending.remove(block)
                self.lock.unlock()
                if got { DispatchQueue.main.async { self.loaded += 1 } }
            }
        }
        return nil
    }

    /// The tile, fetched with its block first if needed; nil without one, or when its block did not come
    /// in the last ten minutes. Waits, so never on the main queue (nil there): for the video, where Stop
    /// also ends the wait.
    func load(z: Int32, x: Int32, y: Int32) -> CGImage? {
        let key = Self.key(z, x, y)
        if let image = images.object(forKey: key as NSString) { return image }
        guard !Thread.isMainThread, z >= 0, z <= 22 else { return nil }
        let block = Self.blockKey(z, x, y)
        let now = Date()
        lock.lock()
        let skip = offlineUntil > now || (failedUntil[block].map { $0 > now } ?? false)
        lock.unlock()
        if skip { return nil }
        let bx = x >= 0 ? x / Self.block : -1, by = y >= 0 ? y / Self.block : -1
        let ok = fetch(z: z, bx: bx, by: by)
        if !Task.isCancelled {
            lock.lock()
            if ok {
                failuresInARow = 0
            } else {
                failedUntil[block] = now.addingTimeInterval(Self.retryAfter)
                failuresInARow += 1
                if failuresInARow >= 2 { offlineUntil = now.addingTimeInterval(Self.retryAfter) }
            }
            lock.unlock()
        }
        return images.object(forKey: key as NSString)
    }

    /// Takes the snapshot of block ([bx], [by]) at zoom [z] and keeps its tiles; false when none came.
    private func fetch(z: Int32, bx: Int32, by: Int32) -> Bool {
        let tiles = Int32(1) << z
        guard bx >= 0, by >= 0, bx * Self.block < tiles, by * Self.block < tiles else { return false }
        let tileMap = MKMapSize.world.width / Double(tiles)
        let margin = Double(Self.marginPoints) / Double(Self.tilePoints) * tileMap
        let side = Double(Self.block) * tileMap
        let options = MKMapSnapshotter.Options()
        options.mapRect = MKMapRect(
            x: Double(bx) * side - margin, y: Double(by) * side - margin,
            width: side + 2 * margin, height: side + 2 * margin
        )
        let points = CGFloat(Self.block) * Self.tilePoints + 2 * Self.marginPoints
        options.size = CGSize(width: points, height: points)
        options.scale = Self.scale
        options.preferredConfiguration = MKImageryMapConfiguration()

        slots.wait()
        defer { slots.signal() }
        let snapshotter = MKMapSnapshotter(options: options)
        var got: MKMapSnapshotter.Snapshot?
        let done = DispatchSemaphore(value: 0)
        DispatchQueue.main.async {
            snapshotter.start(with: .global(qos: .userInitiated)) { snapshot, _ in
                got = snapshot
                done.signal()
            }
        }
        // Waits in short steps, so Stop on the video ends the wait.
        let deadline = Date().addingTimeInterval(15)
        while done.wait(timeout: .now() + 0.2) == .timedOut {
            if Task.isCancelled || Date() > deadline {
                DispatchQueue.main.async { snapshotter.cancel() }
                return false
            }
        }
        guard let snapshot = got, let picture = snapshot.image.cgImage else { return false }
        var kept = 0
        for dy in 0 ..< Self.block {
            for dx in 0 ..< Self.block {
                let x = bx * Self.block + dx, y = by * Self.block + dy
                // The tile's corners where the snapshot put them, so the cut follows MapKit exactly.
                let nw = snapshot.point(for: MKMapPoint(x: Double(x) * tileMap, y: Double(y) * tileMap).coordinate)
                let se = snapshot.point(for: MKMapPoint(x: Double(x + 1) * tileMap, y: Double(y + 1) * tileMap).coordinate)
                let s = snapshot.image.scale
                let cut = CGRect(x: nw.x * s, y: nw.y * s, width: (se.x - nw.x) * s, height: (se.y - nw.y) * s).integral
                guard cut.width > 1, cut.height > 1, let tile = Self.copy(picture, cut) else { continue }
                images.setObject(tile, forKey: Self.key(z, x, y) as NSString, cost: tile.bytesPerRow * tile.height)
                kept += 1
            }
        }
        return kept > 0
    }

    /// The part [cut] of [picture] as an image of its own, so a tile does not keep the whole snapshot.
    private static func copy(_ picture: CGImage, _ cut: CGRect) -> CGImage? {
        guard let part = picture.cropping(to: cut) else { return nil }
        let size = Int(scale * tilePoints)
        guard let ctx = CGContext(
            data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(part, in: CGRect(x: 0, y: 0, width: size, height: size))
        return ctx.makeImage()
    }

    private static func key(_ z: Int32, _ x: Int32, _ y: Int32) -> String { "\(z)/\(x)/\(y)" }

    private static func blockKey(_ z: Int32, _ x: Int32, _ y: Int32) -> String {
        "\(z)/\(x >= 0 ? x / block : -1)/\(y >= 0 ? y / block : -1)"
    }
}
