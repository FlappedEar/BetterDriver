import BetterDriverCore
import CoreLocation
import Foundation

/// The saved tracks for the Tracks screen and the editor (FBBD-336), as Tracks on Android: the list,
/// where the phone is and what is there, and the saves. Disk work runs off the main thread.
@MainActor
final class TracksModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var tracks: [Track] = []
    /// Where the phone is, once found; nil while looking or without location access.
    @Published private(set) var here: Here?
    @Published private(set) var locating = true
    @Published var message: String?

    nonisolated static let library = TrackLibrary(tracksDir: AppFolders.tracks.path, sessionsDir: AppFolders.sessions.path)

    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    /// Reads the list again and looks for where the phone is.
    func refresh() {
        Task.detached {
            let all = Self.library.list()
            await MainActor.run { self.tracks = all }
        }
        locate()
    }

    func locate() {
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways: manager.requestLocation()
        default: locating = false
        }
    }

    /// Create track from the circuit near the phone; hands the saved track to [open].
    func create(from circuit: Circuit, open: @escaping (Track) -> Void) {
        save(open) { try Self.library.createFrom(circuit: circuit) }
    }

    /// New track here: a track at the phone's position named [name].
    func createHere(name: String, open: @escaping (Track) -> Void) {
        guard let h = here else { return }
        save(open) { try Self.library.createAt(lat: h.lat, lon: h.lon, name: name) }
    }

    private func save(_ open: @escaping (Track) -> Void, _ make: @escaping () throws -> Track) {
        Task.detached {
            do {
                let track = try make()
                await MainActor.run {
                    self.refresh()
                    open(track)
                }
            } catch {
                await MainActor.run { self.message = "The track could not be saved." }
            }
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        if status == .authorizedWhenInUse || status == .authorizedAlways {
            manager.requestLocation()
        } else if status != .notDetermined {
            Task { @MainActor in self.locating = false }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let at = locations.last?.coordinate else { return }
        let found = Self.library.here(lat: at.latitude, lon: at.longitude, circuits: BundledCircuits.list)
        Task { @MainActor in
            self.here = found
            self.locating = false
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.locating = false }
    }
}

extension Track {
    /// Under the name in the list: the lines set (Android's trackDetail).
    var detail: String {
        let lines: String
        if !timeable {
            lines = pointToPoint && startFinish != nil ? "No finish line yet" : "No lines yet"
        } else if sectorCount == 1 {
            lines = "1 sector"
        } else {
            lines = "\(sectorCount) sectors"
        }
        return pointToPoint ? "Point to point · \(lines)" : lines
    }
}
