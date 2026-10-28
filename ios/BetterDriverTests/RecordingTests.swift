import XCTest
import BetterDriverCore
import CoreMotion
import UIKit
@testable import BetterDriver

/// Recording on the iPhone (FBBD-332): a session at a saved track shows its laps on the track screen
/// as the Android app does, and the screen's fonts and the circuit directory ship in the app.
@MainActor
final class RecordingTests: XCTestCase {
    private var base: URL!

    override func setUpWithError() throws {
        base = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(
            at: base.appendingPathComponent("tracks"), withIntermediateDirectories: true
        )
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: base)
    }

    /// The demo ring of the Kotlin tests: 200 m radius, a 40 s lap at 25 Hz.
    private let gnss = FakeGnss(
        rateHz: 25, centerLat: 52.0, centerLon: 21.0, radiusM: 200.0, lapSeconds: 40.0,
        startUtcMillis: 1_760_169_600_000
    )

    /// A saved track around the ring with its start/finish line, as a track file of the app.
    private func saveRing() throws {
        let gate = gnss.startFinish
        let json = """
        {"format": "betterdriver-track", "version": 1, "track": {
          "id": "ring", "name": "Fake ring", "center": {"lat": \(gnss.centerLat), "lon": \(gnss.centerLon)},
          "startFinish": {"left": {"lat": \(gate.lat1), "lon": \(gate.lon1)}, "right": {"lat": \(gate.lat2), "lon": \(gate.lon2)}}
        }}
        """
        try json.write(to: base.appendingPathComponent("tracks/ring.json"), atomically: true, encoding: .utf8)
    }

    private func start(receiverHasG: Bool) throws -> LiveSession {
        let info = SessionInfo(
            startUtcMillis: gnss.startUtcMillis, car: "", track: "", devices: ["Phone GPS"],
            startFinish: nil, channels: GnssChannels.shared.ALL, imported: nil
        )
        return try LiveSession.companion.start(
            sessionsDir: base.appendingPathComponent("sessions").path,
            tracksDir: base.appendingPathComponent("tracks").path,
            circuits: CircuitList.companion.Empty,
            info: info,
            receiverHasG: receiverHasG
        )
    }

    func testLapsAtASavedTrackShowOnTheTrackScreen() throws {
        try saveRing()
        let session = try start(receiverHasG: false)
        for n in Int64(0)...Int64(25 * 140) {
            session.submit(set: SampleSet(tMicros: n * 40_000, values: gnss.fix(n: n)))
        }
        let model = TrackScreenModel(session.screen(), receiverHasG: false)
        XCTAssertEqual(model.bestMillis, 40_000)
        XCTAssertEqual(model.lastMillis, 40_000)
        XCTAssertNil(model.alert)
        XCTAssertEqual(model.status, [TrackScreenModel.Status(label: "GNSS", value: "25 Hz · 14 sats", ok: true)])
        // Speed, then four empty slots: the phone gives no G, so its tile is left out (UI-126 B).
        XCTAssertEqual(model.tiles.count, 5)
        XCTAssertEqual(model.tiles.first??.label, "Speed")
        XCTAssertEqual(model.tiles.first??.value, "113")
        // No coach started: no coach line, the tiles take its place.
        XCTAssertNil(model.coach)
        XCTAssertTrue(session.stop())
    }

    /// Says nothing aloud, and remembers what it was asked to say.
    private final class SilentSpeaker: NSObject, CoachSpeaker {
        private(set) var said: [String] = []
        var speaking: Bool { false }

        func say(text: String, speech: CoachSpeech) -> Bool {
            said.append(text)
            speech.started(route: "speaker", device: "Speaker")
            speech.ended(end: "done")
            return true
        }
    }

    /// The coach (FBBD-333) times the laps at a saved track and its last cue shows on the coach line.
    func testTheCoachSaysTheLapTimesOnTheCoachLine() throws {
        try saveRing()
        let session = try start(receiverHasG: false)
        let speaker = SilentSpeaker()
        session.startCoach(
            sessionsDir: base.appendingPathComponent("sessions").path,
            referencesDir: base.appendingPathComponent("coach/references").path,
            linePlansDir: base.appendingPathComponent("coach/lines").path,
            settings: LiveCoachKt.coachSettings(polish: false, audioDelayMillis: 0),
            audioDelayFromPhone: false,
            speaker: speaker
        )
        for n in Int64(0)...Int64(25 * 140) {
            session.submit(set: SampleSet(tMicros: n * 40_000, values: gnss.fix(n: n)))
        }
        // The coach works on a queue of its own.
        let deadline = Date().addingTimeInterval(10)
        while TrackScreenModel(session.screen(), receiverHasG: false).coach?.text != "40 point 0", Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        XCTAssertEqual(TrackScreenModel(session.screen(), receiverHasG: false).coach?.text, "40 point 0")
        XCTAssertEqual(speaker.said.first, "Best lap, 40 point 0")
        XCTAssertTrue(session.stop())
    }

    func testAnObdConnectionShowsAsTheCarIcon() throws {
        let session = try start(receiverHasG: false)
        for n in Int64(0)...Int64(25) {
            session.submit(set: SampleSet(tMicros: n * 40_000, values: gnss.fix(n: n)))
        }
        let model = TrackScreenModel(session.screen(), receiverHasG: false, obdConnected: true)
        XCTAssertEqual(model.status.last?.label, "OBD")
        XCTAssertEqual(model.status.last?.icon, "car.fill")
        XCTAssertTrue(session.stop())
    }

    func testBeforeTheFirstFixTheScreenWaits() throws {
        let session = try start(receiverHasG: true)
        let model = TrackScreenModel(session.screen(), receiverHasG: true)
        XCTAssertEqual(model.status.first?.value, "Waiting")
        XCTAssertNil(model.lapNumber)
        XCTAssertEqual(model.tiles.compactMap { $0?.label }, ["Speed", "G"])
        XCTAssertEqual(model.tiles.count, 6)
        XCTAssertTrue(session.stop())
    }

    /// The car's layout from Settings (FBBD-345): a tile without data in the session is left out, an
    /// empty slot stays; Settings draws every tile so each can be tapped.
    func testTheTrackScreenShowsTheCarsLayout() throws {
        let session = try start(receiverHasG: false)
        let ids = ["speed", "obd_coolant_temp", "", "g", "heart_rate", ""]
        let model = TrackScreenModel(session.screen(), receiverHasG: false, tileIds: ids)
        XCTAssertEqual(model.tiles.count, 3)
        XCTAssertEqual(model.tiles.first??.label, "Speed")
        XCTAssertNil(model.tiles[1])
        XCTAssertTrue(session.stop())
        let layout = TrackScreenModel(layout: ids, receiverHasG: false, coachOn: true)
        XCTAssertEqual(layout.tiles.map { $0?.label }, ["Speed", "Coolant temp", nil, "G", "Heart rate", nil])
        XCTAssertNotNil(layout.coach)
        XCTAssertNil(TrackScreenModel(layout: ids, receiverHasG: true, coachOn: false).coach)
    }

    func testTheLiveActivityShowsLapDeltaAndBest() throws {
        try saveRing()
        let session = try start(receiverHasG: false)
        let empty = RecordingActivity.state(TrackScreenModel(session.screen(), receiverHasG: false))
        XCTAssertEqual(empty, RecordingAttributes.ContentState(lap: nil, delta: nil, gain: false, best: nil))
        for n in Int64(0)...Int64(25 * 140) {
            session.submit(set: SampleSet(tMicros: n * 40_000, values: gnss.fix(n: n)))
        }
        let state = RecordingActivity.state(TrackScreenModel(session.screen(), receiverHasG: false))
        XCTAssertNotNil(state.lap)
        XCTAssertEqual(state.best, "0:40.00")
        XCTAssertTrue(session.stop())
    }

    /// iOS reads a phone lying face up as Z -1 g, Android as +1: the session stores Android's sign,
    /// stamped when the reading was measured (FBBD-337).
    func testThePhonesAccelerometerIsStoredOnAndroidsAxes() {
        let a = CMAcceleration(x: 0.25, y: -0.5, z: -1.0)
        let set = PhoneImuLink.sampleSet(a, measuredAt: 99.96, uptime: 100.0, nowMicros: 5_000_000)
        XCTAssertEqual(set.tMicros, 4_960_000)
        let values = Dictionary(uniqueKeysWithValues: set.values.map { ($0.channelId, $0.value) })
        XCTAssertEqual(values[PhoneImuChannels.shared.ACCEL_X.id], -0.25)
        XCTAssertEqual(values[PhoneImuChannels.shared.ACCEL_Y.id], 0.5)
        XCTAssertEqual(values[PhoneImuChannels.shared.ACCEL_Z.id], 1.0)
        // A stamp from another clock: delivery time.
        XCTAssertEqual(PhoneImuLink.sampleSet(a, measuredAt: 0, uptime: 100.0, nowMicros: 5_000_000).tMicros, 5_000_000)
    }

    func testRecordingClock() {
        XCTAssertEqual(TrackScreenModel.clock(micros: 83_000_000), "1:23")
        XCTAssertEqual(TrackScreenModel.clock(micros: 3_725_000_000), "1:02:05")
    }

    func testTheTrackScreenFontsAndTheCircuitListShipInTheApp() {
        _ = TrackFonts.register
        for face in ["SairaSemiCondensed-Bold", "SairaSemiCondensed-SemiBold", "Barlow-SemiBold"] {
            XCTAssertNotNil(UIFont(name: face, size: 12), face)
        }
        XCTAssertGreaterThan(BundledCircuits.list.circuits.count, 0)
    }
}
