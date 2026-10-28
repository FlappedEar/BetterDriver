import XCTest

/// The review on the iPad and iPhone simulators (FBBD-331): a fresh install with no sessions, then
/// the Android app's built-in demo sessions on every tab. Nothing may crash; a screenshot of each
/// screen stays in the test results, which CI uploads as the ios-review-screenshots artifact.
/// Each test that needs it starts from -freshInstall.
final class ReviewScreensTests: XCTestCase {
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

    func testAFreshInstallOpensEveryScreenWithoutSessions() {
        app.launchArguments = ["-freshInstall"]
        app.launch()
        // Every tab the app has (Drive, Sessions, Tracks, ...), as a new user would open them.
        let tabs = app.tabBars.buttons
        for i in 0..<tabs.count {
            let tab = tabs.element(boundBy: i)
            let label = tab.label
            tab.tap()
            XCTAssertEqual(app.state, .runningForeground, "\(label): the app stopped")
            keep("\(label), fresh install")
        }
        openSessions()
        let empty = app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH 'No sessions yet'")).firstMatch
        XCTAssertTrue(empty.waitForExistence(timeout: 30), "no empty session list")
        keep("Sessions, fresh install")
    }

    func testDeletingASessionAsksThenRemovesIt() {
        app.launchArguments = ["-freshInstall", "-demoSessions"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 1).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).press(forDuration: 1.2)
        app.buttons["Delete"].firstMatch.tap()
        // The alert nests its button in a second element with the same id: take the first.
        let confirm = app.alerts.buttons["confirmDelete"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 10), "no delete question")
        keep("Delete session?")
        confirm.tap()
        let gone = NSPredicate(format: "count == 1")
        expectation(for: gone, evaluatedWith: rows)
        waitForExpectations(timeout: 30)
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testCsvAndGpxExportsAreWrittenThenOfferedToFiles() {
        app.launchArguments = ["-freshInstall", "-demoSessions"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["BEST LAP"].waitForExistence(timeout: 120), "no summary")
        for format in ["CSV", "GPX"] {
            let menu = app.buttons["exportMenu"].firstMatch
            XCTAssertTrue(menu.waitForExistence(timeout: 10), "no export menu")
            menu.tap()
            app.buttons[format].firstMatch.tap()
            // Files' Save sheet comes up once the file is written; a failure shows an alert instead.
            let cancel = app.buttons["Cancel"].firstMatch
            XCTAssertTrue(cancel.waitForExistence(timeout: 60), "\(format): no Save to Files")
            XCTAssertFalse(app.alerts.firstMatch.exists, "\(format): \(app.alerts.firstMatch.label)")
            keep("Export \(format)")
            cancel.tap()
            XCTAssertEqual(app.state, .runningForeground, "\(format): the app stopped")
        }
    }

    func testTheDemoSessionsOpenOnEveryTab() {
        // Newest first: Silesia Ring (27 Sep, 18 laps), then Jastrząb (29 Aug, 23 laps), as DemoParityTest.
        for (index, (name, laps)) in [("silesia-ring", "18"), ("jastrzab", "23")].enumerated() {
            // A fresh launch for each, so the list shows; the first starts empty and adds the demos.
            app.launchArguments = index == 0 ? ["-freshInstall", "-demoSessions"] : ["-demoSessions"]
            app.launch()
            openSessions()
            let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
            XCTAssertTrue(rows.element(boundBy: index).waitForExistence(timeout: 120), "no session row \(index)")
            rows.element(boundBy: index).tap()
            // Figure writes its label in capitals.
            XCTAssertTrue(app.staticTexts["BEST LAP"].waitForExistence(timeout: 120), "\(name): no summary")
            XCTAssertTrue(app.staticTexts[laps].exists, "\(name): not \(laps) laps")
            for tab in ["Summary", "Compare", "Map", "Laps", "Corners", "Car"] {
                app.buttons["tab\(tab)"].tap()
                // The tab's numbers come off the main thread.
                sleep(3)
                XCTAssertEqual(app.state, .runningForeground, "\(name) \(tab): the app stopped")
                keep("\(name) \(tab)")
            }
        }
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
