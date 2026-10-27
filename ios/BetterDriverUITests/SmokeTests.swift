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
        // The sheet looks for RaceBoxes: the Bluetooth prompt can come up over it.
        allowSystemAlerts()

        let sheet = app.descendants(matching: .any)["receiverSheet"]
        if !sheet.waitForExistence(timeout: 20) {
            // What is on screen instead, for the CI log.
            print("SMOKE app:\n\(app.debugDescription)")
            print("SMOKE springboard:\n\(XCUIApplication(bundleIdentifier: "com.apple.springboard").debugDescription)")
            XCTFail("the START sheet")
        }
        // The RaceBox scan's Bluetooth prompt can come up after the sheet does.
        allowSystemAlerts()
        // The camera is on after a fresh install; the toggle only has to say so.
        let camera = app.switches["phoneCameraToggle"]
        if camera.waitForExistence(timeout: 5), (camera.value as? String) != "1" { camera.tap() }
        // By id, or by its label if a parent's id ever covers the row's own.
        let byId = app.buttons["receiver.simulated"]
        let simulated = byId.exists
            ? byId : app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Simulated GPS")).firstMatch
        if !simulated.waitForExistence(timeout: 10) {
            print("SMOKE app:\n\(app.debugDescription)")
            print("SMOKE springboard:\n\(XCUIApplication(bundleIdentifier: "com.apple.springboard").debugDescription)")
            XCTFail("the simulated receiver")
        }
        simulated.tap()
        allowSystemAlerts()

        let screen = app.descendants(matching: .any)["trackScreen"]
        XCTAssertTrue(screen.waitForExistence(timeout: 20), "the track screen")
        // Records for a while with every link running; a tap lets the permission monitor answer.
        let until = Date().addingTimeInterval(15)
        while Date() < until {
            sleep(1)
            allowSystemAlerts()
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

    /// Answers the system's permission prompts (camera, microphone, Bluetooth, motion) as a first-time
    /// user allowing them would; they belong to SpringBoard, not to the app.
    private func allowSystemAlerts() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for _ in 0..<4 {
            let alert = springboard.alerts.firstMatch
            guard alert.waitForExistence(timeout: 2) else { return }
            let allow = ["Allow", "OK", "Allow While Using App", "Allow Once"].map { alert.buttons[$0] }.first { $0.exists }
            guard let allow else { return }
            allow.tap()
        }
    }

    /// A top-level tab: in the tab bar at the bottom, or on an iPad with iOS 18 in the bar at the top.
    private func tab(_ name: String) -> XCUIElement {
        let bottom = app.tabBars.buttons[name]
        return bottom.waitForExistence(timeout: 10) ? bottom : app.buttons[name].firstMatch
    }
}
