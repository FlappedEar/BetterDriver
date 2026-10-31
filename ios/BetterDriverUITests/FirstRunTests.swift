import XCTest
import UIKit

/// The shared core's text under Done (FirstRunSetup.PERMISSIONS_FIRST).
private enum FirstRunText {
    static let permissionsFirst = "Allow location and nearby devices first."
}

/// First run (FBBD-371), as Android's Setup screen: a fresh iPhone opens on the checklist, its four
/// steps in Android's order, Allow asks iOS (location first), Done waits for location and
/// Bluetooth, and after Done the app opens on its tabs. The iPad only reviews and opens on
/// Sessions. Settings › App › Run setup again shows the checklist on both.
final class FirstRunTests: XCTestCase {
    private let app = XCUIApplication()
    private var phone: Bool { UIDevice.current.userInterfaceIdiom == .phone }

    override func setUp() {
        continueAfterFailure = false
    }

    func testAFreshIPhoneOpensOnTheChecklistAndAllowAsksIOS() throws {
        try XCTSkipIf(!phone, "the iPad only reviews: no checklist at launch")
        // A fresh install has answered no permission yet: earlier tests on this simulator may have.
        for resource: XCUIProtectedResource in [.location, .bluetooth, .camera, .microphone] {
            app.resetAuthorizationStatus(for: resource)
        }
        app.launchArguments = ["-uiTestReset", "-firstRun"]
        app.launch()
        let summary = app.staticTexts["setup.summary"]
        XCTAssertTrue(summary.waitForExistence(timeout: 20), "the checklist on a fresh install")
        XCTAssertTrue(summary.label.hasSuffix("of 4 done. You can start without the optional steps."), summary.label)
        // Android's order, top to bottom.
        let rows = ["Permissions", "GNSS receiver", "Cameras", "Car"].map { step($0) }
        for (row, title) in zip(rows, ["Permissions", "GNSS receiver", "Cameras", "Car"]) {
            XCTAssertTrue(row.waitForExistence(timeout: 5), "the \(title) step")
        }
        let tops = rows.map(\.frame.minY)
        XCTAssertEqual(tops, tops.sorted(), "steps in Android's order")
        // Nothing but the phone camera comes with the app.
        XCTAssertTrue(app.staticTexts["Needed for lap timing"].exists, "no GNSS receiver yet")
        XCTAssertTrue(app.staticTexts["Phone camera"].exists, "the phone camera")

        // Done waits for location and Bluetooth.
        XCTAssertFalse(app.buttons["setup.done"].isEnabled, "Done before location and Bluetooth are allowed")
        XCTAssertTrue(app.staticTexts[FirstRunText.permissionsFirst].exists, "what Done waits for")

        // Allow asks iOS, location first.
        let allow = app.buttons["setup.action.Permissions"]
        XCTAssertTrue(allow.waitForExistence(timeout: 5), "Allow on the permissions step")
        allow.tap()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let question = springboard.alerts.firstMatch
        XCTAssertTrue(question.waitForExistence(timeout: 10), "iOS asks for a permission after Allow")
        XCTAssertTrue(question.label.localizedCaseInsensitiveContains("location"), "location first: \(question.label)")
    }

    func testDoneOpensTheAppAndTheNextLaunchSkipsTheChecklist() throws {
        try XCTSkipIf(!phone, "the iPad only reviews: no checklist at launch")
        // Location and Bluetooth count as allowed: Done is the step under test.
        app.launchArguments = ["-uiTestReset", "-firstRun", "-requiredGrantedForTest"]
        app.launch()
        XCTAssertTrue(app.staticTexts["setup.summary"].waitForExistence(timeout: 20), "the checklist on a fresh install")
        let done = app.buttons["setup.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 5) && done.isEnabled, "Done once location and Bluetooth are allowed")
        done.tap()
        XCTAssertTrue(tab("Drive").waitForExistence(timeout: 10), "the app's tabs after Done")

        // Set up: the next launch opens on the tabs.
        app.terminate()
        app.launchArguments = ["-firstRun", "-requiredGrantedForTest"]
        app.launch()
        XCTAssertTrue(tab("Drive").waitForExistence(timeout: 20), "the tabs on the next launch")
        XCTAssertFalse(app.staticTexts["setup.summary"].exists, "no checklist once set up")
    }

    func testTheCarStepAddsACar() throws {
        try XCTSkipIf(!phone, "the iPad only reviews: no checklist at launch")
        app.launchArguments = ["-uiTestReset", "-firstRun"]
        app.launch()
        let add = app.buttons["setup.action.Car"]
        XCTAssertTrue(add.waitForExistence(timeout: 20), "Add on the car step")
        add.tap()
        XCTAssertTrue(app.textFields["carName"].waitForExistence(timeout: 10), "Add car")
    }

    func testTheIPadOpensOnSessions() throws {
        try XCTSkipIf(phone, "the iPhone opens on the checklist")
        app.launchArguments = ["-uiTestReset", "-firstRun"]
        app.launch()
        XCTAssertTrue(tab("Sessions").waitForExistence(timeout: 20), "Sessions")
        XCTAssertFalse(app.staticTexts["setup.summary"].waitForExistence(timeout: 3), "no checklist on the iPad")
    }

    func testRunSetupAgainShowsTheChecklist() {
        app.launchArguments = ["-uiTest", "-uiTestReset"]
        app.launch()
        tab("Settings").tap()
        let appTopic = app.buttons["settings.app"].firstMatch
        XCTAssertTrue(appTopic.waitForExistence(timeout: 10), "Settings › App")
        appTopic.tap()
        let again = app.buttons["Run setup again"].firstMatch
        if !again.waitForExistence(timeout: 5) { app.swipeUp() }
        XCTAssertTrue(again.waitForExistence(timeout: 5), "Run setup again")
        again.tap()
        XCTAssertTrue(app.staticTexts["setup.summary"].waitForExistence(timeout: 10), "the checklist")
        XCTAssertTrue(step("GNSS receiver").exists, "the GNSS receiver step")
    }

    private func step(_ title: String) -> XCUIElement {
        app.descendants(matching: .any)["setup.step.\(title)"].firstMatch
    }

    /// A top-level tab: in the tab bar at the bottom, or on an iPad with iOS 18 in the bar at the top.
    private func tab(_ name: String) -> XCUIElement {
        let bottom = app.tabBars.buttons[name]
        return bottom.waitForExistence(timeout: 10) ? bottom : app.buttons[name].firstMatch
    }
}
