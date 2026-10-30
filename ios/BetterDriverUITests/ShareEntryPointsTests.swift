import XCTest

/// Every way a file leaves the app on iOS (FBBD-393): a session's Share, Settings › Diagnostics ›
/// Share log, and the session's exports. Each must reach its sheet with the app still running: on an
/// iPhone 12 the Share of a session with video showed nothing and then iOS killed the app. Video
/// with data's Save is DataVideoTests'; CSV and GPX are ReviewScreensTests'. The memory of a long
/// video's share is ShareTests' (unit tests): the simulator demo video is only ten seconds.
final class ShareEntryPointsTests: XCTestCase {
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

    /// A share sheet or Files' Save sheet left open must not be in the next test's way.
    override func tearDown() {
        app.terminate()
        super.tearDown()
    }

    func testSharingASessionWithVideoOpensTheShareSheet() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoVideo"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).press(forDuration: 1.2)
        let share = app.buttons["Share"].firstMatch
        XCTAssertTrue(share.waitForExistence(timeout: 10), "no Share in the session menu")
        share.tap()
        assertShareSheet("Share session")
        XCTAssertFalse(app.alerts.firstMatch.exists, app.alerts.firstMatch.label)
    }

    func testShareLogOpensTheShareSheet() throws {
        // A tab on both: in the bar at the bottom, or on an iPad with iOS 18 in the bar at the top.
        app.launchArguments = ["-freshInstall"]
        app.launch()
        let bottom = app.tabBars.buttons["Settings"]
        let settings = bottom.waitForExistence(timeout: 30) ? bottom : app.buttons["Settings"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 30), "no Settings tab")
        settings.tap()
        let diagnostics = app.buttons["settings.diagnostics"].firstMatch
        XCTAssertTrue(diagnostics.waitForExistence(timeout: 10), "no Diagnostics in Settings")
        for _ in 0..<3 where !diagnostics.isHittable { app.swipeUp() }
        diagnostics.tap()
        let shareLog = app.descendants(matching: .any).matching(identifier: "diagnostics.share").firstMatch
        XCTAssertTrue(shareLog.waitForExistence(timeout: 10), "no Share log")
        shareLog.tap()
        assertShareSheet("Share log")
    }

    func testVboExportsAreWrittenWithoutAFailure() {
        // One launch per format: Files' Save sheet runs out of the app, so each run is ended.
        for (index, format) in ["VBO + video", "VBO"].enumerated() {
            app.launchArguments = index == 0 ? ["-freshInstall", "-demoSessions", "-demoVideo"] : ["-demoSessions"]
            app.launch()
            openSessions()
            let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
            XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
            rows.element(boundBy: 0).tap()
            XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "no summary")
            let menu = app.buttons["exportMenu"].firstMatch
            XCTAssertTrue(menu.waitForExistence(timeout: 10), "no export menu")
            // As ReviewScreensTests: the menu can close while the session's video and progress are read.
            let item = app.buttons[format].firstMatch
            for _ in 0..<4 where !(item.exists && item.isHittable) {
                menu.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
                _ = item.waitForExistence(timeout: 5)
            }
            XCTAssertTrue(item.isHittable, "no \(format) in the export menu")
            item.tap()
            // A failed export says so in an alert; a written one goes on to Files.
            let failed = app.alerts.firstMatch.waitForExistence(timeout: 30)
            XCTAssertFalse(failed, "\(format): \(app.alerts.firstMatch.label)")
            keep("Export \(format)")
            XCTAssertEqual(app.state, .runningForeground, "\(format): the app stopped")
            app.terminate()
        }
    }

    /// The system share sheet over the app, which keeps running. The sheet's own elements differ
    /// between iOS versions: its list, or its Copy or Save to Files action.
    private func assertShareSheet(_ name: String, timeout: TimeInterval = 60) {
        let signs = [
            app.otherElements["ActivityListView"].firstMatch,
            app.descendants(matching: .any).matching(identifier: "ActivityListView").firstMatch,
            app.buttons["Copy"].firstMatch,
            app.cells["Copy"].firstMatch,
            app.buttons["Save to Files"].firstMatch,
            app.cells["Save to Files"].firstMatch,
        ]
        var shown = false
        let deadline = Date().addingTimeInterval(timeout)
        while !shown && Date() < deadline && app.state == .runningForeground {
            shown = signs.contains { $0.exists }
            if !shown { sleep(2) }
        }
        keep(name)
        if !shown {
            let tree = XCTAttachment(string: app.debugDescription)
            tree.name = "\(device) \(name) elements"
            tree.lifetime = .keepAlways
            add(tree)
        }
        XCTAssertEqual(app.state, .runningForeground, "\(name): the app stopped")
        XCTAssertTrue(shown, "\(name): no share sheet")
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
