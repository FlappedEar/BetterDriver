import BetterDriverCore
import Foundation

/// A made-up 10 Hz receiver for the UI smoke test (UITestMode): laps of a 100 m circle at 20 m/s
/// around 0°, 0° in the sea, so a session records positions, speed and timing without hardware.
final class SimulatedGpsLink {
    private let sink: SampleSink
    private var timer: Timer?
    private var step = 0

    static let rateHz = 10.0
    static let radiusM = 100.0
    static let speedMs = 20.0

    init(sink: SampleSink) {
        self.sink = sink
    }

    /// Call on the main queue.
    func start() {
        guard timer == nil else { return }
        PhoneGnssChannels.shared.ALL.forEach { sink.defineChannel(def: $0) }
        timer = Timer.scheduledTimer(withTimeInterval: 1 / Self.rateHz, repeats: true) { [weak self] _ in
            self?.submit()
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private func submit() {
        step += 1
        let angle = Double(step) / Self.rateHz * Self.speedMs / Self.radiusM
        let metresPerDegree = 111_320.0
        let fix = PhoneFix(
            lat: Self.radiusM * sin(angle) / metresPerDegree,
            lon: Self.radiusM * cos(angle) / metresPerDegree,
            utcMillis: Int64((Date().timeIntervalSince1970 * 1_000).rounded()),
            speedMs: KotlinDouble(value: Self.speedMs),
            bearingDeg: KotlinDouble(value: (angle * 180 / .pi + 270).truncatingRemainder(dividingBy: 360)),
            altitudeM: KotlinDouble(value: 100),
            accuracyM: KotlinDouble(value: 3),
            satellites: nil
        )
        sink.submit(set: PhoneGnssKt.phoneFixSet(tMicros: sink.nowMicros(), fix: fix))
    }
}
