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
}
