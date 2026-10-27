import XCTest
import BetterDriverCore
@testable import BetterDriver

/// The RaceBox on iPhone (FBBD-332). The simulator has no Bluetooth, so the link's decoding path is
/// checked through the shared session with the RaceBox document's example packet.
final class RaceBoxLinkTests: XCTestCase {
    /// RaceBox BLE Protocol Description rev 8, page 7, as in RaceBoxTestData (Kotlin).
    private let officialExample =
        "B5 62 FF 01 50 00 A0 E7 0C 07 E6 07 01 0A 08 33 08 37 19 00 00 00 2A AD 4D 0E 03 01 EA 0B C6 93 " +
        "E1 0D 3B 37 6F 19 61 8C 09 00 0F 01 09 00 9C 03 00 00 2C 07 00 00 23 00 00 00 00 00 00 00 D0 00 " +
        "00 00 88 A9 DD 00 2C 01 00 59 FD FF 71 00 CE 03 2F FF 56 00 FC FF 06 DB"

    private final class Sink: NSObject, SampleSink {
        var sets: [SampleSet] = []
        var events: [String] = []
        func defineChannel(def: ChannelDef) {}
        func submit(set: SampleSet) { sets.append(set) }
        func event(kind: String, text: String) { events.append(kind) }
        func nowMicros() -> Int64 { 1_000_000 }
    }

    func testNotificationsDecodeIntoTheRecording() {
        let packet = Data(officialExample.split(separator: " ").map { UInt8($0, radix: 16)! })
        let sink = Sink()
        let session = RaceBoxSession(sink: sink, isMicro: false, calibration: nil, log: { _ in }, onSlowLink: { _ in })
        session.start()
        session.streaming(address: "test")
        // Split as a default-MTU link would deliver it.
        session.feed(data: packet.prefix(20))
        session.feed(data: packet.dropFirst(20))
        XCTAssertEqual(sink.sets.count, 1)
        XCTAssertEqual(sink.events.first, RaceBoxEvents.shared.IMU_CALIBRATION)
        XCTAssertTrue(sink.events.contains(RaceBoxEvents.shared.CONNECTED))
    }

    func testRaceBoxNamesAreRecognised() {
        XCTAssertTrue(FoundRaceBox.companion.isRaceBox(name: "RaceBox Micro 1234563241"))
        XCTAssertFalse(FoundRaceBox.companion.isRaceBox(name: "Pixel Buds"))
        let micro = FoundRaceBoxDevice(id: UUID(), name: "RaceBox Micro 1234563241", rssi: -60)
        XCTAssertTrue(micro.isMicro)
    }

    func testRecordsWithThePhoneLocked() {
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String]
        XCTAssertEqual(modes, ["bluetooth-central"])
        let why = Bundle.main.object(forInfoDictionaryKey: "NSBluetoothAlwaysUsageDescription") as? String
        XCTAssertFalse(why?.isEmpty ?? true)
    }
}
