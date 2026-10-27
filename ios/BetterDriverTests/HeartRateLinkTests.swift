import XCTest
import BetterDriverCore
@testable import BetterDriver

/// The heart-rate strap on iPhone (FBBD-333). The simulator has no Bluetooth, so the link's path is
/// checked through the shared session it feeds, and the track screen's heart rate through its model.
@MainActor
final class HeartRateLinkTests: XCTestCase {
    private final class Sink: NSObject, SampleSink {
        var sets: [SampleSet] = []
        var events: [String] = []
        var now: Int64 = 1_000_000
        func defineChannel(def: ChannelDef) {}
        func submit(set: SampleSet) { sets.append(set) }
        func event(kind: String, text: String) { events.append(kind) }
        func nowMicros() -> Int64 { now }
    }

    func testNotificationsRecordTheHeartRate() {
        let sink = Sink()
        let session = HeartRateSession(sink: sink, address: "strap", deviceName: "Polar H10") {}
        session.start()
        XCTAssertTrue(session.streaming())
        // Contact detected, 92 bpm.
        session.feed(data: Data([0x06, 92]))
        XCTAssertEqual(sink.sets.count, 1)
        XCTAssertEqual(sink.events, [HeartRateEvents.shared.DEVICE, HeartRateEvents.shared.CONNECTED])
        XCTAssertEqual(session.status()?.bpm?.intValue, 92)
    }

    func testTheStripShowsTheHeartRateWithItsIcon() throws {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: base) }
        let info = SessionInfo(
            startUtcMillis: 1_760_169_600_000, car: "", track: "", devices: ["Phone GPS"],
            startFinish: nil, channels: [], imported: nil
        )
        let live = try LiveSession.companion.start(
            sessionsDir: base.appendingPathComponent("sessions").path,
            tracksDir: base.appendingPathComponent("tracks").path,
            circuits: CircuitList.companion.Empty,
            info: info,
            receiverHasG: false
        )
        let streaming = HeartRateStatus(link: .streaming, bpm: KotlinInt(int: 142))
        let model = TrackScreenModel(live.screen(), receiverHasG: false, heartRate: streaming)
        XCTAssertEqual(
            model.status.last,
            TrackScreenModel.Status(label: "Heart rate", value: "142", ok: true, icon: "heart.fill", keepValue: true)
        )
        // No value now (lost, or no contact): nothing on the strip, as on Android.
        let lost = TrackScreenModel(live.screen(), receiverHasG: false, heartRate: HeartRateStatus(link: .lost, bpm: nil))
        XCTAssertFalse(lost.status.contains { $0.label == "Heart rate" })
        XCTAssertTrue(live.stop())
    }
}
