import XCTest
import Darwin
import BetterDriverCore
@testable import BetterDriver

/// Share and import of a session with a long video (FBBD-393): on an iPhone 12 the share of a session
/// with video filled the memory until iOS killed the app, as every 64 KB piece of the video read stayed
/// in memory until the copy ended. The copy must stay flat however large the video.
final class ShareTests: XCTestCase {
    /// The video: larger than the growth allowed, so a copy that keeps its pieces fails.
    private let videoBytes: UInt64 = 400_000_000
    /// What the copy may add to the app's memory.
    private let allowedGrowth: UInt64 = 120_000_000

    private var sessionId: String?
    private var files: [URL] = []

    override func tearDown() {
        if let sessionId { _ = ReviewBridge.delete(sessionId) }
        for file in files { try? FileManager.default.removeItem(at: file) }
    }

    func testSharingAndImportingASessionWithALongVideoKeepsMemoryFlat() throws {
        let id = try sessionWithVideo()
        let before = footprint()
        var peak = before
        var last: Int64 = 0
        let url = try ReviewBridge.share(id) { written, total in
            XCTAssertGreaterThanOrEqual(written, last)
            XCTAssertLessThanOrEqual(written, total)
            last = written
            peak = max(peak, self.footprint())
        }
        files.append(url)
        XCTAssertGreaterThan(last, Int64(videoBytes), "the video was not in the bundle")
        let size = try XCTUnwrap(try url.resourceValues(forKeys: [.fileSizeKey]).fileSize)
        XCTAssertGreaterThan(UInt64(size), videoBytes)
        XCTAssertLessThan(peak - before, allowedGrowth, "sharing grew the memory by \((peak - before) / 1_000_000) MB")

        // The same bundle read back, as AirDrop or Files hands it to the app.
        let start = footprint()
        var importPeak = start
        let received = try ReviewBridge.receive(url) { _, _ in importPeak = max(importPeak, self.footprint()) }
        ReviewBridge.discard(received)
        XCTAssertLessThan(importPeak - start, allowedGrowth, "importing grew the memory by \((importPeak - start) / 1_000_000) MB")
    }

    /// The Jastrząb demo session with a long (sparse, so quick to make) phone video in it.
    private func sessionWithVideo() throws -> String {
        let demo = try XCTUnwrap(Bundle.main.resourceURL?.appendingPathComponent("demo"))
        // A start of its own, so it is never the session another test made.
        let start = Int64(Date().timeIntervalSince1970 * 1000)
        let id = try ReviewBridge.library.addDemo(
            replayPath: demo.appendingPathComponent("jastrzab.replay").path,
            trackPath: demo.appendingPathComponent("jastrzab.track.json").path,
            startUtcMillis: start
        )
        sessionId = id
        let dir = URL(fileURLWithPath: ReviewBridge.library.sessionsRoot)
            .appendingPathComponent(id).appendingPathComponent("video", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let video = dir.appendingPathComponent("phone_0001.mp4")
        XCTAssertTrue(FileManager.default.createFile(atPath: video.path, contents: nil))
        let handle = try FileHandle(forWritingTo: video)
        try handle.truncate(atOffset: videoBytes)
        try handle.close()
        return id
    }

    /// The app's memory as iOS counts it against its limit (phys_footprint).
    private func footprint() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? info.phys_footprint : 0
    }
}
