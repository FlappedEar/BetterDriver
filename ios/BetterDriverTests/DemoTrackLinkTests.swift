import XCTest
import BetterDriverCore
@testable import BetterDriver

/// Review v1.34.1 F19 (FBBD-484): a Demo track link let go without stop(), as when the recorder ends
/// a recording itself (storage full, write error), stops its 25 Hz clock; stop() stops it too.
@MainActor
final class DemoTrackLinkTests: XCTestCase {
    private final class Sink: NSObject, SampleSink {
        func defineChannel(def: ChannelDef) {}
        func submit(set: SampleSet) {}
        func event(kind: String, text: String) {}
        func nowMicros() -> Int64 { 1_000_000 }
    }

    func testALinkLetGoStopsItsClock() throws {
        var link: DemoTrackLink? = try DemoTrackLink(sink: Sink())
        link?.start()
        let clock = try XCTUnwrap(link?.timer)
        XCTAssertTrue(clock.isValid)
        link = nil
        XCTAssertFalse(clock.isValid, "the run loop still ticks for a link that is gone")
    }

    func testStopStopsTheClock() throws {
        let link = try DemoTrackLink(sink: Sink())
        link.start()
        let clock = try XCTUnwrap(link.timer)
        link.stop()
        XCTAssertFalse(clock.isValid)
        XCTAssertNil(link.timer)
    }
}
