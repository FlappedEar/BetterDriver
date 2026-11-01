import XCTest

/// Settings › Equipment › RaceBox › Diagnostics and Settings › Diagnostics › Camera test on the iPhone
/// simulator (FBBD-371). The simulator has neither Bluetooth nor a camera: with -raceBoxDiagDemo the
/// RaceBox document's example message goes through the shared probe and text, without it the screen
/// waits for a RaceBox that is never there; the camera test says it cannot run. Real-RaceBox and
/// real-camera checks need a phone. Screenshots stay in the test results.
final class RaceBoxDiagnosticsTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        app.terminate()
        super.tearDown()
    }

    private func openDiagnostics(_ arguments: [String]) {
        app.launchArguments = ["-freshInstall", "-raceBoxListed"] + arguments
        app.launch()
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 30), "no Settings tab")
        settings.tap()
        app.buttons["settings.equipment"].firstMatch.tap()
        let item = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'RaceBox Micro 0000000000'")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 10), "the RaceBox is not in the equipment list")
        item.tap()
        let diagnostics = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Diagnostics'")).firstMatch
        XCTAssertTrue(diagnostics.waitForExistence(timeout: 10), "no Diagnostics row on the RaceBox")
        diagnostics.tap()
        XCTAssertTrue(app.navigationBars["RaceBox diagnostics"].waitForExistence(timeout: 10), "no RaceBox diagnostics page")
    }

    func testDiagnosticsShowWhatTheRaceBoxReports() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom != .phone, "Settings is a tab on the iPhone")
        openDiagnostics(["-raceBoxDiagDemo"])
        let header = app.descendants(matching: .any).matching(identifier: "raceBoxDiag.header").firstMatch
        XCTAssertTrue(header.waitForExistence(timeout: 10), "no header")
        XCTAssertTrue(header.label.contains("Connected · 25 Hz"), header.label)
        // From the example message (rev 8, p. 7) and the Device Information strings.
        for text in ["GNSS", "DATA STREAM", "POWER", "MOTION SENSORS", "CALIBRATION", "DEVICE",
                     "89 % · not charging", "RaceBox Mini S", "−58 dBm", "Not calibrated"] {
            let found = label(text)
            if !found.exists { app.swipeUp() }
            XCTAssertTrue(found.waitForExistence(timeout: 5), "no \(text)")
        }
        XCTAssertTrue(app.buttons["raceBoxDiag.calibrate"].exists, "no Calibrate button")
        keep("RaceBox diagnostics")
    }

    func testDiagnosticsWaitForAMissingRaceBoxWithoutAFailure() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom != .phone, "Settings is a tab on the iPhone")
        openDiagnostics([])
        let header = app.descendants(matching: .any).matching(identifier: "raceBoxDiag.header").firstMatch
        XCTAssertTrue(header.waitForExistence(timeout: 10), "no header")
        Thread.sleep(forTimeInterval: 5)
        XCTAssertEqual(app.state, .runningForeground, "the app stopped while the page waited")
        XCTAssertTrue(header.label.contains("Connecting") || header.label.contains("Bluetooth"), header.label)
        keep("RaceBox diagnostics, no RaceBox")
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertEqual(app.state, .runningForeground, "the app stopped when the page let go")
    }

    func testTheCameraTestSaysWhenThePhoneCannotRunIt() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom != .phone, "Settings is a tab on the iPhone")
        app.launchArguments = ["-freshInstall"]
        app.launch()
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 30), "no Settings tab")
        settings.tap()
        app.buttons["settings.diagnostics"].firstMatch.tap()
        let row = app.buttons["diagnostics.cameraTest"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "no Camera test row")
        row.tap()
        XCTAssertTrue(label("Part 1 · with MP4").waitForExistence(timeout: 10), "no start page")
        keep("Camera test, start")
        app.buttons["cameraTest.start"].tap()
        // The simulator has no camera: the test ends at once and says why.
        XCTAssertTrue(label("COULD NOT RUN").waitForExistence(timeout: 10), "no result")
        XCTAssertTrue(label("This phone has no 1080p · 60 camera.").exists, "no reason")
        keep("Camera test, no camera")
    }

    private func label(_ text: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label == %@", text)).firstMatch
    }

    private func keep(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "iPhone \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
