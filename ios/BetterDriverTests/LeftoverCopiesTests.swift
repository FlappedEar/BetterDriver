import XCTest
@testable import BetterDriver

/// Copies made only to hand a file on go once handed on, and only they (FBBD-467): the 5 Nov AirDrop
/// left an 8.21 GB .bdsession behind on the iPhone, and the iPad kept the received one in its Inbox.
final class LeftoverCopiesTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("leftover-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
    }

    private func file(_ path: String, bytes: Int = 1000) throws -> URL {
        let url = root.appendingPathComponent(path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(count: bytes).write(to: url)
        return url
    }

    private func exists(_ url: URL) -> Bool { FileManager.default.fileExists(atPath: url.path) }

    func testTheLaunchSweepEmptiesTheHandOnFoldersOnly() throws {
        let bundle = try file("share/BetterDriver-a.bdsession", bytes: 4000)
        let part = try file("share/BetterDriver-b.bdsession.part", bytes: 500)
        let vbo = try file("export/BetterDriver-a.vbo", bytes: 300)
        let folder = try file("export/BetterDriver-a-video/part1.mp4", bytes: 200)
        let video = try file("data-video/lap-3.mp4", bytes: 2000)
        let zip = try file("logs-out/BetterDriver-log.zip", bytes: 100)
        let other = try file("camera-test/part1.mp4", bytes: 50)
        let loose = try file("share-notes.txt", bytes: 10)

        XCTAssertEqual(LeftoverCopies.sweep(root: root), 7100)

        for gone in [bundle, part, vbo, folder.deletingLastPathComponent(), video, zip] {
            XCTAssertFalse(exists(gone), gone.lastPathComponent)
        }
        XCTAssertTrue(exists(other), "a folder that is not a hand-on folder stays")
        XCTAssertTrue(exists(loose))
        XCTAssertTrue(exists(root.appendingPathComponent("share")), "the folder itself stays for the next share")
        XCTAssertEqual(LeftoverCopies.sweep(root: root), 0)
    }

    func testAShareCopyGoesOnceHandedOnAndNothingOutsideTheTemporaryFolder() throws {
        let bundle = try file("share/BetterDriver-a.bdsession")
        let session = try file("sessions/a/video/part1.mp4")
        LeftoverCopies.discard([bundle, session], root: root.appendingPathComponent("share"))
        XCTAssertFalse(exists(bundle))
        XCTAssertTrue(exists(session), "the session's own video is never removed")
    }

    func testAnExportedHardLinkGoesAndTheSessionVideoStays() throws {
        let session = try file("sessions/a/video/part1.mp4", bytes: 5000)
        let link = root.appendingPathComponent("export/BetterDriver-a-part1.mp4")
        try FileManager.default.createDirectory(at: link.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.linkItem(at: session, to: link)
        LeftoverCopies.discard([link], root: root.appendingPathComponent("export"))
        XCTAssertFalse(exists(link))
        XCTAssertEqual(try Data(contentsOf: session).count, 5000)
    }

    func testOnlyIOSsInboxCopyOfAReceivedFileGoes() throws {
        let inbox = root.appendingPathComponent("Inbox", isDirectory: true)
        let received = try file("Inbox/BetterDriver-a.bdsession")
        let picked = try file("Picked/BetterDriver-b.bdsession")
        let lookalike = try file("Inbox2/BetterDriver-c.bdsession")
        LeftoverCopies.discardReceived(received, inbox: inbox)
        LeftoverCopies.discardReceived(picked, inbox: inbox)
        LeftoverCopies.discardReceived(lookalike, inbox: inbox)
        XCTAssertFalse(exists(received))
        XCTAssertTrue(exists(picked), "a file picked in Files is the person's own")
        XCTAssertTrue(exists(lookalike), "a folder whose name only starts the same is not the Inbox")
    }

    func testTheInboxIsTheAppsDocumentsInbox() {
        XCTAssertEqual(LeftoverCopies.inbox.lastPathComponent, "Inbox")
        XCTAssertEqual(LeftoverCopies.inbox.deletingLastPathComponent().lastPathComponent, "Documents")
    }
}
