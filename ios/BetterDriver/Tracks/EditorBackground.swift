import MapKit

/// The editor map's backgrounds on iOS: the satellite photos with what they need, best first
/// (FBBD-411: Google's, then MapTiler's, as on Android, then Apple's), and Apple's plain map. Android's
/// editor uses MapTiler or OpenStreetMap tiles (FBBD-230). Google's and MapTiler's tiles are kept only as
/// long as their HTTP headers allow, and Apple's terms allow no store of our own, so at a circuit
/// without signal only what the phone saw recently shows, and lines are still placed on the recorded lap.
enum EditorBackground: String, CaseIterable, Identifiable {
    case googleSatellite
    case mapTilerSatellite
    case satellite
    case standard

    var id: String { rawValue }

    /// The name in the editor's map menu.
    var name: String {
        switch self {
        case .googleSatellite: SatelliteSource.google.name
        case .mapTilerSatellite: SatelliteSource.mapTiler.name
        case .satellite: SatelliteSource.apple.name
        case .standard: "Map"
        }
    }

    /// The satellite source this background shows; nil for the plain map.
    var satellite: SatelliteSource? {
        switch self {
        case .googleSatellite: .google
        case .mapTilerSatellite: .mapTiler
        case .satellite: .apple
        case .standard: nil
        }
    }

    /// MapKit's look under this background: the photo without labels, or the plain map, muted. Google's
    /// and MapTiler's tiles cover it entirely (EditorTileOverlay).
    var configuration: MKMapConfiguration {
        switch self {
        case .googleSatellite, .mapTilerSatellite, .satellite: MKImageryMapConfiguration()
        case .standard: MKStandardMapConfiguration(emphasisStyle: .muted)
        }
    }

    /// The backgrounds that can be shown now: the satellite photos with their key, then the plain map.
    static var available: [EditorBackground] {
        SatelliteMaps.shared.available.map(Self.init(_:)) + [.standard]
    }

    /// The best satellite photo at hand.
    static var best: EditorBackground { Self(SatelliteMaps.shared.best) }

    init(_ source: SatelliteSource) {
        switch source {
        case .google: self = .googleSatellite
        case .mapTiler: self = .mapTilerSatellite
        case .apple: self = .satellite
        }
    }
}

/// Google's or MapTiler's tiles over the whole editor map (FBBD-411), fetched by SatelliteMaps.
final class EditorTileOverlay: MKTileOverlay {
    let source: SatelliteSource

    init(source: SatelliteSource) {
        self.source = source
        super.init(urlTemplate: nil)
        canReplaceMapContent = true
        maximumZ = source == .google ? 22 : 20
        tileSize = CGSize(width: 256, height: 256)
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        SatelliteMaps.shared.tileData(source, z: Int32(path.z), x: Int32(path.x), y: Int32(path.y)) { data in
            result(data, data == nil ? URLError(.badServerResponse) : nil)
        }
    }
}
