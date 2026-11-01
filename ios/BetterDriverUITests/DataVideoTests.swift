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

    /// Each test ends its run of the app itself: one left in Files' Save sheet or a player could not
    /// be stopped in time by the next test's launch (ios-release.yml runs them one after the other).
    override func tearDown() {
        app.terminate()
        super.tearDown()
    }

    func testTheVideoPlaysWithItsDataAndSaves() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoVideo", "-noFilesSheet"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "no summary")
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
        assertLandscape()
        keep("Video with data playing")
        app.buttons["dataVideoClose"].firstMatch.tap()

        // Closing the player comes back to the sheet, which reads the laps and the video again.
        let save = app.buttons["dataVideoSave"].firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 30), "no Save video")
        expectation(for: enabled, evaluatedWith: save)
        waitForExpectations(timeout: 120)
        save.tap()
        // Making the video ends in "Saved <file>" (Files' Save sheet in the app; left out in the tests), or
        // in a reason in the sheet.
        let saved = app.descendants(matching: .any)["dataVideoSaved"].firstMatch
        let failed = app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH 'The video was not saved'")).firstMatch
        let ended = NSPredicate(format: "exists == true")
        let done = XCTWaiter.wait(
            for: [XCTNSPredicateExpectation(predicate: ended, object: saved)], timeout: 240
        )
        XCTAssertFalse(failed.exists, failed.exists ? failed.label : "")
        XCTAssertEqual(done, .completed, "the video was not saved")
        keep("Video with data saved")
        XCTAssertEqual(app.state, .runningForeground, "the app stopped while saving")
    }

    func testWatchVideoFromTheSessionList() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoVideo"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).press(forDuration: 1.2)
        app.buttons["Watch video"].firstMatch.tap()
        XCTAssertTrue(app.descendants(matching: .any)["dataVideoPlayer"].firstMatch.waitForExistence(timeout: 60), "no player")
        sleep(4)
        XCTAssertEqual(app.state, .runningForeground, "the app stopped while playing")
        assertLandscape()
        keep("Watch video")
        app.buttons["dataVideoClose"].firstMatch.tap()
    }

    /// A session with video has a Video tab (Arek, 2026-10-30): Watch plays it in landscape and
    /// closing the player comes back to the tab.
    func testTheVideoTabPlaysTheSessionsVideo() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoVideo"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).tap()
        let tab = app.buttons["tabVideo"].firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 60), "no Video tab")
        tab.tap()
        let watch = app.buttons["dataVideoWatch"].firstMatch
        XCTAssertTrue(watch.waitForExistence(timeout: 60), "no Watch in the Video tab")
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: watch)
        waitForExpectations(timeout: 120)
        keep("Video tab")
        watch.tap()
        XCTAssertTrue(app.descendants(matching: .any)["dataVideoPlayer"].firstMatch.waitForExistence(timeout: 30), "no player")
        sleep(4)
        assertLandscape()
        keep("Video tab playing")
        app.buttons["dataVideoClose"].firstMatch.tap()
        XCTAssertTrue(watch.waitForExistence(timeout: 30), "not back in the Video tab")
    }

    /// The composite laps' video (FBBD-370), as Android's Video tab: with all of the session on video the
    /// realistic optimal and the raw theoretical best are offered after the whole session; a composite
    /// lap is saved only (Watch stays off, no handle) and is written as BetterDriver-<id>-raw-theoretical.mp4.
    func testTheCompositeLapIsSavedFromTheVideoTab() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoVideoLong", "-noFilesSheet"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 180), "no demo sessions")
        rows.element(boundBy: 0).tap()
        let tab = app.buttons["tabVideo"].firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 60), "no Video tab")
        tab.tap()

        let realistic = app.buttons["dataVideoPart-1"].firstMatch
        let raw = app.buttons["dataVideoPart-2"].firstMatch
        XCTAssertTrue(raw.waitForExistence(timeout: 180), "no composite laps in the Video tab")
        XCTAssertTrue(realistic.exists, "no realistic optimal in the Video tab")
        XCTAssertTrue(realistic.label.hasPrefix("Realistic optimal"), realistic.label)
        XCTAssertTrue(raw.label.hasPrefix("Raw theoretical best"), raw.label)
        keep("Video tab composite laps")
        XCTAssertTrue(raw.isEnabled, "the raw theoretical best is not on video: \(raw.label)")
        // The best lap is picked when the tab opens, so Watch and Save are already on: wait until the
        // raw theoretical best is the picked row (tapped again once if the first tap did not take,
        // as on the iPad job), then until Watch has turned off, rather than reading it at once.
        let picked = NSPredicate(format: "isSelected == true")
        raw.tap()
        if XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: picked, object: raw)], timeout: 5) != .completed {
            raw.tap()
        }
        XCTAssertEqual(
            XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: picked, object: raw)], timeout: 15), .completed,
            "the raw theoretical best was not picked"
        )
        let watch = app.buttons["dataVideoWatch"].firstMatch
        let save = app.buttons["dataVideoSave"].firstMatch
        let off = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == false"), object: watch)
        XCTAssertEqual(XCTWaiter.wait(for: [off], timeout: 15), .completed, "a composite lap is saved only, as on Android")
        XCTAssertTrue(save.isEnabled, "no Save video for the composite lap")
        XCTAssertFalse(app.buttons["dataVideoHandle6"].exists, "a composite lap has no handle")
        save.tap()
        let saved = app.descendants(matching: .any)["dataVideoSaved"].firstMatch
        let failed = app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH 'The video was not saved'")).firstMatch
        let done = XCTWaiter.wait(
            for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true"), object: saved)], timeout: 300
        )
        XCTAssertFalse(failed.exists, failed.exists ? failed.label : "")
        XCTAssertEqual(done, .completed, "the composite lap's video was not saved")
        XCTAssertTrue(saved.label.hasSuffix("-raw-theoretical.mp4"), saved.label)
        keep("Composite lap saved")
        XCTAssertEqual(app.state, .runningForeground, "the app stopped while saving")
    }

    /// The Sessions tab: in the tab bar at the bottom, or on an iPad with iOS 18 in the bar at the top.
    private func openSessions() {
        let bottom = app.tabBars.buttons["Sessions"]
        if bottom.waitForExistence(timeout: 5) { bottom.tap(); return }
        let top = app.buttons["Sessions"].firstMatch
        if top.waitForExistence(timeout: 5) { top.tap() }
    }

    /// Video plays in landscape on the iPhone, whichever way the phone is held. The iPad turns too,
    /// unless it shares the screen with another app.
    private func assertLandscape() {
        // The video itself with its overlay: landscape on every device, also from a file tagged upright.
        XCTAssertEqual(app.descendants(matching: .any)["dataVideoPlayer"].firstMatch.value as? String, "wide",
                       "the video is not landscape")
        guard UIDevice.current.userInterfaceIdiom == .phone else { return }
        let close = app.buttons["dataVideoClose"].firstMatch
        let turned = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == 'landscape'"), object: close)
        XCTAssertEqual(XCTWaiter.wait(for: [turned], timeout: 15), .completed, "the player is not in landscape")
    }

    private func keep(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "\(device) \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
