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
        pickSimulated()
        for kind in ["CAR_DATA", "HEART_RATE", "TPMS"] {
            pick("start.\(kind).none")
        }
        record()
    }

    func testRecordsWithTheSecondStrapAndEveryDeviceOn() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .pad, "the iPad has no Drive tab")
        openSheet()
        pickSimulated()
        // Top down, as the rows are listed: the sheet only scrolls up to them.
        pick("start.CAR_DATA.Wi-Fi OBD-II adapter")
        // The strap added last is chosen as it is added (FBBD-445); Strap A is picked, then B again.
        pick("start.HEART_RATE.Strap A")
        pick("start.HEART_RATE.Strap B")
        pick("start.TPMS.Tyre bridge")
        record()
        // The choice is kept for the next session (UI-313 A), also after the app is closed.
        app.terminate()
        app.launchArguments.removeAll { $0 == "-uiTestReset" }
        launched = false
        openSheet()
        let strapB = reveal("start.HEART_RATE.Strap B")
        XCTAssertTrue(strapB.isSelected, "Strap B still chosen at the next START")
        XCTAssertFalse(row("start.HEART_RATE.Strap A").isSelected)
    }

    private var launched = false

    private func openSheet() {
        // A fresh start for each test: a test before this one can leave the app on its track screen.
        if !launched {
            app.launch()
            launched = true
        }
        tab("Drive").tap()
        let start = app.buttons["startButton"]
        XCTAssertTrue(start.waitForExistence(timeout: 20), "START on the Drive tab")
        let sheet = app.descendants(matching: .any)["receiverSheet"]
        // Right after a stop the track screen can still be going away: SwiftUI drops a sheet asked for
        // then, as a person's tap would be, so START is tapped again.
        for _ in 0..<3 {
            start.tap()
            allowSystemAlerts()
            if sheet.waitForExistence(timeout: 8) { break }
        }
        if !sheet.exists {
            print("START LIST app:\n\(app.debugDescription)")
            XCTFail("the START sheet; on screen: \(onScreen())")
        }
        allowSystemAlerts()
    }

    private func row(_ id: String) -> XCUIElement { app.buttons[id] }

    /// The row, scrolled up into view: car data, heart rate and tyres are below the sheet's first height.
    private func reveal(_ id: String) -> XCUIElement {
        let element = row(id)
        let sheet = app.scrollViews.firstMatch
        for _ in 0..<8 {
            if element.exists, element.isHittable { return element }
            if sheet.exists { sheet.swipeUp() } else { app.swipeUp() }
        }
        if !element.waitForExistence(timeout: 5) || !element.isHittable {
            print("START LIST app:\n\(app.debugDescription)")
            XCTFail("\(id) on the START sheet; on screen: \(onScreen())")
        }
        return element
    }

    /// Taps a row of the list and checks its radio is the one on.
    private func pick(_ id: String) {
        let element = reveal(id)
        element.tap()
        let selected = expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: element)
        wait(for: [selected], timeout: 5)
    }

    /// The simulated receiver, at the top of the sheet: picked before the rows further down.
    private func pickSimulated() {
        let simulated = app.buttons["receiver.simulated"]
        XCTAssertTrue(simulated.waitForExistence(timeout: 10), "the simulated receiver")
        simulated.tap()
    }

    /// The ids on screen, short enough for the failure text the CI log prints.
    private func onScreen() -> String {
        let elements = app.buttons.allElementsBoundByIndex + app.switches.allElementsBoundByIndex
        return elements.prefix(60).map { "\($0.label)#\($0.identifier)" }.joined(separator: " | ")
    }

    /// Starts with the receiver picked, records 10 s, stops with the 3 s hold.
    private func record() {
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
        allowSystemPrompts()
    }

    private func tab(_ name: String) -> XCUIElement {
        let bottom = app.tabBars.buttons[name]
        return bottom.waitForExistence(timeout: 10) ? bottom : app.buttons[name].firstMatch
    }
}
