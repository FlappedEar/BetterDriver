import XCTest

/// The Map tab's Laps view (FBBD-378, Android's FBBD-149) on the iPad and iPhone simulators: the
/// compared lap on the map coloured by time against the best lap, with its G-G diagram, and the
/// switch to "Where to find time". A screenshot of each stays in the test results.
final class MapLapsTests: XCTestCase {
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

    func testTheMapTabOpensOnTheLapColouredAgainstTheBestLap() {
        app.launchArguments = ["-freshInstall", "-demoSessions"]
        app.launch()
        openSessions()
        // Newest first: Silesia Ring, then Jastrząb.
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 1).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "no summary")
        app.buttons["tabMap"].firstMatch.tap()
        let map = app.descendants(matching: .any).matching(identifier: "lapLineMap").firstMatch
        XCTAssertTrue(map.waitForExistence(timeout: 30), "no lap on the map")
        // Compare opens on the second fastest lap, so the map shows it against the best lap.
        XCTAssertTrue(app.staticTexts[" vs best lap "].exists || app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'vs best lap'")).firstMatch.exists, "not against the best lap")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Gaining'")).firstMatch.exists,
                      "no time vs best legend")
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "lapGgChart").firstMatch.exists, "no G-G")
        keep("Map, laps")
        app.buttons["Speed"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["slow to fast"].waitForExistence(timeout: 10), "no speed legend")
        keep("Map, laps by speed")
        app.buttons["mapViewLine"].firstMatch.tap()
        XCTAssertFalse(map.exists, "still the laps view")
        keep("Map, where to find time")
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
