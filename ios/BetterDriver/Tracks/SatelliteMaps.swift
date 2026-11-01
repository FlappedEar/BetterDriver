import BetterDriverCore
import Combine
import Foundation
import ImageIO
import UIKit

/// Where a satellite photo comes from on iOS, best first (FBBD-411). Checked 2026-11-01 under the
/// Silesia Ring lap: Google's photo shows the track, Apple's (and Esri's, and GUGiK's tiles) predate
/// it. Google needs Arek's Google key and MapTiler his MapTiler key (the one the Android app uses),
/// both entered on the device and never in the repository or the app; Apple's needs none.
enum SatelliteSource: String, CaseIterable {
    case google
    case mapTiler
    case apple

    /// The name in the map menus.
    var name: String {
        switch self {
        case .google: "Satellite (Google)"
        case .mapTiler: "Satellite (MapTiler)"
        case .apple: "Satellite"
        }
    }
}

/// The keys, the Google session and the tiles of the satellite sources that come as Web Mercator tiles
/// (Google, MapTiler), shared by the track editor and the perfect lap in 3D. The tiles are kept only as
/// long as their own HTTP headers allow (URLCache), as Google's Map Tiles API policies ask; nothing of
/// ours is written to disk but the keys.
final class SatelliteMaps: ObservableObject {
    static let shared = SatelliteMaps()

    /// Goes up when a key or a credit changes, so the menus and the credits follow. Main queue.
    @Published private(set) var revision = 0

    private let lock = NSLock()
    private var session: (token: String, expiry: Date)?
    private var copyrights: [String: String] = [:]
    private let http: URLSession
    /// Set by the UI tests (-stubSatellite): stub keys, tiles drawn by the app, no network.
    private let stub: Bool

    private init() {
        stub = ProcessInfo.processInfo.arguments.contains("-stubSatellite")
        let config = URLSessionConfiguration.default
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        config.httpAdditionalHeaders = [
            "User-Agent": "BetterDriver/\(version) iOS (+https://github.com/FlappedEar/BetterDriver)",
            // A Google key limited to iOS apps checks this header.
            "X-Ios-Bundle-Identifier": Bundle.main.bundleIdentifier ?? "com.flappedear.betterdriver",
        ]
        config.timeoutIntervalForRequest = 15
        if stub { config.protocolClasses = [StubSatelliteProtocol.self] }
        http = URLSession(configuration: config)
    }

    // MARK: Keys

    func key(_ source: SatelliteSource) -> String? {
        if stub, source == .google { return "stub" }
        guard let file = Self.keyFile(source),
              let text = try? String(contentsOf: file, encoding: .utf8) else { return nil }
        let key = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return key.isEmpty ? nil : key
    }

    /// Keeps [key] for [source] on this device; a blank one removes it. Main queue.
    func setKey(_ key: String, for source: SatelliteSource) {
        guard let file = Self.keyFile(source) else { return }
        let value = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty {
            try? FileManager.default.removeItem(at: file)
        } else {
            try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? value.write(to: file, atomically: true, encoding: .utf8)
        }
        if source == .google {
            lock.lock()
            session = nil
            lock.unlock()
        }
        revision += 1
    }

    private static func keyFile(_ source: SatelliteSource) -> URL? {
        switch source {
        case .google: AppFolders.base.appendingPathComponent("google-maps.key")
        // The Android app's file name (TileLoader).
        case .mapTiler: AppFolders.base.appendingPathComponent("maptiler.key")
        case .apple: nil
        }
    }

    /// The sources with what they need, best first; Apple's always.
    var available: [SatelliteSource] { SatelliteSource.allCases.filter { $0 == .apple || key($0) != nil } }

    var best: SatelliteSource { available.first ?? .apple }

    // MARK: Credits

    /// What the picture must say while it shows [source]'s photo: Google's name and its photos' owners,
    /// MapTiler's credit (MapTiler's terms), or Apple's name (its logo is cut off the 3D tiles).
    func credit(_ source: SatelliteSource) -> String {
        switch source {
        case .google:
            lock.lock()
            let owners = Array(Set(copyrights.values)).sorted().joined(separator: ", ")
            lock.unlock()
            return owners.isEmpty ? "Google Maps" : "Google Maps · \(owners)"
        case .mapTiler: return TileSources.shared.mapTiler(key: "").attribution
        case .apple: return "Apple Maps"
        }
    }

    // MARK: Tiles

    /// The tile's picture, fetched now; nil without a key, without signal or on any error. Waits, so
    /// never on the main queue.
    func tile(_ source: SatelliteSource, z: Int32, x: Int32, y: Int32) -> CGImage? {
        guard !Thread.isMainThread, let url = tileURL(source, z: z, x: x, y: y),
              let data = get(URLRequest(url: url)) else { return nil }
        if source == .google { noteCopyright(z: z, x: x, y: y) }
        return Self.decode(data)
    }

    /// The tile's bytes for MapKit's overlay, delivered on any queue.
    func tileData(_ source: SatelliteSource, z: Int32, x: Int32, y: Int32, done: @escaping (Data?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            guard let url = self.tileURL(source, z: z, x: x, y: y), let data = self.get(URLRequest(url: url)) else {
                done(nil)
                return
            }
            if source == .google { self.noteCopyright(z: z, x: x, y: y) }
            done(data)
        }
    }

