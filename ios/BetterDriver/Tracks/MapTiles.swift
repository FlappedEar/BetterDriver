import BetterDriverCore
import MapKit

/// The editor map's backgrounds (FBBD-230 on Android): MapTiler's satellite map once a key is entered
/// on this device, else OpenStreetMap (timing's TileSources). Tiles are kept in the app's caches
/// folder, so a track made at the circuit works without signal for the places already seen.
enum MapTiles {
    private static var keyFile: URL { AppFolders.base.appendingPathComponent("maptiler.key") }

    /// The MapTiler key, kept on this device only (never in the repo or the app).
    static var mapTilerKey: String? {
        get {
            let text = try? String(contentsOf: keyFile, encoding: .utf8)
            return text?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        }
        set {
            if let key = newValue?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty {
                try? key.write(to: keyFile, atomically: true, encoding: .utf8)
            } else {
                try? FileManager.default.removeItem(at: keyFile)
            }
        }
    }

    /// The backgrounds, best first.
    static func sources() -> [TileSource] { TileSources.shared.available(mapTilerKey: mapTilerKey) }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

/// One background's tiles for MapKit, fetched with the app's name as OpenStreetMap's tile policy asks,
/// and kept on disk by source, zoom, x and y (the key is not part of the name).
final class CachedTileOverlay: MKTileOverlay {
    let source: TileSource
    private let folder: URL
    private let session: URLSession

    init(source: TileSource) {
        self.source = source
        folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("tiles/\(source.id)", isDirectory: true)
        let config = URLSessionConfiguration.default
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        config.httpAdditionalHeaders = [
            "User-Agent": "BetterDriver/\(version) iOS (+https://github.com/FlappedEar/BetterDriver)",
        ]
        session = URLSession(configuration: config)
        super.init(urlTemplate: nil)
        canReplaceMapContent = true
        maximumZ = Int(source.maxZoom)
        tileSize = CGSize(width: 256, height: 256)
    }

    override func url(forTilePath path: MKTileOverlayPath) -> URL {
        URL(string: source.url(z: Int32(path.z), x: Int32(path.x), y: Int32(path.y)))!
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        let file = folder.appendingPathComponent("\(path.z)/\(path.x)/\(path.y)")
        if let data = try? Data(contentsOf: file) {
            result(data, nil)
            return
        }
        session.dataTask(with: url(forTilePath: path)) { data, response, error in
            let ok = (response as? HTTPURLResponse).map { (200..<300).contains($0.statusCode) } ?? false
            if let data, ok {
                try? FileManager.default.createDirectory(
                    at: file.deletingLastPathComponent(), withIntermediateDirectories: true
                )
                try? data.write(to: file, options: .atomic)
                result(data, nil)
            } else {
                result(nil, error ?? URLError(.badServerResponse))
            }
        }.resume()
    }
}
