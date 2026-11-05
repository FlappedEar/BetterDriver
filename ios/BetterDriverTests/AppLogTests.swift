import XCTest
import BetterDriverCore
@testable import BetterDriver

/// The iOS log writes Android's line format (LogLine.kt), keeps lines in order, clears on request and
/// turns a signal's note from the previous run into a Crash line (FBBD-339).
final class AppLogTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
    }

    private func lines(_ log: LogWriter) throws -> [String] {
        log.flush()
        return try String(contentsOf: log.file, encoding: .utf8).split(separator: "\n").map(String.init)
    }

    func testALineHasAndroidsFormat() {
        let line = LogLineFormat.line(
            date: Date(timeIntervalSince1970: 1_760_272_568.593), uptimeMicros: 4_711_204_518, pid: 2646,
            thread: "log writer", level: "I", tag: "Recording", session: "20261012-123607-3713ff1a", message: "hello"
        )
        let pattern = #"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.593[+-]\d\d:\d\d 4711\.204518 main 2646 log_writer I Recording \[20261012-123607-3713ff1a\]: hello$"#
        XCTAssertNotNil(line.range(of: pattern, options: .regularExpression), line)
    }

    func testLinesAreKeptInOrderWithTheSession() throws {
        let log = LogWriter(dir: dir, echo: false)
        log.write("I", "A", "one")
        log.session = "s1"
        log.write("W", "B", "two")
        log.session = nil
        log.writeNow("E", "Crash", "three")
        let all = try lines(log)
        XCTAssertEqual(all.count, 3)
        XCTAssertTrue(all[0].hasSuffix(" I A [-]: one"), all[0])
        XCTAssertTrue(all[1].hasSuffix(" W B [s1]: two"), all[1])
        XCTAssertTrue(all[2].hasSuffix(" E Crash [-]: three"), all[2])
    }

    func testClearStartsTheLogAgain() throws {
        let log = LogWriter(dir: dir, echo: false)
        log.write("I", "A", "old")
        log.flush()
        XCTAssertGreaterThan(log.bytes(), 0)
        log.clear()
        let all = try lines(log)
        XCTAssertEqual(all.count, 1)
        XCTAssertTrue(all[0].hasSuffix("log cleared"), all[0])
    }

    func testASignalNoteBecomesACrashLine() throws {
        let log = LogWriter(dir: dir, echo: false)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let note = dir.appendingPathComponent("crash.txt")
        try "signal 11\n0 BetterDriver 0x1 frame\n".write(to: note, atomically: true, encoding: .utf8)
        CrashLog.reportPrevious(note: note, log: log)
        log.flush()
        let text = try String(contentsOf: log.file, encoding: .utf8)
        XCTAssertTrue(text.contains("E Crash [-]: the previous run ended by SIGSEGV (11)"), text)
        XCTAssertTrue(text.contains("0 BetterDriver 0x1 frame"), text)
        XCTAssertFalse(FileManager.default.fileExists(atPath: note.path))
    }

    func testTheSizeShowsKilobytesUnderAMegabyte() {
        XCTAssertNil(DiagnosticsPage.sizeLabel(nil))
        XCTAssertEqual(DiagnosticsPage.sizeLabel(0), "Empty")
        XCTAssertEqual(DiagnosticsPage.sizeLabel(120), "1 KB")
        XCTAssertEqual(DiagnosticsPage.sizeLabel(16_469), "16 KB")
        XCTAssertEqual(DiagnosticsPage.sizeLabel(999_400), "999 KB")
        XCTAssertEqual(DiagnosticsPage.sizeLabel(1_282_866), "1.3 MB")
    }

    func testTheShareZipHoldsTheLog() throws {
        let log = LogWriter(dir: dir, echo: false)
        log.write("I", "A", "for the zip")
        let zip = try LogShare.prepare(log: log)
        let size = try zip.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        XCTAssertEqual(zip.pathExtension, "zip")
        XCTAssertGreaterThan(size, 0)
        // What Arek sends after a drive (FBBD-396): a zip keeps its file names readable in its
        // directory, so each file is named in it.
        let bytes = try Data(contentsOf: zip)
        XCTAssertEqual(bytes.prefix(2), Data("PK".utf8), "not a zip")
        for name in ["device.txt", "oslog.txt", log.file.lastPathComponent] {
            XCTAssertNotNil(bytes.range(of: Data(name.utf8)), "no \(name) in the zip")
        }
    }

    /// The load line (FBBD-281): the process's counters read, and two samples a minute apart make the
    /// shared LoadLine Android's log checks read.
    func testTheLoadLineReadsTheProcess() {
        let a = ProcessLoad.sample()
        var x = 0.0
        for i in 0..<2_000_000 { x += Double(i).squareRoot() }
        XCTAssertGreaterThan(x, 0)
        let b = ProcessLoad.sample()
        XCTAssertGreaterThan(b.heapUsedBytes, 0)
        XCTAssertGreaterThanOrEqual(b.heapMaxBytes, b.heapUsedBytes)
        XCTAssertGreaterThan(b.threads, 0)
        XCTAssertGreaterThanOrEqual(b.cpuMillis, a.cpuMillis)
        let later = LoadSample(
            uptimeMillis: a.uptimeMillis + 60_000, cpuMillis: a.cpuMillis + 6_000, gcCount: 0, gcMillis: 0,
            allocatedBytes: 0, heapUsedBytes: b.heapUsedBytes, heapMaxBytes: b.heapMaxBytes, threads: b.threads
        )
        let line = LoadLine.companion.between(from: a, to: later)
        XCTAssertEqual(line?.text().hasPrefix("load: cpu_pct=10.0 cpu_ms=6000 wall_ms=60000 gc=0"), true, line?.text() ?? "nil")
    }

    // FBBD-430: the background line names files the app holds open and which carry a lock.
    func testOpenFilesFindsAHeldLock() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let lockPath = dir.appendingPathComponent("held.lock").path
        let freePath = dir.appendingPathComponent("free.txt").path
        let held = open(lockPath, O_WRONLY | O_CREAT, 0o644)
        let free = open(freePath, O_WRONLY | O_CREAT, 0o644)
        defer { close(held); close(free) }
        XCTAssertEqual(flock(held, LOCK_EX), 0)
        let entries = OpenFiles.list()
        let lock = entries.first { $0.path.hasSuffix("/held.lock") }
        let plain = entries.first { $0.path.hasSuffix("/free.txt") }
        XCTAssertEqual(lock?.locked, true)
        XCTAssertEqual(plain?.locked, false)
        // The probe let go of free.txt: a lock on it is still free to take.
        XCTAssertEqual(flock(free, LOCK_EX | LOCK_NB), 0)
        XCTAssertEqual(OpenFiles.list(probe: false).first { $0.path.hasSuffix("/held.lock") }?.locked, false)
    }

    func testOpenFilesLineHidesTheHomeAndPutsLocksFirst() {
        let line = OpenFiles.line(
            [OpenFiles.Entry(path: "/home/u/Library/a.log", locked: false),
             OpenFiles.Entry(path: "/home/u/Library/b.lock", locked: true),
             OpenFiles.Entry(path: "/tmp/c", locked: false)],
            home: "/home/u"
        )
        XCTAssertEqual(line, "3 open files, 1 locked: ~/Library/b.lock (locked), ~/Library/a.log, /tmp/c")
    }
    /// The share sheet's result goes in the log, so a drive log says whether the AirDrop went through.
    func testShareResultLine() {
        XCTAssertEqual(
            LogShare.resultLine(name: "a.bdsession", activity: "com.apple.UIKit.activity.AirDrop",
                                completed: true, error: nil, seconds: 937.4),
            "share of a.bdsession completed, to AirDrop, after 937 s")
        XCTAssertEqual(
            LogShare.resultLine(name: "a.zip", activity: nil, completed: false, error: nil, seconds: 3),
            "share of a.zip cancelled, to none, after 3 s")
        let error = NSError(domain: "t", code: 1, userInfo: [NSLocalizedDescriptionKey: "no room"])
        XCTAssertEqual(
            LogShare.resultLine(name: "a.vbo", activity: "com.apple.DocumentManagerUICore.SaveToFiles",
                                completed: false, error: error, seconds: 12),
            "share of a.vbo failed: no room, to com.apple.DocumentManagerUICore.SaveToFiles, after 12 s")
    }
}
