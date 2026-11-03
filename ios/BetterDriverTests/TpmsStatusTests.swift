import XCTest
import UIKit
import BetterDriverCore
@testable import BetterDriver

/// The TPMS bridge on the track screen's status strip (FBBD-444). The simulator has no Bluetooth, so
/// the shared session the link feeds gives the status, and the track screen model shows it.
@MainActor
final class TpmsStatusTests: XCTestCase {
    private final class Sink: NSObject, SampleSink {
        var now: Int64 = 1_000_000
        func defineChannel(def: ChannelDef) {}
        func submit(set: SampleSet) {}
        func event(kind: String, text: String) {}
        func nowMicros() -> Int64 { now }
    }

    /// A pressure notification as the bridge sends it: PID little-endian, hundredths of a bar big-endian.
    private func pressure(_ pid: UInt8, _ centibar: Int) -> Data {
        Data([pid, 0, 0, 0, UInt8(centibar >> 8), UInt8(centibar & 0xFF)])
    }

    func testTheSymbolExists() {
        XCTAssertNotNil(UIImage(systemName: TrackScreenModel.tyreIcon))
    }

    func testTheStripShowsTheTyresWhileTheBridgeStreams() throws {
        let session = TpmsSession(sink: Sink(), address: "bridge", deviceName: "RC TPMS AMOLED")
        session.start()
        XCTAssertNil(session.status())
        session.linkChanged(s: LinkStateStreaming(hz: 8))
        XCTAssertNil(session.status())
        session.feed(data: pressure(1, 230))
        XCTAssertEqual(session.status(), TpmsStatus(value: "FR, RL, RR no data", ok: false))
        for pid: UInt8 in 2...4 { session.feed(data: pressure(pid, 230)) }
        let fine = try XCTUnwrap(session.status())

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
        let model = TrackScreenModel(live.screen(), receiverHasG: false, tyres: fine)
        XCTAssertEqual(
            model.status.last,
            TrackScreenModel.Status(label: "Tyres", value: "4 tyres", ok: true, icon: TrackScreenModel.tyreIcon)
        )
        let missing = TrackScreenModel(live.screen(), receiverHasG: false, tyres: TpmsStatus(value: "RR no data", ok: false))
        XCTAssertEqual(missing.status.last?.ok, false)
        XCTAssertEqual(missing.status.last?.value, "RR no data")
        let none = TrackScreenModel(live.screen(), receiverHasG: false, tyres: nil)
        XCTAssertFalse(none.status.contains { $0.label == "Tyres" })
        XCTAssertTrue(live.stop())
    }
}
