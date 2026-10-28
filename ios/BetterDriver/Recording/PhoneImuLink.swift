import BetterDriverCore
import CoreMotion
import Foundation

/// The phone's accelerometer in a recording on iOS, as PhoneImuSource on Android (FBBD-218): recorded
/// in every session next to the receiver, and with phone GPS only the source of the live G (FBBD-337).
/// Readings at 25 Hz in g with gravity, on Android's axes, stamped at the time they were measured.
final class PhoneImuLink {
    private let motion = CMMotionManager()
    private let queue = OperationQueue()
    private let sink: SampleSink

    /// 25 Hz, the RaceBox's rate and Android's.
    static let interval = 0.04

    init(sink: SampleSink) {
        self.sink = sink
        queue.name = "phone-imu"
        queue.maxConcurrentOperationCount = 1
    }

    func start() {
        guard motion.isAccelerometerAvailable, !motion.isAccelerometerActive else { return }
        PhoneImuChannels.shared.ALL.forEach { sink.defineChannel(def: $0) }
        motion.accelerometerUpdateInterval = Self.interval
        motion.startAccelerometerUpdates(to: queue) { [sink] data, _ in
            guard let data else { return }
            sink.submit(set: Self.sampleSet(
                data.acceleration,
                measuredAt: data.timestamp,
                uptime: ProcessInfo.processInfo.systemUptime,
                nowMicros: sink.nowMicros()
            ))
        }
    }

    func stop() {
        motion.stopAccelerometerUpdates()
    }

    /// One reading as the set the recorder stores. iOS gives the accelerometer in g with the opposite
    /// sign to Android (a phone face up reads Z -1, Android +1) on the same axes, so each axis is
    /// negated. [measuredAt] and [uptime] are on the boot clock: their difference is the reading's age.
    static func sampleSet(_ a: CMAcceleration, measuredAt: TimeInterval, uptime: TimeInterval, nowMicros: Int64) -> SampleSet {
        let ageMicros = Int64(((uptime - measuredAt) * 1_000_000).rounded())
        return PhoneImuChannelsKt.phoneAccelSet(
            tMicros: PhoneImuChannelsKt.phoneImuMicrosByAge(nowMicros: nowMicros, ageMicros: ageMicros),
            x: -a.x,
            y: -a.y,
            z: -a.z
        )
    }
}
