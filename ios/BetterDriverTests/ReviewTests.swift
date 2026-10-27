import XCTest
import UniformTypeIdentifiers
import BetterDriverCore
@testable import BetterDriver

/// The iPad review's Swift side (FBBD-331): the tabs as on Android and the session file type.
final class ReviewTests: XCTestCase {
    func testSixTabsAsOnAndroidSummaryFirst() {
        XCTAssertEqual(SessionTab.allCases.map(\.rawValue), ["Summary", "Compare", "Map", "Laps", "Corners", "Car"])
    }

    func testSessionFileTypeIsDeclared() {
        XCTAssertEqual(UTType(filenameExtension: "bdsession"), .bdsession)
    }

    func testAMissingSessionIsAnErrorNotACrash() {
        XCTAssertThrowsError(try ReviewBridge.summary(id: "no-such-session"))
    }

    func testANonBundleIsRefused() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("not-a-bundle.bdsession")
        try Data("hello".utf8).write(to: url)
        XCTAssertThrowsError(try ReviewBridge.receive(url) { _, _ in })
    }

    func testTheMapIsNorthUpOnOneScale() {
        let dots = [MapDot(east: 0, north: 0, pedal: nil), MapDot(east: 100, north: 50, pedal: .braking)]
        let frame = MapFrame(dots: dots, size: CGSize(width: 300, height: 300), padding: 0, margin: 0)
        let a = frame.point(dots[0]), b = frame.point(dots[1])
        XCTAssertEqual(b.x - a.x, 300, accuracy: 1e-9)
        XCTAssertEqual(a.y - b.y, 150, accuracy: 1e-9)
        XCTAssertEqual(LineColors.pedal(.braking), LineColors.loss)
    }
}
