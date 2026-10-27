import XCTest
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

    func testTheShareZipHoldsTheLog() throws {
        let log = LogWriter(dir: dir, echo: false)
        log.write("I", "A", "for the zip")
        let zip = try LogShare.prepare(log: log)
        let size = try zip.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        XCTAssertEqual(zip.pathExtension, "zip")
        XCTAssertGreaterThan(size, 0)
    }
}
