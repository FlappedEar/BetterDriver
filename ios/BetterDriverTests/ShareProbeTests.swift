import XCTest
import Darwin
import BetterDriverCore
@testable import BetterDriver

/// PROBE FBBD-393 (not for main): where the share of a long video grows the memory.
final class ShareProbeTests: XCTestCase {
    private let videoBytes: UInt64 = 400_000_000
    private var sessionId: String?

    override func tearDown() {
        if let sessionId { _ = ReviewBridge.delete(sessionId) }
    }

    func testProbe() throws {
        let id = try sessionWithVideo()
        let lib = ReviewBridge.library
        let mb = { (v: UInt64) -> Int in Int(v / 1_000_000) }
        print("PROBE start fp=\(mb(footprint()))")
        for run in 1...3 {
            var f0 = footprint()
            _ = try lib.probeTimedTrack(id: id, samples: false)
            print("PROBE run=\(run) read(samples=false) delta=\(Int64(footprint()) / 1_000_000 - Int64(f0) / 1_000_000)")
            f0 = footprint()
            _ = try lib.probeTimedTrack(id: id, samples: true)
            print("PROBE run=\(run) read(samples=true) delta=\(Int64(footprint()) / 1_000_000 - Int64(f0) / 1_000_000)")
            lib.probeCollect()
            print("PROBE run=\(run) after GC fp=\(mb(footprint()))")
        }
        for mode in ["plain", "gcBefore", "gcEvery50"] {
            for run in 1...5 {
                if mode != "plain" { lib.probeCollect() }
                let before = footprint()
                var peak = before
                var first: UInt64 = 0
                var nextLog: Int64 = 0
                let url = try ReviewBridge.share(id) { written, _ in
                    let fp = self.footprint()
                    if first == 0 { first = fp }
                    peak = max(peak, fp)
                    if written >= nextLog {
                        print("PROBE \(mode) run=\(run) written=\(written / 1_000_000) growth=\(Int64(fp) / 1_000_000 - Int64(before) / 1_000_000)")
                        nextLog += 50_000_000
                        if mode == "gcEvery50" { lib.probeCollect() }
                    }
                }
                try? FileManager.default.removeItem(at: url)
                print("PROBE \(mode) run=\(run) firstCallbackGrowth=\(Int64(first) / 1_000_000 - Int64(before) / 1_000_000) peakGrowth=\(mb(peak - before)) before=\(mb(before))")
            }
        }
    }

    private func sessionWithVideo() throws -> String {
        let demo = try XCTUnwrap(Bundle.main.resourceURL?.appendingPathComponent("demo"))
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
