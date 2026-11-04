import XCTest
import UIKit

/// A recording keeps running with the phone locked or another app in front (Moto Park readiness,
/// 2026-11-04). With an external receiver nothing else keeps the app running, so iOS would suspend it:
/// a timer-driven receiver stops, and a recorder holding its session lock can be ended (0xdead10cc).
/// The test records with the simulated receiver, goes to the Home Screen for 30 s and checks that the
/// app was never suspended, then comes back and stops the session.
final class BackgroundRecordingTests: XCTestCase {
    private let app = XCUIApplication.quiet()

    override func setUp() {
        continueAfterFailure = false
        app.launchArguments = ["-uiTest", "-uiTestReset"]
        addUIInterruptionMonitor(withDescription: "Permission") { alert in
            for label in ["Allow While Using App", "Allow", "OK"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }
    }

    func testARecordingKeepsRunningInTheBackground() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .pad, "the iPad does not record")
        app.launch()
        let bottom = app.tabBars.buttons["Drive"]
        (bottom.waitForExistence(timeout: 10) ? bottom : app.buttons["Drive"].firstMatch).tap()
        let start = app.buttons["startButton"]
        XCTAssertTrue(start.waitForExistence(timeout: 20), "START on the Drive tab")
        start.tap()
        allowSystemAlerts()
        let byId = app.buttons["receiver.simulated"]
        let simulated = byId.exists
            ? byId : app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Simulated GPS")).firstMatch
        XCTAssertTrue(simulated.waitForExistence(timeout: 20), "the simulated receiver")
        simulated.tap()
        // A row picks; the sheet's own button starts (FBBD-446).
        let startSession = app.buttons["startSession"]
        XCTAssertTrue(startSession.waitForExistence(timeout: 10), "Start session on the START sheet")
        startSession.tap()
        // The location prompt for the background keep-alive can come up as the session starts.
        allowSystemAlerts()
        let screen = app.descendants(matching: .any).matching(identifier: "trackScreen").firstMatch
        XCTAssertTrue(screen.waitForExistence(timeout: 20), "the track screen")
        sleep(3)
        allowSystemAlerts()

        XCUIDevice.shared.press(.home)
        // The Home press can first close a system prompt, and the state takes a moment to change.
        if !app.wait(for: .runningBackground, timeout: 10) {
            allowSystemAlerts()
            XCUIDevice.shared.press(.home)
            XCTAssertTrue(app.wait(for: .runningBackground, timeout: 10), "in the background, state \(app.state.rawValue)")
        }
        // iOS suspends an app it does not keep running within a few seconds of leaving the screen.
        let until = Date().addingTimeInterval(30)
        while Date() < until {
            sleep(2)
            XCTAssertEqual(app.state, .runningBackground, "the recording app is suspended or gone in the background, state \(app.state.rawValue)")
        }

        app.activate()
        XCTAssertTrue(screen.waitForExistence(timeout: 20), "still recording after 30 s in the background")
        XCTAssertEqual(app.state, .runningForeground)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 4)
        XCTAssertTrue(start.waitForExistence(timeout: 20), "back on Drive after the stop")
        XCTAssertFalse(app.staticTexts["recordingError"].exists, "no recording error")
    }

    /// Answers the system's permission prompts (location, motion, Bluetooth) as a user allowing them would.
    private func allowSystemAlerts() {
        allowSystemPrompts()
    }
}
