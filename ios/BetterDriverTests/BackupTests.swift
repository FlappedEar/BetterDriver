import XCTest
import BetterDriverCore
@testable import BetterDriver

/// Back up, Restore and track files on the iPhone (FBBD-372), through the shared core the Android app
/// uses: a backup written here restores on another device with its tracks, a damaged file is refused,
/// and a track file goes over and is matched as Android matches it. The Android ⇄ shared-core file
/// checks are the JVM's BackupInteropTest.
final class BackupTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func device(_ name: String) throws -> URL {
        let base = root.appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: base.appendingPathComponent("sessions"), withIntermediateDirectories: true)
        return base
    }

    private func library(_ base: URL) -> TrackLibrary {
        TrackLibrary(tracksDir: base.appendingPathComponent("tracks").path, sessionsDir: base.appendingPathComponent("sessions").path)
    }

    func testABackupRestoresOnAnotherDeviceWithItsTracksAndSettings() throws {
        let phone = try device("phone")
        _ = try library(phone).createAt(lat: 52.0, lon: 21.0, name: "Backup test")
        let coach = phone.appendingPathComponent("coach", isDirectory: true)
        try FileManager.default.createDirectory(at: coach, withIntermediateDirectories: true)
        try "verbosity=NORMAL\n".write(to: coach.appendingPathComponent("settings.properties"), atomically: true, encoding: .utf8)

        var last: Int64 = 0
        let path = try IosBackups(base: phone.path).write(
            outRoot: root.appendingPathComponent("out").path, fileName: BackupBridge.fileName(),
            includeSessions: true, appVersion: "test"
        ) { written, _ in last = written.int64Value }
        XCTAssertTrue(path.hasSuffix(".bdbackup"))
        XCTAssertGreaterThan(last, 0)

        let tablet = try device("tablet")
        let backups = IosBackups(base: tablet.path)
        let staged = try backups.open(path: path) { _, _ in }
        XCTAssertEqual(staged.summary.tracks, 1)
        XCTAssertEqual(staged.summary.coachFiles, 1)
        XCTAssertFalse(staged.summary.prefs)
        XCTAssertEqual(staged.summary.appVersion, "test")
        XCTAssertEqual(BackupTexts.shared.contentLabels(s: staged.summary).first, "Tracks")

        let result = try backups.restore(backup: staged, replace: false)
        XCTAssertEqual(result.tracksAdded, 1)
        XCTAssertEqual(BackupTexts.shared.result(r: result), "Restored: 1 track added, settings restored.")
        XCTAssertEqual(library(tablet).list().map(\.displayName), ["Backup test"])
        XCTAssertTrue(FileManager.default.fileExists(atPath: tablet.appendingPathComponent("coach/settings.properties").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: tablet.appendingPathComponent("backup-staging/restore").path))
    }

    func testADamagedOrForeignFileIsRefusedAndLeavesNothingBehind() throws {
        let tablet = try device("tablet")
        let file = root.appendingPathComponent("damaged.bdbackup")
        try Data("BDBACKUP not really".utf8).write(to: file)
        XCTAssertThrowsError(try IosBackups(base: tablet.path).open(path: file.path) { _, _ in })
        try Data("BDBUNDLE a session".utf8).write(to: file)
        XCTAssertThrowsError(try IosBackups(base: tablet.path).open(path: file.path) { _, _ in })
        let staging = tablet.appendingPathComponent("backup-staging").path
        XCTAssertEqual((try? FileManager.default.contentsOfDirectory(atPath: staging)) ?? [], [])
    }

    func testATrackFileGoesToTheOtherDeviceAndIsMatchedAsOnAndroid() throws {
        let phone = try device("phone")
        let made = try library(phone).createAt(lat: 52.0, lon: 21.0, name: "Tor Jastrząb")
        let path = try IosTrackShare(tracksRoot: phone.appendingPathComponent("tracks").path)
            .write(id: made.id, outRoot: root.appendingPathComponent("out").path)
        XCTAssertTrue(path.hasSuffix("Tor-Jastrząb.bdtrack"), path)

        let tablet = try device("tablet")
        let share = IosTrackShare(tracksRoot: tablet.appendingPathComponent("tracks").path)
        let track = try share.read(path: path)
        XCTAssertNotEqual(share.match(track: track), TrackMatch.same)
        XCTAssertNotEqual(share.match(track: track), TrackMatch.different)
        try share.save(track: track)
        XCTAssertEqual(library(tablet).list().map(\.id), [made.id])
        XCTAssertEqual(share.match(track: try share.read(path: path)), TrackMatch.same)

        let notTrack = root.appendingPathComponent("not.bdtrack")
        try Data("{\"hello\":1}".utf8).write(to: notTrack)
        XCTAssertThrowsError(try share.read(path: notTrack.path))
    }
}
