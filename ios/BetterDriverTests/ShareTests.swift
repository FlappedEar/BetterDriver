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
    /// What the copy may add to the app's memory while it streams the video.
    private let allowedGrowth: UInt64 = 120_000_000
    /// Bytes copied before the streaming part is measured: the share's and the import's one-time
    /// work (the session's events and its track read, as FBBD-393's fix left it) is done by then,
    /// and over 250 MB of the video is still to come.
    private let settled: Int64 = 150_000_000
    /// What the whole share or import may add, one-time work included (FBBD-465): before, a long
    /// session's samples read whole for its track took hundreds of MB on an iPhone.
    private let allowedTotal: UInt64 = 250_000_000

    private var sessionId: String?
    private var files: [URL] = []

    override func tearDown() {
        if let sessionId { _ = ReviewBridge.delete(sessionId) }
        for file in files { try? FileManager.default.removeItem(at: file) }
    }

    /// FBBD-465: the footprint is the whole test host's, and the Kotlin core's collector frees the
    /// one-time work's garbage when it runs, not when the copy starts. Measured from the start only,
    /// the check failed 4 of about 15 CI runs at 122-141 MB, peaking 45-89 MB into the copy, on
    /// GitHub's and the Mac mini's runners alike. A copy that keeps its pieces grows with every
    /// piece: measured from [settled] on, it adds over 250 MB and still fails.
    func testSharingAndImportingASessionWithALongVideoKeepsMemoryFlat() throws {
        let id = try sessionWithVideo()
        // Earlier tests' garbage goes first: the share is measured, not when the core's collector runs.
        CoreMemory.shared.collect()
        var share = Growth(start: footprint(), settled: settled)
        var last: Int64 = 0
        let url = try ReviewBridge.share(id) { written, total in
            XCTAssertGreaterThanOrEqual(written, last)
            XCTAssertLessThanOrEqual(written, total)
            last = written
            share.add(written, self.footprint(), self.breakdown)
        }
        files.append(url)
        print("SHARE memory (FBBD-465): \(share.trace)")
        XCTAssertGreaterThan(last, Int64(videoBytes), "the video was not in the bundle")
        let size = try XCTUnwrap(try url.resourceValues(forKeys: [.fileSizeKey]).fileSize)
        XCTAssertGreaterThan(UInt64(size), videoBytes)
        XCTAssertLessThan(share.streaming, allowedGrowth, "sharing grew the memory while copying the video: \(share.trace)")
        XCTAssertLessThan(share.total, allowedTotal, "sharing grew the memory: \(share.trace)")

        // The same bundle read back, as AirDrop or Files hands it to the app.
        CoreMemory.shared.collect()
        var receive = Growth(start: footprint(), settled: settled)
        let received = try ReviewBridge.receive(url) { read, _ in receive.add(read, self.footprint(), self.breakdown) }
        ReviewBridge.discard(received)
        print("IMPORT memory (FBBD-465): \(receive.trace)")
        XCTAssertLessThan(receive.streaming, allowedGrowth, "importing grew the memory while copying the video: \(receive.trace)")
        XCTAssertLessThan(receive.total, allowedTotal, "importing grew the memory: \(receive.trace)")
    }

    /// The footprint over a copy: its growth from the start, and from the first piece past [settled].
    private struct Growth {
        let start: UInt64
        let settled: Int64
        private var peak: UInt64 = 0
        private var base: UInt64?
        private var streamingPeak: UInt64 = 0
        private var nextMark: Int64 = 0
        private var marks: [String] = []
        private var atPeak = ""

        init(start: UInt64, settled: Int64) {
            self.start = start
            self.settled = settled
            peak = start
        }

        var total: UInt64 { peak > start ? peak - start : 0 }
        var streaming: UInt64 { base.map { streamingPeak > $0 ? streamingPeak - $0 : 0 } ?? 0 }

        /// What the failure says: the footprint every 50 MB copied, and where it peaked.
        var trace: String {
            let mb = { (v: UInt64) in v / 1_000_000 }
            return "start \(mb(start)) MB, grew \(mb(total)) MB in all (peak \(atPeak)), " +
                "\(mb(streaming)) MB after \(settled / 1_000_000) MB copied; footprint by MB copied: " +
                marks.joined(separator: ", ")
        }

        mutating func add(_ copied: Int64, _ now: UInt64, _ breakdown: () -> String) {
            if now > peak {
                peak = now
                atPeak = "at \(copied / 1_000_000) MB copied: \(breakdown())"
            }
            if copied >= settled {
                if base == nil { base = now }
                streamingPeak = max(streamingPeak, now)
            }
            if copied >= nextMark {
                marks.append("\(copied / 1_000_000):\(now / 1_000_000)")
                nextMark += 50_000_000
            }
        }
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

    /// What the footprint is made of, for a failure to say where the memory went.
    private func breakdown() -> String {
        let info = vmInfo()
        let mb = { (v: UInt64) in v / 1_000_000 }
        return "footprint \(mb(info.phys_footprint)) MB: internal \(mb(info.internal)), compressed \(mb(info.compressed)), " +
            "external \(mb(info.external)), resident \(mb(info.resident_size))"
    }

    /// The app's memory as iOS counts it against its limit (phys_footprint).
    private func footprint() -> UInt64 { vmInfo().phys_footprint }

    private func vmInfo() -> task_vm_info_data_t {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? info : task_vm_info_data_t()
    }
}
