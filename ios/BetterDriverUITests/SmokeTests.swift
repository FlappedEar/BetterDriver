import XCTest

/// The gate before every TestFlight upload (ios-release.yml): a fresh install records a session with
/// the phone camera, OBD, heart rate and the coach on, stops it and opens it, without crashing. The
/// simulator has no camera or Bluetooth, so those links run against nothing, as with nothing paired.
final class SmokeTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        app.launchArguments = ["-uiTest", "-uiTestReset"]
        // Camera, microphone and Bluetooth prompts: allow, as a first-time user would.
        addUIInterruptionMonitor(withDescription: "Permission") { alert in
            for label in ["Allow", "OK", "Allow While Using App"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }
    }

    func testRecordStopAndOpenTheSession() {
        app.launch()
        tab("Drive").tap()

        let start = app.buttons["startButton"]
        XCTAssertTrue(start.waitForExistence(timeout: 20), "START on the Drive tab")
        start.tap()

        let camera = app.switches["phoneCameraToggle"]
        XCTAssertTrue(camera.waitForExistence(timeout: 10), "the START sheet")
        if (camera.value as? String) != "1" { camera.tap() }
        app.buttons["receiver.simulated"].tap()

        let screen = app.descendants(matching: .any)["trackScreen"]
        XCTAssertTrue(screen.waitForExistence(timeout: 20), "the track screen")
        // Records for a while with every link running; a tap lets the permission monitor answer.
        let until = Date().addingTimeInterval(15)
        while Date() < until {
            sleep(1)
            app.tap()
            XCTAssertEqual(app.state, .runningForeground, "the app is still running while recording")
        }
        XCTAssertTrue(screen.exists, "still recording after 15 s")

        // The 3 s hold stops the session (UI-16).
        screen.press(forDuration: 4)
        XCTAssertTrue(start.waitForExistence(timeout: 20), "back on Drive after the stop")
        XCTAssertFalse(app.staticTexts["recordingError"].exists, "no recording error")

        tab("Sessions").tap()
        let row = app.cells.firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the recorded session in the list")
        row.tap()
        XCTAssertTrue(app.descendants(matching: .any)["tabSummary"].waitForExistence(timeout: 20), "the session opens")
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// A top-level tab: in the tab bar at the bottom, or on an iPad with iOS 18 in the bar at the top.
    private func tab(_ name: String) -> XCUIElement {
        let bottom = app.tabBars.buttons[name]
        return bottom.waitForExistence(timeout: 10) ? bottom : app.buttons[name].firstMatch
    }
}
