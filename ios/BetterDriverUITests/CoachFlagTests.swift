import XCTest

/// "Coach this next time" (FBBD-363, Android's FBBD-148) on the iPad and iPhone simulators: a corner
/// flagged in Where time goes of the Jastrząb demo is stored for the spoken coach, so it is still
/// flagged when the Summary is opened again, and unflagging it is stored too. A screenshot of each
/// stays in the test results.
final class CoachFlagTests: XCTestCase {
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

    func testAFlaggedCornerIsKeptForTheCoachAndUnflagged() {
        app.launchArguments = ["-freshInstall", "-demoSessions"]
        app.launch()
        openSessions()
        // Newest first: Silesia Ring, then Jastrząb.
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 1).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "no summary")
        let flags = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'coachFlag-'"))
        let flag = flags.firstMatch
        scrollTo(flag)
        XCTAssertTrue(flag.label.hasPrefix("Coach "), "a corner is flagged on a fresh install")
        let id = flag.identifier
        XCTAssertTrue(app.staticTexts["Flag a corner and the spoken coach works on it first next time at this track."].exists)
        flag.tap()
        let flagged = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label BEGINSWITH 'Coaching '", id))
        XCTAssertTrue(flagged.firstMatch.waitForExistence(timeout: 30), "the corner was not flagged")
        keep("Where time goes, corner flagged")
        // Read again from the stored picks: away to Compare and back.
        reopenSummary()
        scrollTo(app.buttons[id].firstMatch)
        XCTAssertTrue(flagged.firstMatch.waitForExistence(timeout: 30), "the flag was not kept")
        app.buttons[id].firstMatch.tap()
        let unflagged = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label BEGINSWITH 'Coach '", id))
        XCTAssertTrue(unflagged.firstMatch.waitForExistence(timeout: 30), "the corner was not unflagged")
        reopenSummary()
        scrollTo(app.buttons[id].firstMatch)
        XCTAssertTrue(unflagged.firstMatch.waitForExistence(timeout: 30), "the unflag was not kept")
        keep("Where time goes, corner unflagged")
        XCTAssertEqual(app.state, .runningForeground)
    }

    private func reopenSummary() {
        app.buttons["tabCompare"].firstMatch.tap()
        sleep(1)
        app.buttons["tabSummary"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 30), "not back on the Summary")
    }

    /// Swipes the Summary up until [element] is on screen; Where time goes is below the lap times.
    private func scrollTo(_ element: XCUIElement) {
        let summary = app.descendants(matching: .any).matching(identifier: "summaryTab").firstMatch
        for _ in 0..<12 where !(element.exists && element.isHittable) {
            summary.swipeUp()
            sleep(1)
        }
        XCTAssertTrue(element.waitForExistence(timeout: 30), "no coach flag in Where time goes")
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
