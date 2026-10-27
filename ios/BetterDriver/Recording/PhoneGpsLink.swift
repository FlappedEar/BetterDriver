import CoreLocation
import BetterDriverCore

/// The phone's own GPS in a recording on iOS (FBBD-332), as PhoneGnssSource on Android (FBBD-275):
/// every position goes into the receiver channels with a phone's provenance, stamped at the time it
/// was measured. Keeps running with the phone locked (location background mode).
final class PhoneGpsLink: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let sink: SampleSink
    private let onState: (PhoneGpsState) -> Void
    private var running = false

    /// [onState] runs on the main queue.
    init(sink: SampleSink, onState: @escaping (PhoneGpsState) -> Void) {
        self.sink = sink
        self.onState = onState
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.activityType = .automotiveNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
    }

    /// Call on the main queue, while the app is in the foreground.
    func start() {
        guard !running else { return }
        running = true
        PhoneGnssChannels.shared.ALL.forEach { sink.defineChannel(def: $0) }
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways: begin()
        default: onState(.notAllowed)
        }
    }

    func stop() {
        running = false
        manager.stopUpdatingLocation()
        onState(.off)
    }

    private func begin() {
        guard running else { return }
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
        onState(.searching)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: begin()
        case .denied, .restricted: if running { onState(.notAllowed) }
        default: break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard running else { return }
        for location in locations where location.horizontalAccuracy >= 0 {
            sink.submit(set: Self.sampleSet(location, nowMicros: sink.nowMicros(), now: Date()))
        }
        onState(.fix)
    }

    /// [location] as the set the recorder stores, stamped [now] less the position's age.
    static func sampleSet(_ location: CLLocation, nowMicros: Int64, now: Date) -> SampleSet {
        let ageMicros = Int64((now.timeIntervalSince(location.timestamp) * 1_000_000).rounded())
        let fix = PhoneFix(
            lat: location.coordinate.latitude,
            lon: location.coordinate.longitude,
            utcMillis: Int64((location.timestamp.timeIntervalSince1970 * 1_000).rounded()),
            speedMs: location.speed >= 0 ? KotlinDouble(value: location.speed) : nil,
            bearingDeg: location.course >= 0 ? KotlinDouble(value: location.course) : nil,
            altitudeM: location.verticalAccuracy > 0 ? KotlinDouble(value: location.altitude) : nil,
            accuracyM: KotlinDouble(value: location.horizontalAccuracy),
            // iOS does not say how many satellites it used.
            satellites: nil
        )
        return PhoneGnssKt.phoneFixSet(
            tMicros: PhoneGnssKt.phoneFixMicrosByAge(nowMicros: nowMicros, ageMicros: ageMicros),
            fix: fix
        )
    }
}

/// Where the phone's GPS is, for the recording screen.
enum PhoneGpsState: Equatable {
    case off
    case notAllowed
    case searching
    case fix
}
