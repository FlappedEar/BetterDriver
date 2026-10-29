import XCTest

/// Compare laps from any sessions (FBBD-379, Android's FBBD-92) on the iPad and iPhone simulators:
/// Sessions' Compare laps lists the sessions at a track with their laps; two laps picked into slots
/// A and B open side by side in the lap review. A screenshot of each stays in the test results.
final class LapPickerTests: XCTestCase {
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

    func testCompareLapsPicksTwoLapsAtATrackAndOpensTheLapReview() {
        // Two Jastrząb sessions an hour apart, and Silesia Ring.
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoTrackDay"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 2).waitForExistence(timeout: 120), "no demo sessions")
        let open = app.buttons["compareLaps"].firstMatch
        XCTAssertTrue(open.waitForExistence(timeout: 10), "no Compare laps in Sessions")
        open.tap()
        let laps = app.buttons.matching(identifier: "pickerLap")
        // Every session is replayed into laps off the main thread: a busy CI simulator takes a while.
        XCTAssertTrue(laps.firstMatch.waitForExistence(timeout: 180), "no laps to pick")
        // Silesia Ring was driven last, so it shows first; Jastrząb has the two sessions.
        let jastrzab = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Jastrz'")).firstMatch
        XCTAssertTrue(jastrzab.waitForExistence(timeout: 10), "no track chips")
        jastrzab.tap()
        XCTAssertTrue(laps.element(boundBy: 2).waitForExistence(timeout: 10), "no Jastrząb laps")
        XCTAssertFalse(app.buttons["lapPickerCompare"].firstMatch.isEnabled, "Compare before two laps")
        laps.element(boundBy: 2).tap()
        app.swipeUp()
        app.swipeUp()
        let shown = laps.allElementsBoundByIndex.filter { $0.isHittable }
        XCTAssertTrue(shown.count > 1, "no lap to pick for B")
        shown[shown.count - 1].tap()
        keep("Compare laps, two picked")
        app.swipeDown()
        app.swipeDown()
        let compare = app.buttons["lapPickerCompare"].firstMatch
        XCTAssertTrue(compare.waitForExistence(timeout: 10) && compare.isEnabled, "Compare is off with two laps picked")
        compare.tap()
        let review = app.descendants(matching: .any).matching(identifier: "lapVideo").firstMatch
        XCTAssertTrue(review.waitForExistence(timeout: 60), "no lap review of the two laps")
        keep("Compare laps, lap review")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["lapPickerDone"].firstMatch.tap()
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 30), "not back on Sessions")
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
