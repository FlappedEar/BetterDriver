import BetterDriverCore
import Combine
import UIKit

/// MapTiler's satellite photo under the perfect lap in 3D (FBBD-316, UI-201 B), as Android's
/// MapTilerGround: the same tiles and key as the track editor, in the editor's folder on disk, so a
/// tile seen once is kept on the phone. Without a key there is none and the road is drawn (UI-201 A).
final class PerfectLapTiles: ObservableObject {
    /// Shown on the picture whenever a tile is, as MapTiler's terms ask.
    let attribution: String
    /// Goes up each time a tile arrives, so the paused picture is drawn again. Main queue.
    @Published private(set) var loaded = 0

    private let source: TileSource
    private let folder: URL
    private let session: URLSession
    private let images = NSCache<NSString, CGImage>()
    private let lock = NSLock()
    private var pending = Set<String>()
    /// Tiles that did not come, and until when they are not asked for again; all of them while offline.
    /// Ten minutes, as Android's tile loader, so neither the player nor the video waits on them every frame.
    private var failedUntil: [String: Date] = [:]
    private var offlineUntil = Date.distantPast
    private static let retryAfter: TimeInterval = 600

    /// The photo with the key entered in the track editor; nil without one.
    static func make() -> PerfectLapTiles? {
        MapTiles.mapTilerKey.map { PerfectLapTiles(source: TileSources.shared.mapTiler(key: $0)) }
    }

    private init(source: TileSource) {
        self.source = source
        attribution = source.attribution
        // The track editor's tile folder (CachedTileOverlay): the same files by source, zoom, x and y.
        folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("tiles/\(source.id)", isDirectory: true)
        let config = URLSessionConfiguration.default
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        config.httpAdditionalHeaders = [
            "User-Agent": "BetterDriver/\(version) iOS (+https://github.com/FlappedEar/BetterDriver)",
        ]
        session = URLSession(configuration: config)
        // 64 MB of decoded tiles, as Android's tile memory.
        images.totalCostLimit = 64 << 20
    }

    /// The tile if it is at hand; otherwise nil, and it is read or fetched for a later frame.
    func tile(z: Int32, x: Int32, y: Int32) -> CGImage? {
        let key = Self.key(z, x, y)
        if let image = images.object(forKey: key as NSString) { return image }
        lock.lock()
        let start = pending.insert(key).inserted
        lock.unlock()
        if start {
            DispatchQueue.global(qos: .userInitiated).async {
                let image = self.load(z: z, x: x, y: y)
                self.lock.lock()
                self.pending.remove(key)
                self.lock.unlock()
                if image != nil { DispatchQueue.main.async { self.loaded += 1 } }
            }
        }
        return nil
    }

    /// The tile, read from disk or fetched first if needed; nil without one, or when it did not come in the
    /// last ten minutes. Not on the main queue: for the video, where Stop also ends a fetch.
    func load(z: Int32, x: Int32, y: Int32) -> CGImage? {
        let key = Self.key(z, x, y)
        if let image = images.object(forKey: key as NSString) { return image }
        let file = folder.appendingPathComponent(key)
        let data = (try? Data(contentsOf: file)) ?? fetchUnlessFailed(key: key, z: z, x: x, y: y, to: file)
        guard let data, let image = UIImage(data: data)?.preparingForDisplay()?.cgImage else { return nil }
        images.setObject(image, forKey: key as NSString, cost: image.bytesPerRow * image.height)
        return image
    }

    private func fetchUnlessFailed(key: String, z: Int32, x: Int32, y: Int32, to file: URL) -> Data? {
        let now = Date()
        lock.lock()
        let skip = offlineUntil > now || (failedUntil[key].map { $0 > now } ?? false)
        lock.unlock()
        if skip { return nil }
        let (data, offline) = fetch(z: z, x: x, y: y, to: file)
        if data == nil, !Task.isCancelled {
            lock.lock()
            failedUntil[key] = now.addingTimeInterval(Self.retryAfter)
            if offline { offlineUntil = now.addingTimeInterval(Self.retryAfter) }
            lock.unlock()
        }
        return data
    }

    /// The tile's bytes, kept on disk; nil and whether the network itself failed (no signal, timed out).
    private func fetch(z: Int32, x: Int32, y: Int32, to file: URL) -> (Data?, Bool) {
        guard let url = URL(string: source.url(z: z, x: x, y: y)) else { return (nil, false) }
        var got: Data?
        var offline = false
        let done = DispatchSemaphore(value: 0)
        let task = session.dataTask(with: url) { data, response, error in
            let ok = (response as? HTTPURLResponse).map { (200 ..< 300).contains($0.statusCode) } ?? false
            if ok, let data {
                try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
                try? data.write(to: file, options: .atomic)
                got = data
            }
            offline = error != nil
            done.signal()
        }
        task.resume()
        // Waits in short steps, so Stop on the video ends the fetch.
        let deadline = Date().addingTimeInterval(10)
        while done.wait(timeout: .now() + 0.2) == .timedOut {
            if Task.isCancelled || Date() > deadline {
                task.cancel()
                return (nil, !Task.isCancelled)
            }
        }
        return (got, offline)
    }

    private static func key(_ z: Int32, _ x: Int32, _ y: Int32) -> String { "\(z)/\(x)/\(y)" }
}
