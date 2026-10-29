import XCTest

/// Every pass through a corner (FBBD-362, Android's FBBD-93/FBBD-153) on the iPad and iPhone
/// simulators: from the Corners tab the Jastrząb demo's first corner lists every counted lap's pass
/// with the traces over the corner, and two ticked passes open side by side in the lap review.
/// A screenshot of each stays in the test results.
final class EveryPassTests: XCTestCase {
    private let app = XCUIApplication()
    private var device: String { UIDevice.current.userInterfaceIdiom == .phone ? "iPhone" : "iPad" }

    override func setUp() {
        continueAfterFailure = false
        addUIInterruptionMonitor(withDescription: "Permission prompt") { alert in
            let allow = alert.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Allow'")).firstMatch
            (allow.exists ? allow : alert.buttons.firstMatch).tap()
            return true
        }
    }

    func testEveryPassThroughACornerOpensFromTheCornersTabAndComparesTwoLaps() {
        app.launchArguments = ["-freshInstall", "-demoSessions"]
        app.launch()
        openSessions()
        // Newest first: Silesia Ring, then Jastrząb.
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 1).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "no summary")
        app.buttons["tabCorners"].firstMatch.tap()
        let open = app.buttons["everyPass"].firstMatch
        XCTAssertTrue(open.waitForExistence(timeout: 30), "no Every pass on the Corners tab")
        open.tap()
        let header = app.descendants(matching: .any).matching(identifier: "everyPassHeader").firstMatch
        XCTAssertTrue(header.waitForExistence(timeout: 120), "no Every pass screen")
        let count = app.staticTexts.matching(NSPredicate(format: "label ENDSWITH 'fastest through the corner first.'"))
        XCTAssertTrue(count.firstMatch.waitForExistence(timeout: 10), "no counted laps through the corner")
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "cornerTraces").firstMatch.exists, "no traces")
        keep("Every pass")
        // Tick the two fastest passes and compare them in the lap review.
        // Each pass is a button: by its identifier, or by its "Lap N" label should a container's win.
        let passes = app.buttons.matching(NSPredicate(format: "identifier == 'passRow' OR label BEGINSWITH 'Lap '"))
        XCTAssertTrue(passes.element(boundBy: 1).waitForExistence(timeout: 10), "fewer than two passes")
        passes.element(boundBy: 0).tap()
        passes.element(boundBy: 1).tap()
        let compare = app.buttons["everyPassCompare"].firstMatch
        XCTAssertTrue(compare.waitForExistence(timeout: 10), "no Compare")
        XCTAssertTrue(compare.isEnabled, "Compare is off with two laps ticked")
        keep("Every pass, two ticked")
        compare.tap()
        let review = app.descendants(matching: .any).matching(identifier: "lapVideo").firstMatch
        XCTAssertTrue(review.waitForExistence(timeout: 120), "no lap review of the two passes")
        keep("Every pass, lap review")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(header.waitForExistence(timeout: 30), "not back on Every pass")
        app.buttons["everyPassDone"].firstMatch.tap()
        XCTAssertTrue(app.buttons["tabCorners"].firstMatch.waitForExistence(timeout: 30), "not back on the session")
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// The Sessions tab: in the tab bar at the bottom, or on an iPad with iOS 18 in the bar at the top.
    private func openSessions() {
        let bottom = app.tabBars.buttons["Sessions"]
        if bottom.waitForExistence(timeout: 5) { bottom.tap(); return }
        let top = app.buttons["Sessions"].firstMatch
        if top.waitForExistence(timeout: 5) { top.tap() }
    }

    private func keep(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "\(device) \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
