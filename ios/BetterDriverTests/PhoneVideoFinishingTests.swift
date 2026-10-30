import XCTest
@testable import BetterDriver

/// Audit 30 Oct BD-01 and BD-02: the phone camera's frame counts and its MP4s still being finished.
final class PhoneVideoFinishingTests: XCTestCase {
    /// A writer that is not ready (overloaded) gets no frame and no video_sync; the drop is counted.
    func testAFrameTheWriterIsNotReadyForIsDroppedAndNotSynced() {
        var counts = PhoneFrameCounts()
        var appends = 0
        let append: () -> Bool = {
            appends += 1
            return true
        }
        XCTAssertEqual(counts.frame(ready: true, append: append), .appended)
        XCTAssertEqual(counts.frame(ready: false, append: append), .dropped)
        XCTAssertEqual(counts.frame(ready: false, append: append), .dropped)
        XCTAssertEqual(counts.frame(ready: true, append: { false }), .failed)
        XCTAssertEqual(appends, 1, "nothing is appended while the writer is not ready")
        XCTAssertEqual(counts, PhoneFrameCounts(captured: 4, appended: 1, dropped: 2, late: 0))
        XCTAssertEqual(counts.text, "captured=4 appended=1 dropped=2 late=0")
    }

    /// The session's video is not offered while a file finishes; its last file posts a session change.
    func testASessionIsBusyUntilItsLastFileFinishes() {
        let finishing = PhoneVideoFinishing()
        let changed = expectation(forNotification: .sessionsChanged, object: nil)
        finishing.begin(sessionId: "a")
        finishing.begin(sessionId: "a")
        finishing.begin(sessionId: "b")
        XCTAssertTrue(finishing.busy(sessionId: "a"))
        XCTAssertFalse(finishing.end(sessionId: "a"))
        XCTAssertTrue(finishing.busy(sessionId: "a"))
        XCTAssertTrue(finishing.end(sessionId: "a"))
        XCTAssertFalse(finishing.busy(sessionId: "a"))
        XCTAssertTrue(finishing.busy(sessionId: "b"))
        wait(for: [changed], timeout: 5)
        XCTAssertFalse(finishing.busy(sessionId: "never"))
    }
}
