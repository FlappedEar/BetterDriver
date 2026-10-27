import XCTest

/// The review on the iPad and iPhone simulators (FBBD-331): a fresh install with no sessions, then
/// the Android app's built-in demo sessions on every tab. Nothing may crash; a screenshot of each
/// screen stays in the test results, which CI uploads as the ios-review-screenshots artifact.
/// Runs in name order: the fresh install first.
final class ReviewScreensTests: XCTestCase {
    private let app = XCUIApplication()
    private var phone: Bool { UIDevice.current.userInterfaceIdiom == .phone }
    private var device: String { phone ? "iPhone" : "iPad" }

    override func setUp() {
        continueAfterFailure = false
    }

    func testAFreshInstallOpensEveryScreenWithoutSessions() {
        app.launchArguments = ["-freshInstall"]
        app.launch()
        if phone {
            keep("Drive, fresh install")
            app.tabBars.buttons["Tracks"].tap()
            keep("Tracks, fresh install")
            app.tabBars.buttons["Sessions"].tap()
        }
        let empty = app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH 'No sessions yet'")).firstMatch
        XCTAssertTrue(empty.waitForExistence(timeout: 30), "no empty session list")
        keep("Sessions, fresh install")
        if !phone {
            app.buttons["openTracks"].tap()
            keep("Tracks, fresh install")
        }
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testTheDemoSessionsOpenOnEveryTab() {
        // Newest first: Silesia Ring (27 Sep, 18 laps), then Jastrząb (29 Aug, 23 laps), as DemoParityTest.
        for (index, (name, laps)) in [("silesia-ring", "18"), ("jastrzab", "23")].enumerated() {
            // A fresh launch for each, so the list shows; the first starts empty and adds the demos.
            app.launchArguments = index == 0 ? ["-freshInstall", "-demoSessions"] : ["-demoSessions"]
            app.launch()
            if phone { app.tabBars.buttons["Sessions"].tap() }
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

    private func keep(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "\(device) \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
