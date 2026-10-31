import XCTest
import CoreLocation
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

    /// The diagnostics screen's probe (FBBD-371): the shared RaceBoxDiagnostics behind a lock, fed as
    /// the link feeds it, asking only the read-only queries the firmware answers.
    func testTheDiagnosticsProbeReadsTheExampleAndAsksReadOnlyQueries() {
        let packet = Data(officialExample.split(separator: " ").map { UInt8($0, radix: 16)! })
        let probe = RaceBoxDiagProbe(isMicro: true)
        XCTAssertTrue(probe.queries().isEmpty, "nothing is asked before the Device Information strings")
        probe.deviceInfo(id: "2A24", value: "RaceBox Micro")
        probe.deviceInfo(id: "2A26", value: "3.4")
        probe.link(rssi: -61, mtu: 185)
        probe.state(LinkStateStreaming(hz: 25))
        probe.data(packet.prefix(20))
        probe.data(packet.dropFirst(20))
        let (state, s) = probe.snapshot()
        XCTAssertTrue(state is LinkStateStreaming)
        XCTAssertEqual(s.messages, 1)
        XCTAssertEqual(s.latest?.satellites, 11)
        XCTAssertEqual(s.rssiDbm?.intValue, -61)
        let queries = probe.queries()
        XCTAssertEqual(queries.count, 2)
        // B5 62 FF 27 (receiver settings) and B5 62 FF 22 (memory status), both with empty payloads.
        XCTAssertEqual(Array(queries[0].prefix(6)), [0xB5, 0x62, 0xFF, 0x27, 0x00, 0x00])
        XCTAssertEqual(Array(queries[1]), [0xB5, 0x62, 0xFF, 0x22, 0x00, 0x00, 0x21, 0x62])
    }

    func testRaceBoxNamesAreRecognised() {
        XCTAssertTrue(FoundRaceBox.companion.isRaceBox(name: "RaceBox Micro 1234563241"))
        XCTAssertFalse(FoundRaceBox.companion.isRaceBox(name: "Pixel Buds"))
        let micro = FoundRaceBoxDevice(id: UUID(), name: "RaceBox Micro 1234563241", rssi: -60)
        XCTAssertTrue(micro.isMicro)
    }

    func testRecordsWithThePhoneLocked() {
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        XCTAssertTrue(modes.contains("bluetooth-central"))
        let why = Bundle.main.object(forInfoDictionaryKey: "NSBluetoothAlwaysUsageDescription") as? String
        XCTAssertFalse(why?.isEmpty ?? true)
    }
}

/// The phone's GPS on iPhone (FBBD-332): positions become the same sets as on Android.
final class PhoneGpsLinkTests: XCTestCase {
    func testAPositionIsStampedWhenMeasured() {
        let now = Date(timeIntervalSince1970: 1_790_000_000)
        let location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 50.1, longitude: 19.9),
            altitude: 220,
            horizontalAccuracy: 3.5,
            verticalAccuracy: 5,
            course: 90,
            speed: 25,
            timestamp: now.addingTimeInterval(-0.3)
        )
        let set = PhoneGpsLink.sampleSet(location, nowMicros: 60_000_000, now: now)
        XCTAssertEqual(set.tMicros, 59_700_000)
        let values = Dictionary(uniqueKeysWithValues: set.values.map { ($0.channelId, $0.value) })
        XCTAssertEqual(values[GnssChannels.shared.LAT.id], 50.1)
        XCTAssertEqual(values[GnssChannels.shared.SPEED_KMH.id] ?? 0, 90, accuracy: 1e-9)
        XCTAssertEqual(values[PhoneGnssChannels.shared.ACCURACY.id], 3.5)
        XCTAssertNil(values[GnssChannels.shared.SATS.id])
    }

    func testRecordsPositionsWithThePhoneLocked() {
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        XCTAssertTrue(modes.contains("location"))
        let why = Bundle.main.object(forInfoDictionaryKey: "NSLocationWhenInUseUsageDescription") as? String
        XCTAssertFalse(why?.isEmpty ?? true)
    }
}
