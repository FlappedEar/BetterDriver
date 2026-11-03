import XCTest
import UIKit

/// The Demo track on the iPhone (FBBD-373), as on Android (FBBD-233, FBBD-235): added in Settings ›
/// Equipment under Try the app, Arek's Jastrząb laps picked in its Drives, then a recording on it times
/// laps on the track screen and the session opens, with the track screen's frame times within budget
/// (FBBD-376). Screenshots stay in the test results.
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
        // A row picks; the sheet's own button starts (FBBD-446).
        let startSession = app.buttons["startSession"]
        XCTAssertTrue(startSession.waitForExistence(timeout: 10), "Start session on the START sheet")
        startSession.tap()
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
        checkFrameTimes()
    }

    /// The track screen's frame times on the simulator, from the session's shared quality report, as
    /// Android's ReplayDriveTest gates them on the emulator (FBBD-376, FBBD-266): the p95 after the first
    /// 90 s within [drivingFramesMillis], the first 90 s within [startUpFramesMillis].
    /// The Demo track's Live data (FBBD-440): its item page links to the page, which runs the same link
    /// a recording does, without a session, and shows the made-up car's position as it drives.
    func testTheDemoTrackShowsLiveDataWithoutASession() throws {
        // Equipment is on the iPhone: the iPad is for analysis only.
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .pad, "the iPad has no Equipment")
        app.launch()
        tab("Settings").tap()
        app.buttons["settings.equipment"].firstMatch.tap()
        let addEquipment = app.buttons["addEquipment"].firstMatch
        XCTAssertTrue(addEquipment.waitForExistence(timeout: 10), "no Add equipment")
        addEquipment.tap()
        allowSystemAlerts()
        let addDemo = element("add.DEMO_TRACK", labelStart: "Demo track")
        XCTAssertTrue(addDemo.waitForExistence(timeout: 10), "no Demo track under Try the app")
        addDemo.tap()
        let item = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Demo track'")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 10), "the Demo track is not in the equipment list")
        item.tap()

        let live = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Live data'")).firstMatch
        XCTAssertTrue(live.waitForExistence(timeout: 10), "no Live data row")
        live.tap()
        let header = app.descendants(matching: .any).matching(identifier: "liveData.header").firstMatch
        XCTAssertTrue(header.waitForExistence(timeout: 10), "no live data page")
        let receiving = NSPredicate(format: "label CONTAINS 'Receiving'")
        expectation(for: receiving, evaluatedWith: header)
        waitForExpectations(timeout: 15)
        for label in ["Latitude", "Speed", "Updates received"] {
            XCTAssertTrue(
                app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", label))
                    .firstMatch.exists,
                "no \(label) row"
            )
        }
        keep("Live data")
    }

    private func checkFrameTimes() {
        let line = app.staticTexts["qualityChecks"]
        XCTAssertTrue(line.waitForExistence(timeout: 90), "no quality report for the session")
        var checks: [String: (verdict: String, value: Double?)] = [:]
        for item in line.label.components(separatedBy: "; ") {
            let parts = item.split(separator: "=", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { continue }
            let rest = parts[1].split(separator: " ", maxSplits: 1).map(String.init)
            guard let verdict = rest.first else { continue }
            checks[parts[0]] = (verdict, rest.count > 1 ? Double(rest[1]) : nil)
        }
        let driving = checks["ui.frame_p95"]
        print("FBBD-376 frame times: ui.frame_p95=\(String(describing: driving)) ui.frame_p95_start=\(String(describing: checks["ui.frame_p95_start"]))")
        XCTAssertNotNil(driving?.value, "no frame times while driving: \(line.label)")
        if let p95 = driving?.value {
            XCTAssertLessThanOrEqual(p95, drivingFramesMillis, "Frame time p95 (FBBD-266): \(p95) ms")
        }
        let startUp = checks["ui.frame_p95_start"]?.value
        XCTAssertNotNil(startUp, "no frame times from the first 90 s: \(line.label)")
        if let startUp {
            XCTAssertLessThanOrEqual(startUp, startUpFramesMillis, "Frame time p95, first 90 s (FBBD-266): \(startUp) ms")
        }
    }

    /// The limit after the first 90 s on the simulator: two frames at 60 Hz. The CI's macOS runners draw
    /// the simulator without a GPU (20 ms p95 on 2 Nov 2026, the phone's 16 ms check FAIL), so this
    /// catches a track screen that does twice the work, while the phone is judged against 16 ms from its logs.
    private let drivingFramesMillis = 33.0

    /// The first 90 s's limit on the simulator, as on Android's emulator: the app warms up there while
    /// the recorder, coach and voice start; the phone is judged against 16 ms from its logs.
    private let startUpFramesMillis = 50.0

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
