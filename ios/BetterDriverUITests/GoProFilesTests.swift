import XCTest

/// Adding a GoPro's files to a session on the iPhone and iPad simulators (FBBD-343), as on Android
/// (UI-32): the session's menu offers Add for a GoPro started by hand, and the file is lined up by
/// the GoPro's GPS. With -demoGoPro the app adds a made-up GoPro file riding along the session in place
/// of Files' picker. Screenshots stay in the test results.
final class GoProFilesTests: XCTestCase {
    private let app = XCUIApplication()
    private var device: String { UIDevice.current.userInterfaceIdiom == .phone ? "iPhone" : "iPad" }

    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        app.terminate()
        super.tearDown()
    }

    func testAGoProFileIsLinedUpByGps() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoGoPro"]
        app.launch()
        openTab("Sessions")
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).press(forDuration: 1.2)
        let add = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Add GoPro'")).firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 10), "no Add GoPro video in the session's menu")
        keep("Add GoPro video")
        add.tap()

        let line = app.descendants(matching: .any).matching(identifier: "cameraLine").firstMatch
        let problem = app.descendants(matching: .any).matching(identifier: "cameraProblem").firstMatch
        if !line.waitForExistence(timeout: 120) {
            keep("GoPro not lined up")
            XCTFail("the GoPro file was not lined up (app \(app.state.rawValue)): \(problem.exists ? problem.label : "no problem shown")")
            return
        }
        XCTAssertFalse(problem.exists, problem.exists ? problem.label : "")
        XCTAssertTrue(line.label.contains("1 clip lined up"), line.label)
        XCTAssertTrue(line.label.contains("by GPS"), line.label)
        keep("GoPro lined up")
        XCTAssertEqual(app.state, .runningForeground, "the app stopped while adding the file")

        // Once it has video, the camera offers Add no more.
        rows.element(boundBy: 0).press(forDuration: 1.2)
        XCTAssertTrue(app.buttons["Watch video"].firstMatch.waitForExistence(timeout: 10), "no session menu")
        XCTAssertFalse(add.exists, "Add is still offered")
    }

    func testAGoProStartedByHandIsAddedInSettings() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom != .phone, "Settings is a tab on the iPhone")
        app.launchArguments = ["-freshInstall"]
        app.launch()
        openTab("Settings")
        app.buttons["settings.equipment"].firstMatch.tap()
        let addEquipment = app.buttons["addEquipment"].firstMatch
        XCTAssertTrue(addEquipment.waitForExistence(timeout: 10), "no Add equipment")
        addEquipment.tap()
        let goPro = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'GoPro, files added'")).firstMatch
        XCTAssertTrue(goPro.waitForExistence(timeout: 10), "no GoPro started by hand in Add equipment")
        keep("Add equipment")
        goPro.tap()
        let hero8 = app.buttons["HERO8 Black"].firstMatch
        XCTAssertTrue(hero8.waitForExistence(timeout: 10), "no Which GoPro list")
        keep("Which GoPro")
        hero8.tap()
        let item = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'GoPro HERO8 Black'")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 10), "the GoPro is not in the equipment list")
        keep("Equipment with a GoPro")
    }

    /// A tab: in the tab bar at the bottom, or on an iPad with iOS 18 in the bar at the top.
    private func openTab(_ name: String) {
        let bottom = app.tabBars.buttons[name]
        if bottom.waitForExistence(timeout: 5) { bottom.tap(); return }
        let top = app.buttons[name].firstMatch
        if top.waitForExistence(timeout: 5) { top.tap() }
    }

    private func keep(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "\(device) \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