    private func tileURL(_ source: SatelliteSource, z: Int32, x: Int32, y: Int32) -> URL? {
        switch source {
        case .google:
            guard let key = key(.google), let token = googleSession(key: key) else { return nil }
            return URL(string: TileSources.shared.googleSatellite(key: key, session: token).url(z: z, x: x, y: y))
        case .mapTiler:
            guard let key = key(.mapTiler) else { return nil }
            return URL(string: TileSources.shared.mapTiler(key: key).url(z: z, x: x, y: y))
        case .apple:
            return nil
        }
    }

    /// Google's session token, made once and used until it expires.
    private func googleSession(key: String) -> String? {
        lock.lock()
        if let s = session, s.expiry > Date().addingTimeInterval(60) {
            lock.unlock()
            return s.token
        }
        lock.unlock()
        guard let url = URL(string: GoogleMapTiles.shared.createSessionUrl(key: key)) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = GoogleMapTiles.shared.sessionRequest().data(using: .utf8)
        guard let data = get(request),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = json["session"] as? String else { return nil }
        // "expiry" is seconds since 1970, as a string; two weeks per Google, one day if it is missing.
        let seconds = (json["expiry"] as? String).flatMap(Double.init) ?? (json["expiry"] as? Double)
        let expiry = seconds.map { Date(timeIntervalSince1970: $0) } ?? Date().addingTimeInterval(86400)
        lock.lock()
        session = (token, expiry)
        lock.unlock()
        return token
    }

    /// Asks once per area of 16 × 16 tiles whose photos those are, for the credit.
    private func noteCopyright(z: Int32, x: Int32, y: Int32) {
        let area = "\(z)/\(x / 16)/\(y / 16)"
        lock.lock()
        let known = copyrights[area] != nil
        if !known { copyrights[area] = "" }
        lock.unlock()
        guard !known, let key = key(.google), let token = googleSession(key: key) else { return }
        let n = Double(1 << z)
        func lon(_ x: Double) -> Double { x / n * 360 - 180 }
        func lat(_ y: Double) -> Double { atan(sinh(Double.pi * (1 - 2 * y / n))) * 180 / Double.pi }
        let x0 = Double(x / 16 * 16), y0 = Double(y / 16 * 16)
        let address = GoogleMapTiles.shared.viewportUrl(
            key: key, session: token, zoom: z,
            north: lat(y0), south: lat(y0 + 16), east: lon(x0 + 16), west: lon(x0)
        )
        guard let url = URL(string: address), let data = get(URLRequest(url: url)),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let text = json["copyright"] as? String else { return }
        lock.lock()
        copyrights[area] = text
        lock.unlock()
        // The editor's credit follows.
        DispatchQueue.main.async { self.revision += 1 }
    }

    /// The body of a 2xx answer to [request], waiting for it; nil otherwise.
    private func get(_ request: URLRequest) -> Data? {
        var got: Data?
        let done = DispatchSemaphore(value: 0)
        http.dataTask(with: request) { data, response, _ in
            if let r = response as? HTTPURLResponse, (200 ..< 300).contains(r.statusCode) { got = data }
            done.signal()
        }.resume()
        done.wait()
        return got
    }

    static func decode(_ data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
    }
}

/// The UI tests' Google (-stubSatellite): a session, a credit, and tiles in two colours with their
/// address written on them, so a screenshot shows which tile went where.
final class StubSatelliteProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "tile.googleapis.com" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        let body: Data
        var type = "application/json"
        if url.path.contains("createSession") {
            let expiry = Int(Date().timeIntervalSince1970) + 86400
            body = Data(#"{"session":"stub","expiry":"\#(expiry)","tileWidth":512,"tileHeight":512,"imageFormat":"png"}"#.utf8)
        } else if url.path.contains("viewport") {
            body = Data(#"{"copyright":"Stub imagery","maxZoomRects":[]}"#.utf8)
        } else {
            let parts = url.path.split(separator: "/").suffix(3).compactMap { Int($0) }
            body = Self.tile(parts.count == 3 ? parts : [0, 0, 0])
            type = "image/png"
        }
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": type])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func tile(_ zxy: [Int]) -> Data {
        let size = CGSize(width: 512, height: 512)
        let image = UIGraphicsImageRenderer(size: size, format: { let f = UIGraphicsImageRendererFormat(); f.scale = 1; return f }()).image { ctx in
            let even = (zxy[1] + zxy[2]) % 2 == 0
            (even ? UIColor(red: 0.85, green: 0.45, blue: 0.1, alpha: 1) : UIColor(red: 0.1, green: 0.5, blue: 0.55, alpha: 1)).setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            let text = "\(zxy[0])/\(zxy[1])/\(zxy[2])" as NSString
            text.draw(at: CGPoint(x: 24, y: 24), withAttributes: [
                .font: UIFont.boldSystemFont(ofSize: 48), .foregroundColor: UIColor.white,
            ])
        }
        return image.pngData() ?? Data()
    }
}
