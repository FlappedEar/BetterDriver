import XCTest

/// A track made from a session (FBBD-367, Android's FBBD-151) on the iPad and iPhone simulators: a
/// demo session at no saved track drove laps of a loop, so its summary offers to make a track; the
/// editor opens on the proposed line, Save keeps it, and the session is timed with it. A screenshot
/// of each stays in the test results.
final class MakeTrackTests: XCTestCase {
    private let app = XCUIApplication()
    private var device: String { UIDevice.current.userInterfaceIdiom == .phone ? "iPhone" : "iPad" }

    override func setUp() {
        continueAfterFailure = false
        // The session is analysed again once timed, on a busy CI simulator: the most xcodebuild allows.
        executionTimeAllowance = 600
        addUIInterruptionMonitor(withDescription: "Permission prompt") { alert in
            let allow = alert.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Allow'")).firstMatch
            (allow.exists ? allow : alert.buttons.firstMatch).tap()
            return true
        }
    }

    func testASessionAtNoTrackMakesATrackThatTimesItsLaps() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoNoTrack"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).tap()
        // Polled with cheap firstMatch queries while the loop is looked for, as the no-laps test does.
        let make = app.buttons["makeTrack"].firstMatch
        var shown = false
        for _ in 0..<36 where !shown { sleep(5); shown = make.exists }
        XCTAssertTrue(shown, "no offer to make a track")
        keep("Make a track")
        make.tap()
        let save = app.buttons["saveTrack"].firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 60), "no track editor")
        XCTAssertTrue(save.isEnabled, "the proposed track cannot be saved")
        keep("Make a track, editor")
        save.tap()
        let saved = NSPredicate(format: "isEnabled == false")
        expectation(for: saved, evaluatedWith: save)
        waitForExpectations(timeout: 30)
        // Saved, the session is read again with its new track and the editor closes with the offer;
        // Back only when it is still open.
        let back = app.buttons["editorBack"].firstMatch
        if back.waitForExistence(timeout: 3) { back.tap() }
        // Timed with the new track: the summary's lap times.
        let best = app.staticTexts["BEST ACTUAL LAP"].firstMatch
        var timed = false
        for _ in 0..<36 where !timed { sleep(5); timed = best.exists }
        XCTAssertTrue(timed, "the session is not timed with the new track")
        keep("Make a track, timed")
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// The Sessions tab: in the tab bar at the bottom, or on an iPad with iOS 18 in the bar at the top.
    private func openSessions() {
        let bottom = app.tabBars.buttons["Sessions"]
        if bottom.waitForExistence(timeout: 5) { bottom.tap(); return }
        let top = app.buttons["Sessions"].firstMatch
        if top.waitForExistence(timeout: 5) { top.tap() }
    }

    private func keep(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "\(device) \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
