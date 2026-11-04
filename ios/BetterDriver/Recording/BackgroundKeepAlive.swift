import CoreLocation
import UIKit

/// Keeps a recording running with the phone locked or another app in front (Moto Park readiness,
/// 2026-11-04), as Android's foreground service does. With an external receiver (RaceBox, Bluetooth or
/// Wi-Fi GPS) nothing else asks iOS to keep the app running: Bluetooth only wakes a suspended app for
/// each event, a Wi-Fi receiver's socket and every timer stop while it is suspended, and a recorder
/// holding its session lock may be ended by the system (0xdead10cc). Coarse location updates with the
/// location background mode keep the app running until the recording ends. The phone's GPS link does
/// this on its own, so it is not needed with phone GPS.
final class BackgroundKeepAlive: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var running = false
    private var updating = false
    private var observers: [NSObjectProtocol] = []

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
    }

    /// Call on the main queue, while the app is in the foreground (iOS keeps background location only
    /// for updates started there).
    func start() {
        guard !running else { return }
        running = true
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                self?.logState("in background")
            },
            center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
                self?.logState("back in front")
            },
        ]
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: begin()
        // Asked once; the answer comes to locationManagerDidChangeAuthorization.
        case .notDetermined: manager.requestWhenInUseAuthorization()
        // Without it the recording still runs while the screen is on (it stays on during a recording).
        default: AppLog.w("KeepAlive", "no location access: the recording may stop with the phone locked")
        }
    }

    func stop() {
        guard running else { return }
        running = false
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        updating = false
        manager.stopUpdatingLocation()
        AppLog.i("KeepAlive", "off")
    }

    private func begin() {
        guard running, !updating else { return }
        updating = true
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
        AppLog.i("KeepAlive", "on: coarse location keeps the recording running in the background")
    }

    private func logState(_ what: String) {
        guard running else { return }
        AppLog.i("KeepAlive", "\(what) while recording, location \(manager.authorizationStatus.rawValue)")
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: begin()
        default: break
        }
    }

    // The positions themselves are not used: the receiver gives the session's positions.
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {}

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        AppLog.w("KeepAlive", "location: \(error.localizedDescription)")
    }
}
