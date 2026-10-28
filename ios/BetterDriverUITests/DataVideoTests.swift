import XCTest

/// Video with data on the iPhone and iPad simulators (FBBD-152, FBBD-333): a demo session with ten
/// seconds of phone video plays with the overlay and is saved as an MP4. Screenshots stay in the
/// test results (ios-review-screenshots).
final class DataVideoTests: XCTestCase {
    private let app = XCUIApplication()
    private var device: String { UIDevice.current.userInterfaceIdiom == .phone ? "iPhone" : "iPad" }

    override func setUp() {
        continueAfterFailure = false
        addUIInterruptionMonitor(withDescription: "Permission prompt") { alert in
            let allow = alert.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Allow'")).firstMatch
            (allow.exists ? allow : alert.buttons.firstMatch).tap()
            return true
        }
    }

    func testTheVideoPlaysWithItsDataAndSaves() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoVideo"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["BEST LAP"].waitForExistence(timeout: 120), "no summary")
        let menu = app.buttons["exportMenu"].firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 30), "no export menu")
        // The menu offers Video with data once it has read that the session has video.
        sleep(3)
        menu.tap()
        let item = app.buttons["Video with data"].firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 10), "no Video with data in the export menu")
        item.tap()

        // The sheet reads the laps and the video, then offers the whole session (the demo's video is
        // a minute in, off the best lap).
        let watch = app.buttons["dataVideoWatch"].firstMatch
        XCTAssertTrue(watch.waitForExistence(timeout: 60), "no Video with data sheet")
        let enabled = NSPredicate(format: "isEnabled == true")
        expectation(for: enabled, evaluatedWith: watch)
        waitForExpectations(timeout: 120)
        keep("Video with data")
        watch.tap()
        XCTAssertTrue(app.descendants(matching: .any)["dataVideoPlayer"].firstMatch.waitForExistence(timeout: 30), "no player")
        sleep(4)
        XCTAssertEqual(app.state, .runningForeground, "the app stopped while playing")
        keep("Video with data playing")
        app.buttons["dataVideoClose"].firstMatch.tap()

        let save = app.buttons["dataVideoSave"].firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 30), "no Save video")
        save.tap()
        // Making the video ends in Files' Save sheet (out of the app), or in a reason in the sheet.
        let making = app.staticTexts["Making the video…"]
        _ = making.waitForExistence(timeout: 5)
        let finished = NSPredicate(format: "exists == false")
        expectation(for: finished, evaluatedWith: making)
        waitForExpectations(timeout: 180)
        let failed = app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH 'The video was not saved'")).firstMatch
        XCTAssertFalse(failed.exists, failed.exists ? failed.label : "")
        keep("Video with data saved")
        XCTAssertEqual(app.state, .runningForeground, "the app stopped while saving")
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
