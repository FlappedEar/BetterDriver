import XCTest
import UIKit

/// The list at START (FBBD-446, FBBD-447): every listed kind has its rows, a pick moves the radio,
/// and a session records with every device off and with each one chosen. The simulator has no
/// Bluetooth, so the chosen devices run against nothing, as with nothing in range.
final class StartListUiTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        app.launchArguments = ["-uiTest", "-uiTestReset", "-openGoPro", "-obdWifi", "-sensorsListed"]
        addUIInterruptionMonitor(withDescription: "Permission") { alert in
            for label in ["Allow", "OK", "Allow While Using App"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }
    }

    func testRecordsWithEveryDeviceOff() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .pad, "the iPad has no Drive tab")
        openSheet()
        let goPro = app.switches["goProToggle"]
        XCTAssertTrue(goPro.waitForExistence(timeout: 5), "the GoPro's switch")
        if (goPro.value as? String) == "1" { goPro.tap() }
        for kind in ["CAR_DATA", "HEART_RATE", "TPMS"] {
            pick("start.\(kind).none")
        }
        record()
    }

    func testRecordsWithTheSecondStrapAndEveryDeviceOn() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .pad, "the iPad has no Drive tab")
        openSheet()
        // The strap added last is chosen as it is added (FBBD-445); Strap A is picked, then B again.
        pick("start.HEART_RATE.Strap A")
        pick("start.HEART_RATE.Strap B")
        pick("start.TPMS.Tyre bridge")
        pick("start.CAR_DATA.Wi-Fi OBD-II adapter")
        record()
        // The choice is kept for the next session (UI-313 A).
        openSheet()
        let strapB = row("start.HEART_RATE.Strap B")
        XCTAssertTrue(strapB.waitForExistence(timeout: 10))
        XCTAssertTrue(strapB.isSelected, "Strap B still chosen at the next START")
        XCTAssertFalse(row("start.HEART_RATE.Strap A").isSelected)
    }

    private func openSheet() {
        if app.state != .runningForeground { app.launch() }
        tab("Drive").tap()
        let start = app.buttons["startButton"]
        XCTAssertTrue(start.waitForExistence(timeout: 20), "START on the Drive tab")
        start.tap()
        allowSystemAlerts()
        XCTAssertTrue(app.descendants(matching: .any)["receiverSheet"].waitForExistence(timeout: 20), "the START sheet")
        allowSystemAlerts()
    }

    private func row(_ id: String) -> XCUIElement { app.buttons[id] }

    /// Taps a row of the list and checks its radio is the one on.
    private func pick(_ id: String) {
        let element = row(id)
        if !element.waitForExistence(timeout: 10) {
            print("START LIST app:\n\(app.debugDescription)")
            XCTFail("\(id) on the START sheet")
        }
        element.tap()
        XCTAssertTrue(element.isSelected, "\(id) chosen")
    }

    /// Starts with the simulated receiver, records 10 s, stops with the 3 s hold.
    private func record() {
        let simulated = app.buttons["receiver.simulated"]
        XCTAssertTrue(simulated.waitForExistence(timeout: 10), "the simulated receiver")
        simulated.tap()
        let startSession = app.buttons["startSession"]
        XCTAssertTrue(startSession.waitForExistence(timeout: 10), "Start session")
        startSession.tap()
        allowSystemAlerts()
        let screen = app.descendants(matching: .any).matching(identifier: "trackScreen").firstMatch
        XCTAssertTrue(screen.waitForExistence(timeout: 20), "the track screen")
        let until = Date().addingTimeInterval(10)
        while Date() < until {
            sleep(1)
            allowSystemAlerts()
            XCTAssertEqual(app.state, .runningForeground, "the app is still running while recording")
        }
        XCTAssertTrue(screen.exists, "still recording after 10 s")
        screen.press(forDuration: 4)
        XCTAssertTrue(app.buttons["startButton"].waitForExistence(timeout: 20), "back on Drive after the stop")
        XCTAssertFalse(app.staticTexts["recordingError"].exists, "no recording error")
    }

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

    private func tab(_ name: String) -> XCUIElement {
        let bottom = app.tabBars.buttons[name]
        return bottom.waitForExistence(timeout: 10) ? bottom : app.buttons[name].firstMatch
    }
}
