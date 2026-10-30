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
            outputDelayMillis: 0,
            output: "speaker",
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

    /// Settings › Spoken coach reaches the session's coach (audit BD-01): off speaks nothing; the
    /// language, verbosity and learning laps set are the ones it runs with.
    func testTheCoachRunsWithTheSettings() throws {
        let core = SettingsCore.shared
        let off = try XCTUnwrap(core.verbosities.first { $0.name == "Off" })
        XCTAssertNil(RecordingController.sessionCoach(core.withVerbosity(s: core.coachSettings(text: nil), v: off), phoneDelayMillis: 0))
        let polish = try XCTUnwrap(core.languages.first { $0.name == "Polish" })
        var set = core.withLanguage(s: core.coachSettings(text: nil), l: polish)
        set = core.withLearningLaps(s: set, laps: 2)
        let settings = try XCTUnwrap(RecordingController.sessionCoach(set, phoneDelayMillis: 120))
        XCTAssertEqual(settings.language.name, "Polish")
        XCTAssertEqual(settings.verbosity, set.verbosity)
        XCTAssertEqual(settings.learningLaps, 2)
        XCTAssertEqual(settings.audioDelayMillis, 120)
        let spoken = try XCTUnwrap(RecordingController.sessionCoach(core.withLanguage(s: core.coachSettings(text: nil), l: polish), phoneDelayMillis: 0))

        try saveRing()
        let session = try start(receiverHasG: false)
        let speaker = SilentSpeaker()
        session.startCoach(
            sessionsDir: base.appendingPathComponent("sessions").path,
            referencesDir: base.appendingPathComponent("coach/references").path,
            linePlansDir: base.appendingPathComponent("coach/lines").path,
            settings: spoken,
            outputDelayMillis: 0,
            output: "speaker",
            speaker: speaker
        )
        for n in Int64(0)...Int64(25 * 140) {
            session.submit(set: SampleSet(tMicros: n * 40_000, values: gnss.fix(n: n)))
        }
        let deadline = Date().addingTimeInterval(10)
        while speaker.said.isEmpty, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        // The lap is said in Polish, not as the English "Best lap, 40 point 0".
        XCTAssertFalse(speaker.said.isEmpty)
        XCTAssertNotEqual(speaker.said.first, "Best lap, 40 point 0")
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

    /// The second screen (FBBD-262, FBBD-318): with G, the radar beside the first nine tiles, three rows
    /// of three; a tile without data is left out of its row and a row with none goes; Settings draws all.
    func testTheSecondScreenShowsTheRadarAndItsTiles() throws {
        let session = try start(receiverHasG: true)
        let second = [
            "speed", "obd_rpm", "", // row 1: the RPM has no data in this session
            "obd_coolant_temp", "obd_oil_temp", "heart_rate", // row 2: none with data
            "g", "satellites", "", // row 3
            "best_lap", "last_lap", "", // past the radar's nine
        ]
        let model = TrackScreenModel(session.screen(), receiverHasG: true, secondTileIds: second)
        XCTAssertTrue(model.radarOn)
        XCTAssertNil(model.radar, "no G before the first reading")
        XCTAssertEqual(model.secondShown.count, 9)
        XCTAssertEqual(model.secondShownColumns, 3)
        XCTAssertEqual(
            TrackScreenModel.shownRows(model.secondShown, columns: 3, rows: 3, editing: false), [[0, 2], [6, 7, 8]]
        )
        XCTAssertTrue(session.stop())

        let noG = TrackScreenModel(session.screen(), receiverHasG: false, secondTileIds: second)
        XCTAssertFalse(noG.radarOn)
        XCTAssertEqual(noG.secondShown.count, 12)
        XCTAssertEqual(noG.secondShownColumns, 4)

        let layout = TrackScreenModel(layout: [], secondIds: second, receiverHasG: true, coachOn: true)
        XCTAssertTrue(layout.editable)
        XCTAssertEqual(
            TrackScreenModel.shownRows(layout.secondShown, columns: 3, rows: 3, editing: true),
            [[0, 1, 2], [3, 4, 5], [6, 7, 8]]
        )
        XCTAssertEqual(layout.secondShown[1]?.label, "RPM")
    }

    /// The radar's dot moves the way the driver is pushed (UI-243 A): braking up, a right turn left, and
    /// beyond 1.2 g it stays on the edge.
    func testTheRadarDotMovesTheWayTheDriverIsPushed() {
        let radar = TrackScreenModel.radar(
            now: GPoint(forward: -0.63, left: -0.53),
            trail: [GPoint(forward: -0.58, left: -0.19), GPoint(forward: -0.63, left: -0.28)]
        )
        XCTAssertEqual(radar?.now, TrackScreenModel.RadarPoint(forward: -0.63, left: -0.53))
        XCTAssertEqual(radar?.trail.count, 2)
        XCTAssertNil(TrackScreenModel.radar(now: nil, trail: []))

        let centre = CGPoint(x: 100, y: 100)
        let braking = GRadarView.position(.init(forward: -0.5, left: 0), centre: centre, g: 50)
        XCTAssertEqual(braking.y, 75, accuracy: 0.001)
        XCTAssertEqual(braking.x, 100, accuracy: 0.001)
        let rightTurn = GRadarView.position(.init(forward: 0, left: -1), centre: centre, g: 50)
        XCTAssertEqual(rightTurn.x, 50, accuracy: 0.001)
        let hard = GRadarView.position(.init(forward: -3, left: 0), centre: centre, g: 50)
        XCTAssertEqual(hard.y, 100 - 1.2 * 50, accuracy: 0.001)
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
