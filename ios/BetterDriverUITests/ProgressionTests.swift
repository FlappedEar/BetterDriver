import XCTest

/// Progression at a track (FBBD-181) on the iPad and iPhone simulators, from the More menu of a
/// session, as Android opens it: two demo sessions at Jastrząb make it, the Silesia Ring session
/// takes no part. A screenshot stays in the test results.
final class ProgressionTests: XCTestCase {
    private let app = XCUIApplication()
    private var device: String { UIDevice.current.userInterfaceIdiom == .phone ? "iPhone" : "iPad" }

    override func setUp() {
        continueAfterFailure = false
        // A permission prompt (Bluetooth, location) on a fresh install is answered, not left in the way.
        addUIInterruptionMonitor(withDescription: "Permission prompt") { alert in
            let allow = alert.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Allow'")).firstMatch
            (allow.exists ? allow : alert.buttons.firstMatch).tap()
            return true
        }
    }

    func testTheProgressionAtASessionsTrackOpensFromItsMoreMenu() {
        // Two Jastrząb sessions an hour apart, and Silesia Ring.
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoTrackDay"]
        app.launch()
        openSessions()
        // Newest first: Silesia Ring, then the two Jastrząb sessions.
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 1).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "no summary")
        let more = app.buttons["sessionMore"].firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 10), "no More menu")
        more.tap()
        let open = app.buttons["Progression at this track"].firstMatch
        XCTAssertTrue(open.waitForExistence(timeout: 10), "no Progression in the More menu")
        open.tap()
        XCTAssertFalse(app.staticTexts["Not implemented yet"].exists, "still the placeholder")
        // Each session at the track is analysed off the main thread. On a busy CI simulator a query
        // made meanwhile can time out, so ask every 5 s instead of every second, for up to 3 minutes.
        let both = app.staticTexts["2 sessions on 1 day"].firstMatch
        var shown = false
        for _ in 0..<36 where !shown {
            sleep(5)
            shown = both.exists
        }
        XCTAssertTrue(shown, "no progression of the two Jastrząb sessions")
        // SectionTitle writes its heading in capitals.
        XCTAssertTrue(app.staticTexts["LAPS"].exists, "no Laps card")
        keep("Progression")
        app.swipeUp()
        keep("Progression, sessions")
        XCTAssertEqual(app.state, .runningForeground)
        app.buttons["Done"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 30), "not back on the session")
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
