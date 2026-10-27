import XCTest

/// The review of the Android app's built-in demo sessions on the iPad and iPhone simulators
/// (FBBD-331): each tab opens on real laps, and a screenshot of each stays in the test results,
/// which CI uploads as the ios-review-screenshots artifact.
final class ReviewScreensTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testTheDemoSessionsOpenOnEveryTab() {
        let app = XCUIApplication()
        app.launchArguments = ["-demoSessions"]
        let phone = UIDevice.current.userInterfaceIdiom == .phone
        let device = phone ? "iPhone" : "iPad"
        // Newest first: Silesia Ring (27 Sep, 18 laps), then Jastrząb (29 Aug, 23 laps), as DemoParityTest.
        for (index, (name, laps)) in [("silesia-ring", "18"), ("jastrzab", "23")].enumerated() {
            // A fresh launch for each, so the list shows; the demos are added on the first only.
            app.launch()
            if phone { app.tabBars.buttons["Sessions"].tap() }
            let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
            XCTAssertTrue(rows.element(boundBy: index).waitForExistence(timeout: 120), "no session row \(index)")
            rows.element(boundBy: index).tap()
            XCTAssertTrue(app.staticTexts["Best lap"].waitForExistence(timeout: 120), "\(name): no summary")
            XCTAssertTrue(app.staticTexts[laps].exists, "\(name): not \(laps) laps")
            for tab in ["Summary", "Compare", "Map", "Laps", "Corners", "Car"] {
                app.buttons["tab\(tab)"].tap()
                // The tab's numbers come off the main thread.
                sleep(3)
                keep(app.screenshot(), "\(device) \(name) \(tab)")
            }
        }
    }

    private func keep(_ screenshot: XCUIScreenshot, _ name: String) {
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
