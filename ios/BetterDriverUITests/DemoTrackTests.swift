import XCTest
import UIKit

/// The Demo track on the iPhone (FBBD-373), as on Android (FBBD-233, FBBD-235): added in Settings ›
/// Equipment under Try the app, Arek's Jastrząb laps picked in its Drives, then a recording on it times
/// laps on the track screen and the session opens. Screenshots stay in the test results.
final class DemoTrackTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        app.launchArguments = ["-uiTest", "-uiTestReset"]
        addUIInterruptionMonitor(withDescription: "Permission") { alert in
            for label in ["Allow", "OK", "Allow While Using App"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }
    }

    override func tearDown() {
        app.terminate()
        super.tearDown()
    }

    func testTheJastrzabReplayTimesLaps() throws {
        // Recording is on the iPhone: the iPad has no Drive tab.
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .pad, "the iPad has no Drive tab")
        app.launch()

        // Settings › Equipment › Add equipment › Try the app › Demo track.
        tab("Settings").tap()
        app.buttons["settings.equipment"].firstMatch.tap()
        let addEquipment = app.buttons["addEquipment"].firstMatch
        XCTAssertTrue(addEquipment.waitForExistence(timeout: 10), "no Add equipment")
        addEquipment.tap()
        allowSystemAlerts()
        let addDemo = element("add.DEMO_TRACK", labelStart: "Demo track")
        XCTAssertTrue(addDemo.waitForExistence(timeout: 10), "no Demo track under Try the app")
        keep("Add equipment")
        addDemo.tap()

        // Its item page: Drives, then Arek's Jastrząb laps.
        let item = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Demo track'")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 10), "the Demo track is not in the equipment list")
        item.tap()
        let drives = element("demoDrives", labelStart: "Drives")
        XCTAssertTrue(drives.waitForExistence(timeout: 10), "no Drives row")
        XCTAssertTrue(
            app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'Demo circuit'")).firstMatch.exists,
            "a new Demo track drives the demo circuit"
        )
        drives.tap()
        let jastrzab = element("demoDrive.built-in:jastrzab", labelStart: "")
        XCTAssertTrue(jastrzab.waitForExistence(timeout: 20), "no Jastrząb in Drives")
        XCTAssertTrue(element("demoDrive.built-in:silesia-ring", labelStart: "").exists, "no Silesia Ring in Drives")
        keep("Drives")
        jastrzab.tap()
        XCTAssertTrue(
            app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'Autodrom Jastrząb'"))
                .firstMatch.waitForExistence(timeout: 10),
            "Drives does not show the pick"
        )

        // START on the Demo track.
        tab("Drive").tap()
        let start = app.buttons["startButton"]
        XCTAssertTrue(start.waitForExistence(timeout: 20), "START on the Drive tab")
        start.tap()
        allowSystemAlerts()
        let receiver = element("receiver.demoTrack", labelStart: "Demo track")
        XCTAssertTrue(receiver.waitForExistence(timeout: 20), "no Demo track on the START sheet")
        keep("START sheet")
        receiver.tap()
        allowSystemAlerts()

        let screen = app.descendants(matching: .any).matching(identifier: "trackScreen").firstMatch
        XCTAssertTrue(screen.waitForExistence(timeout: 20), "the track screen")
        // A finished lap shows three times: the running lap, Last and Best. Jastrząb laps are 110 to
        // 151 s, and the replay starts at the line.
        let times = app.staticTexts.matching(NSPredicate(format: "label MATCHES %@", "^[0-9]+:[0-9]{2}\\.[0-9]{2}$"))
        let until = Date().addingTimeInterval(360)
        while times.count < 3, Date() < until {
            sleep(5)
            XCTAssertEqual(app.state, .runningForeground, "the app is still running while recording")
        }
        keep("Track screen")
        XCTAssertGreaterThanOrEqual(times.count, 3, "no lap timed in 6 minutes: \(times.allElementsBoundByIndex.map(\.label))")

        // The 3 s hold stops the session; it opens from Sessions.
        screen.press(forDuration: 4)
        XCTAssertTrue(start.waitForExistence(timeout: 30), "back on Drive after the stop")
        XCTAssertFalse(app.staticTexts["recordingError"].exists, "no recording error")
        tab("Sessions").tap()
        let row = app.descendants(matching: .any).matching(identifier: "sessionRow").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the recorded session in the list")
        row.tap()
        XCTAssertTrue(app.buttons["tabSummary"].waitForExistence(timeout: 60), "the session opens")
        keep("Session")
    }

    /// By id, or by its label if a parent's id ever covers the row's own.
    private func element(_ id: String, labelStart: String) -> XCUIElement {
        let byId = app.descendants(matching: .any).matching(identifier: id).firstMatch
        if byId.waitForExistence(timeout: 3) || labelStart.isEmpty { return byId }
        return app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", labelStart)).firstMatch
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

    private func keep(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    private func tab(_ name: String) -> XCUIElement {
        let bottom = app.tabBars.buttons[name]
        return bottom.waitForExistence(timeout: 10) ? bottom : app.buttons[name].firstMatch
    }
}
