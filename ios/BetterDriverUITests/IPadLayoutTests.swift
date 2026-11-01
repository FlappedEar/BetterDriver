import XCTest

/// The iPad's session page in portrait and landscape (Arek, TestFlight 1441): Summary's Also column
/// and the Map tab's G-G card keep a readable width, the session list stays beside the session as
/// on an Android tablet held sideways and can be hidden and shown again (Arek 2026-10-31), and
/// Settings is a tab. Screenshots of each stay in the test results.
final class IPadLayoutTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        addUIInterruptionMonitor(withDescription: "Permission prompt") { alert in
            let allow = alert.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Allow'")).firstMatch
            (allow.exists ? allow : alert.buttons.firstMatch).tap()
            return true
        }
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
    }

    func testTheSessionPageInPortrait() throws {
        try check(.portrait, "portrait")
    }

    func testTheSessionPageInLandscape() throws {
        try check(.landscapeLeft, "landscape")
    }

    private func check(_ orientation: UIDeviceOrientation, _ name: String) throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .phone, "the iPad's layout")
        XCUIDevice.shared.orientation = orientation
        app.launchArguments = ["-freshInstall", "-demoSessions"]
        app.launch()
        openSessions()
        // Newest first: Silesia Ring, whose focus has a primary corner and others under Also.
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "no summary")

        // One line, not one letter per line down the screen.
        let also = app.staticTexts["ALSO"].firstMatch
        XCTAssertTrue(also.waitForExistence(timeout: 10), "no Also in the focus card")
        XCTAssertLessThan(also.frame.height, 40, "Also wraps letter by letter (\(also.frame))")
        XCTAssertGreaterThan(also.frame.width, also.frame.height, "Also is a column of letters (\(also.frame))")
        keep("Summary, \(name)")

        app.buttons["tabMap"].firstMatch.tap()
        let gg = app.descendants(matching: .any).matching(identifier: "lapGgChart").firstMatch
        XCTAssertTrue(gg.waitForExistence(timeout: 30), "no G-G")
        XCTAssertGreaterThanOrEqual(gg.frame.width, 200, "the G-G card is too narrow (\(gg.frame))")
        keep("Map, \(name)")

        // As an Android tablet held sideways: the list stays beside the session, with Import session
        // over it, and the list button hides it, so the session takes its width, and shows it again
        // (Arek 2026-10-31: the list was stuck beside the session).
        let add = app.buttons["importSessions"].firstMatch
        let next = rows.element(boundBy: 1)
        let hittable = NSPredicate(format: "isHittable == true")
        expectation(for: hittable, evaluatedWith: add)
        expectation(for: hittable, evaluatedWith: next)
        waitForExpectations(timeout: 15)
        let toggle = app.buttons["toggleSessionList"].firstMatch
        XCTAssertTrue(toggle.waitForExistence(timeout: 10), "no list button over the session")
        let besideX = toggle.frame.minX
        toggle.tap()
        // Gone or covered: a hidden column's rows may leave the tree, where isHittable would fail.
        for _ in 0..<30 where next.exists && next.isHittable {
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        XCTAssertFalse(next.exists && next.isHittable, "the list is still beside the session")
        XCTAssertTrue(gg.exists, "the session closed with the list")
        XCTAssertLessThan(toggle.frame.minX, besideX - 200, "the session did not take the list's width")
        keep("Session without the list, \(name)")
        toggle.tap()
        expectation(for: hittable, evaluatedWith: next)
        waitForExpectations(timeout: 15)
        XCTAssertFalse(app.buttons["Drive"].exists, "a Drive tab on the iPad")
        // Android's row: time and track, then the laps and the rest from the shared core.
        let row = rows.element(boundBy: 0)
        let detailed = NSPredicate(format: "label CONTAINS ' laps'")
        expectation(for: detailed, evaluatedWith: row)
        waitForExpectations(timeout: 60)
        XCTAssertTrue(row.label.localizedCaseInsensitiveContains("silesia"), "no track in the row: \(row.label)")
        XCTAssertTrue(app.buttons["sessionActions"].firstMatch.exists, "no ⋮ on the rows")
        keep("Sessions beside the session, \(name)")
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "the next session did not open")
        let settings = app.tabBars.buttons["Settings"].exists ? app.tabBars.buttons["Settings"] : app.buttons["Settings"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 10), "no Settings tab")
        settings.tap()
        // Only review on a tablet (ReviewOnlyDevice, Arek 2026-11-01): no Car tab with live OBD-II values,
        // and none of what sets up a recording in Settings.
        XCTAssertTrue(app.buttons["settings.diagnostics"].firstMatch.waitForExistence(timeout: 10), "no Diagnostics")
        XCTAssertTrue(app.buttons["settings.cars"].firstMatch.exists, "no Cars")
        for topic in ["equipment", "trackScreen", "video", "coach"] {
            XCTAssertFalse(app.buttons["settings.\(topic)"].firstMatch.exists, "\(topic) in the iPad's Settings")
        }
        XCTAssertFalse(app.buttons["Car"].exists, "a Car tab on the iPad")
        keep("Settings, \(name)")
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
        attachment.name = "iPad \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
