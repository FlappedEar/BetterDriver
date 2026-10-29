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

    private func keep(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "iPhone \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
