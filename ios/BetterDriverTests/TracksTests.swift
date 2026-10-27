import XCTest
import BetterDriverCore
import MapKit
@testable import BetterDriver

/// Tracks on the iPhone (FBBD-336): a track made here, with its start/finish line placed on the
/// recorded lap in the editor, times laps from the next session, as on Android.
@MainActor
final class TracksTests: XCTestCase {
    private var base: URL!
    private var library: TrackLibrary!

    private let gnss = FakeGnss(
        rateHz: 25, centerLat: 52.0, centerLon: 21.0, radiusM: 200.0, lapSeconds: 40.0,
        startUtcMillis: 1_760_169_600_000
    )

    override func setUpWithError() throws {
        base = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        library = TrackLibrary(
            tracksDir: base.appendingPathComponent("tracks").path,
            sessionsDir: base.appendingPathComponent("sessions").path
        )
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: base)
    }

    /// Records three and a half laps; returns the best lap the track screen showed, if any.
    private func record() throws -> Int64? {
        let info = SessionInfo(
            startUtcMillis: gnss.startUtcMillis, car: "", track: "", devices: ["Phone GPS"],
            startFinish: nil, channels: GnssChannels.shared.ALL, imported: nil
        )
        let session = try LiveSession.companion.start(
            sessionsDir: base.appendingPathComponent("sessions").path,
            tracksDir: base.appendingPathComponent("tracks").path,
            circuits: CircuitList.companion.Empty,
            info: info,
            receiverHasG: false
        )
        for n in Int64(0)...Int64(25 * 140) {
            session.submit(set: SampleSet(tMicros: n * 40_000, values: gnss.fix(n: n)))
        }
        let best = TrackScreenModel(session.screen(), receiverHasG: false).bestMillis
        _ = session.stop()
        return best
    }

    func testANewTrackHereGivesLapsFromTheNextSession() throws {
        XCTAssertNil(try record())
        let track = try library.createAt(lat: gnss.centerLat, lon: gnss.centerLon, name: "Moto ring")
        XCTAssertEqual(track.detail, "No lines yet")
        let sessions = library.editorSessions(track: track)
        XCTAssertEqual(sessions.count, 1)
        let model = EditorModel(TrackEditor(initial: track, sessions: sessions, stored: track))
        XCTAssertTrue(model.editor.placingFromLap)
        XCTAssertFalse(model.editor.pathCoordinates.isEmpty)
        model.act { $0.select(index: 0) }
        XCTAssertEqual(model.editor.title, "Start / finish")
        let gate = gnss.startFinish
        model.act { $0.onTap(lat: (gate.lat1 + gate.lat2) / 2, lon: (gate.lon1 + gate.lon2) / 2) }
        XCTAssertNotNil(model.editor.selectedLine)
        model.act { $0.done() }
        XCTAssertTrue(model.editor.changed)
        XCTAssertTrue(model.editor.lines()[0].placed)
        let sent = model.editor.track
        let stored = try library.save(track: sent)
        model.act { $0.saved(sent: sent, stored: stored) }
        XCTAssertFalse(model.editor.changed)
        XCTAssertEqual(stored.detail, "1 sector")

        XCTAssertEqual(try record(), 40_000)
    }

    func testTheEditorMapIsAppleMapsSatelliteFirst() {
        XCTAssertEqual(EditorBackground.allCases, [.satellite, .standard])
        XCTAssertTrue(EditorBackground.satellite.configuration is MKImageryMapConfiguration)
        XCTAssertTrue(EditorBackground.standard.configuration is MKStandardMapConfiguration)
    }
}
