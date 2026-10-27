import XCTest

/// The review on the iPad and iPhone simulators (FBBD-331): a fresh install with no sessions, then
/// the Android app's built-in demo sessions on every tab. Nothing may crash; a screenshot of each
/// screen stays in the test results, which CI uploads as the ios-review-screenshots artifact.
/// Runs in name order: the fresh install first.
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

    /// The Sessions tab, where the app has a tab bar (the iPhone, and the iPad with four tabs).
    private func openSessions() {
        let sessions = app.tabBars.buttons["Sessions"]
        if sessions.waitForExistence(timeout: 5) { sessions.tap() }
    }

    private func keep(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "\(device) \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
