import XCTest
import UIKit

/// The gate before every TestFlight upload (ios-release.yml): a fresh install records a session with
/// the phone camera, an Open GoPro, OBD, heart rate and the coach on, stops it and opens it, without
/// crashing. The simulator has no camera or Bluetooth, so those links run against nothing, as with
/// nothing paired.
final class SmokeTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        app.launchArguments = ["-uiTest", "-uiTestReset", "-openGoPro"]
        // Camera, microphone and Bluetooth prompts: allow, as a first-time user would.
        addUIInterruptionMonitor(withDescription: "Permission") { alert in
            for label in ["Allow", "OK", "Allow While Using App"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }
    }

    func testRecordStopAndOpenTheSession() throws {
        // Recording is on the iPhone: the iPad shows the review shell, with no Drive tab.
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .pad, "the iPad has no Drive tab")
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
        // The camera starts off: the smoke test records with it on.
        let camera = app.switches["phoneCameraToggle"]
        if camera.waitForExistence(timeout: 5), (camera.value as? String) != "1" { camera.tap() }
        // A GoPro that is not there: it fails on its own while the session records (FBBD-343).
        let goPro = app.switches["goProToggle"]
        XCTAssertTrue(goPro.waitForExistence(timeout: 5), "the GoPro's switch on the START sheet")
        XCTAssertEqual(goPro.value as? String, "1", "a GoPro just added starts with the next session")
        // By id, or by its label if a parent's id ever covers the row's own.
        let byId = app.buttons["receiver.simulated"]
        let simulated = byId.exists
            ? byId : app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Simulated GPS")).firstMatch
        if !simulated.waitForExistence(timeout: 10) {
            print("SMOKE app:\n\(app.debugDescription)")
            print("SMOKE springboard:\n\(XCUIApplication(bundleIdentifier: "com.apple.springboard").debugDescription)")
            XCTFail("the simulated receiver; on screen: \(onScreen())")
        }
        simulated.tap()
        allowSystemAlerts()

        // The screen's id reaches its children too: the first match is the screen itself.
        let screen = app.descendants(matching: .any).matching(identifier: "trackScreen").firstMatch
        XCTAssertTrue(screen.waitForExistence(timeout: 20), "the track screen")
        // Records for a while with every link running; a tap lets the permission monitor answer.
        let until = Date().addingTimeInterval(15)
        while Date() < until {
            sleep(1)
            allowSystemAlerts()
            XCTAssertEqual(app.state, .runningForeground, "the app is still running while recording")
        }
        XCTAssertTrue(screen.exists, "still recording after 15 s")

        // A swipe goes to the second screen and back (FBBD-262, FBBD-318). By label: the track screen's id
        // reaches its children and can stand in for theirs.
        func labelled(_ text: String) -> XCUIElement {
            app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", text)).firstMatch
        }
        // Across the whole app: the first element with the screen's id can be a small child, too short
        // for a swipe to reach the screen's 60 points.
        app.swipeLeft()
        if !labelled("Screen 2 of 2").waitForExistence(timeout: 10) {
            print("SMOKE app:\n\(app.debugDescription)")
            XCTFail("the second screen")
        }
        // The live screen always has G (a RaceBox's or the phone's accelerometer), so the radar is there.
        XCTAssertTrue(labelled("G radar").exists, "the G radar on the second screen")
        app.swipeRight()
        XCTAssertTrue(labelled("Screen 1 of 2").waitForExistence(timeout: 10), "back on the main screen")
        XCTAssertEqual(app.state, .runningForeground)

        // The 3 s hold stops the session (UI-16).
        screen.press(forDuration: 4)
        XCTAssertTrue(start.waitForExistence(timeout: 20), "back on Drive after the stop")
        XCTAssertFalse(app.staticTexts["recordingError"].exists, "no recording error")

        tab("Sessions").tap()
        // The row as the review tests find it: a cell can be the day's header.
        let row = app.descendants(matching: .any).matching(identifier: "sessionRow").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the recorded session in the list")
        row.tap()
        if !app.buttons["tabSummary"].waitForExistence(timeout: 30) {
            print("SMOKE app:\n\(app.debugDescription)")
            XCTFail("the session opens")
        }
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

    /// The buttons and switches on screen, short enough for the failure text the CI log prints.
    private func onScreen() -> String {
        let elements = app.buttons.allElementsBoundByIndex + app.switches.allElementsBoundByIndex
        return elements.prefix(40).map { "\($0.label)#\($0.identifier)" }.joined(separator: " | ")
    }

    /// A top-level tab: in the tab bar at the bottom, or on an iPad with iOS 18 in the bar at the top.
    private func tab(_ name: String) -> XCUIElement {
        let bottom = app.tabBars.buttons[name]
        return bottom.waitForExistence(timeout: 10) ? bottom : app.buttons[name].firstMatch
    }
}
