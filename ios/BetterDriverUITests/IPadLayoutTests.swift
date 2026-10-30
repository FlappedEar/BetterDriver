import XCTest

/// The iPad's session page in portrait and landscape (Arek, TestFlight 1441): Summary's Also column
/// and the Map tab's G-G card keep a readable width, the session list stays beside the session as
/// on an Android tablet, and Settings is a tab. Screenshots of each stay in the test results.
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

        // As an Android tablet: the list stays beside the session, with Import session over it, and
        // another session opens from it. Settings is a tab, with the app's log (no Drive: no recording).
        let add = app.buttons["importSessions"].firstMatch
        let hittable = NSPredicate(format: "isHittable == true")
        expectation(for: hittable, evaluatedWith: add)
        expectation(for: hittable, evaluatedWith: rows.element(boundBy: 1))
        waitForExpectations(timeout: 15)
        XCTAssertFalse(app.buttons["Drive"].exists, "a Drive tab on the iPad")
        keep("Sessions beside the session, \(name)")
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "the next session did not open")
        let settings = app.tabBars.buttons["Settings"].exists ? app.tabBars.buttons["Settings"] : app.buttons["Settings"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 10), "no Settings tab")
        settings.tap()
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
