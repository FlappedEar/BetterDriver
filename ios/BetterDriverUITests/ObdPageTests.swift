import XCTest

/// The car data page on the iPhone simulator (FBBD-261, FBBD-333): with a Wi-Fi OBD-II adapter listed
/// that is never there (-obdWifi), the page connects by itself, says no adapter answers, and the app
/// keeps running while it retries. Screenshots stay in the test results.
final class ObdPageTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        app.terminate()
        super.tearDown()
    }

    func testTheCarDataPageSearchesForAMissingWifiAdapterWithoutAFailure() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom != .phone, "Settings is a tab on the iPhone")
        app.launchArguments = ["-freshInstall", "-obdWifi"]
        app.launch()
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 30), "no Settings tab")
        settings.tap()
        app.buttons["settings.equipment"].firstMatch.tap()
        let item = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Wi-Fi OBD-II adapter'")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 10), "the Wi-Fi adapter is not in the equipment list")
        item.tap()
        let live = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Connection and live values'")).firstMatch
        XCTAssertTrue(live.waitForExistence(timeout: 10), "no Connection and live values row")
        live.tap()
        let header = app.descendants(matching: .any).matching(identifier: "obdHeader").firstMatch
        XCTAssertTrue(header.waitForExistence(timeout: 10), "no car data page")
        // A few connect rounds to 192.168.0.10, which is not there on the simulator.
        Thread.sleep(forTimeInterval: 8)
        XCTAssertEqual(app.state, .runningForeground, "the app stopped while the page searched")
        XCTAssertTrue(
            header.label.contains("No adapter") || header.label.contains("Connecting"),
            header.label
        )
        keep("Car data page, no adapter")
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(live.waitForExistence(timeout: 10), "back from the page")
        XCTAssertEqual(app.state, .runningForeground, "the app stopped when the page let go")
    }

    /// Only what Equipment lists reads the car (FBBD-349): the Wi-Fi adapter removed there is gone from
    /// the list and the car data page, and Add without Bluetooth offers it again.
    func testARemovedWifiAdapterIsGoneAndCanBeAddedAgain() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom != .phone, "Settings is a tab on the iPhone")
        app.launchArguments = ["-freshInstall", "-obdWifi"]
        app.launch()
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 30), "no Settings tab")
        settings.tap()
        app.buttons["settings.equipment"].firstMatch.tap()
        let item = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Wi-Fi OBD-II adapter'")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 10), "the Wi-Fi adapter is not in the equipment list")
        item.tap()
        let remove = app.buttons["Remove"].firstMatch
        XCTAssertTrue(remove.waitForExistence(timeout: 10), "no Remove")
        remove.tap()
        XCTAssertTrue(item.waitForNonExistence(timeout: 10), "the removed adapter is still listed")
        keep("Equipment, Wi-Fi adapter removed")
        let addEquipment = app.buttons["addEquipment"].firstMatch
        XCTAssertTrue(addEquipment.waitForExistence(timeout: 10), "no Add equipment")
        addEquipment.tap()
        allowSystemAlerts()
        var add = app.descendants(matching: .any).matching(identifier: "add.OBD_WIFI").firstMatch
        if !add.waitForExistence(timeout: 5) {
            add = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Wi-Fi OBD-II adapter'")).firstMatch
        }
        keep("Add equipment, Wi-Fi adapter offered")
        XCTAssertTrue(add.waitForExistence(timeout: 10), "Add without Bluetooth does not offer the Wi-Fi adapter")
        add.tap()
        XCTAssertTrue(item.waitForExistence(timeout: 10), "the Wi-Fi adapter was not added again")
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// The Bluetooth question Add equipment's search can raise on a fresh simulator.
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
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "iPhone \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
