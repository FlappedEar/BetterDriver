import MapKit

/// The editor map's backgrounds on iOS: Apple Maps (MapKit), satellite first. Android's editor uses
/// MapTiler or OpenStreetMap tiles (FBBD-230); on iOS every map is Apple's, so no key is needed.
/// MapKit keeps recently seen map data itself for a while; Apple's terms allow no store of our own,
/// so at a circuit without signal only what the phone saw recently shows, and lines are still placed
/// on the recorded lap.
enum EditorBackground: String, CaseIterable, Identifiable {
    case satellite
    case standard

    var id: String { rawValue }

    /// The name in the editor's map menu.
    var name: String {
        switch self {
        case .satellite: "Satellite"
        case .standard: "Map"
        }
    }

    /// MapKit's look for this background: the photo without labels, or the plain map, muted.
    var configuration: MKMapConfiguration {
        switch self {
        case .satellite: MKImageryMapConfiguration()
        case .standard: MKStandardMapConfiguration(emphasisStyle: .muted)
        }
    }
}
