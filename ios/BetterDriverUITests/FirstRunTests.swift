import XCTest
import UIKit

/// First run (FBBD-371), as Android's Setup screen: a fresh iPhone opens on the checklist, its four
/// steps in Android's order, Done waits for location and Bluetooth, and after Done the app opens on
/// its tabs. The iPad only reviews and opens on Sessions. Settings › App › Run setup again shows
/// the checklist on both.
final class FirstRunTests: XCTestCase {
    private let app = XCUIApplication()
    private var phone: Bool { UIDevice.current.userInterfaceIdiom == .phone }

    override func setUp() {
        continueAfterFailure = false
    }

    func testAFreshIPhoneOpensOnTheChecklistAndDoneOpensTheApp() throws {
        try XCTSkipIf(!phone, "the iPad only reviews: no checklist at launch")
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

        // Allow: iOS asks for location, Bluetooth, camera and microphone, one after another.
        let allow = app.buttons["setup.action.Permissions"]
        XCTAssertTrue(allow.waitForExistence(timeout: 5), "Allow on the permissions step")
        allow.tap()
        allowSystemAlerts()
        let done = app.buttons["setup.done"]
        let enabled = NSPredicate(format: "isEnabled == true")
        let granted = expectation(for: enabled, evaluatedWith: done)
        if XCTWaiter.wait(for: [granted], timeout: 30) != .completed {
            print("FIRSTRUN app:\n\(app.debugDescription)")
            XCTFail("Done once location and Bluetooth are allowed")
        }
        done.tap()
        XCTAssertTrue(tab("Drive").waitForExistence(timeout: 10), "the app's tabs after Done")

        // Set up: the next launch opens on the tabs.
        app.terminate()
        app.launchArguments = ["-firstRun"]
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

    /// iOS's permission questions, each allowed, as a first-time user would.
    private func allowSystemAlerts() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for _ in 0..<6 {
            let alert = springboard.alerts.firstMatch
            guard alert.waitForExistence(timeout: 5) else { return }
            let allow = ["Allow While Using App", "Allow", "OK", "Allow Once"].map { alert.buttons[$0] }.first { $0.exists }
            guard let allow else { return }
            allow.tap()
        }
    }

    /// A top-level tab: in the tab bar at the bottom, or on an iPad with iOS 18 in the bar at the top.
    private func tab(_ name: String) -> XCUIElement {
        let bottom = app.tabBars.buttons[name]
        return bottom.waitForExistence(timeout: 10) ? bottom : app.buttons[name].firstMatch
    }
}
