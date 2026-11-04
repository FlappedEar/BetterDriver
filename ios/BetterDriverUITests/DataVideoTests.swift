import XCTest

/// Video with data on the iPhone and iPad simulators (FBBD-152, FBBD-333): a demo session with ten
/// seconds of phone video plays with the overlay and is saved as an MP4. Screenshots stay in the
/// test results (ios-review-screenshots).
final class DataVideoTests: XCTestCase {
    private let app = XCUIApplication.quiet()
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
        XCTAssertTrue(app.descendants(matching: .any)["dataVideoPlayer"].firstMatch.waitForExistence(timeout: 90), "no player")
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
        // The row's menu offers Watch video once the row has read that the session has video (FBBD-426).
        waitForLabel(rows.element(boundBy: 0), containing: "Video")
        rows.element(boundBy: 0).press(forDuration: 1.2)
        app.buttons["Watch video"].firstMatch.tap()
        XCTAssertTrue(app.descendants(matching: .any)["dataVideoPlayer"].firstMatch.waitForExistence(timeout: 60), "no player")
        sleep(4)
        XCTAssertEqual(app.state, .runningForeground, "the app stopped while playing")
        assertLandscape()
        keep("Watch video")
        app.buttons["dataVideoClose"].firstMatch.tap()
    }

    /// A session without video offers no Watch video (Arek 2026-11-02, FBBD-426): left out of the row's
    /// menu, not greyed out, and no Video tab or realistic optimal video in the session.
    func testASessionWithoutVideoOffersNoWatchVideo() {
        app.launchArguments = ["-freshInstall", "-demoSessions"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        let row = rows.element(boundBy: 0)
        XCTAssertTrue(row.waitForExistence(timeout: 120), "no demo sessions")
        // The row's facts are read: its laps are listed.
        waitForLabel(row, containing: "laps")
        XCTAssertFalse(row.label.contains("Video"), row.label)
        row.press(forDuration: 1.2)
        XCTAssertTrue(app.buttons["Share"].firstMatch.waitForExistence(timeout: 10), "no session menu")
        XCTAssertFalse(app.buttons["Watch video"].firstMatch.exists, "Watch video offered without video")
        keep("No Watch video without video")

        // In the session: no Video tab, no realistic optimal video under the lap times. A fresh start
        // closes the menu without tapping into it.
        app.terminate()
        app.launch()
        openSessions()
        XCTAssertTrue(row.waitForExistence(timeout: 120), "no demo sessions")
        row.tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "no summary")
        XCTAssertTrue(app.buttons["tabSummary"].firstMatch.waitForExistence(timeout: 30), "no tab row")
        // The session's video is looked for off the main queue; give it time to (wrongly) appear.
        sleep(5)
        XCTAssertFalse(app.buttons["tabVideo"].firstMatch.exists, "a Video tab without video")
        XCTAssertFalse(app.buttons["optimalVideoButton"].firstMatch.exists, "realistic optimal video without video")
        keep("No video entry points in the session")
    }

    /// Waits until [element]'s label holds [text].
    private func waitForLabel(_ element: XCUIElement, containing text: String) {
        let has = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", text), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [has], timeout: 60), .completed, "\(text) not in \(element.label)")
    }

    /// The seek bar moves the video (FBBD-428, Arek 2026-11-02: in the app on a Mac AVKit's bar did not
    /// seek and Close sat on AirPlay): the app's own transport, as Android's Watch video. Dragging the bar
    /// moves the clock, the video plays on from there, and Close is clear of the controls.
    func testTheSeekBarMovesTheVideo() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoVideo"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
        waitForLabel(rows.element(boundBy: 0), containing: "Video")
        rows.element(boundBy: 0).press(forDuration: 1.2)
        app.buttons["Watch video"].firstMatch.tap()
        XCTAssertTrue(app.descendants(matching: .any)["dataVideoPlayer"].firstMatch.waitForExistence(timeout: 60), "no player")
        let play = app.buttons["dataVideoPlay"].firstMatch
        let seek = app.sliders["dataVideoSeek"].firstMatch
        let clock = app.staticTexts["dataVideoClock"].firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 10), "no Play/Pause")
        XCTAssertTrue(seek.exists, "no seek bar")
        XCTAssertTrue(clock.exists, "no clock")
        assertLandscape()
        // It starts playing; stopped, the clock stays put.
        XCTAssertEqual(play.label, "Pause")
        play.tap()
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == 'Play'"), object: play)], timeout: 10), .completed, "Pause did not stop it")
        let total = seconds(clock.label, part: 1)
        XCTAssertGreaterThanOrEqual(total, 4, "the demo video is too short: \(clock.label)")

        seek.adjust(toNormalizedSliderPosition: 0.6)
        let wanted = Double(total) * 0.6
        let moved = waitFor(timeout: 20) { abs(Double(self.seconds(clock.label, part: 0)) - wanted) <= 1.5 }
        XCTAssertTrue(moved, "the seek bar did not move the video: \(clock.label) of \(total) s, wanted about \(wanted) s")
        // Left alone it stays there once the exact seek has landed: the player was sent there, not only the bar.
        sleep(2)
        keep("Seek bar moved")
        let sought = seconds(clock.label, part: 0)
        XCTAssertLessThanOrEqual(abs(Double(sought) - wanted), 1.5, "the video went back after the seek: \(clock.label)")

        // Plays on from there.
        play.tap()
        sleep(2)
        play.tap()
        XCTAssertGreaterThan(seconds(clock.label, part: 0), sought, "it did not play on from the seek: \(clock.label)")

        // Close is clear of the transport (it sat on AirPlay in AVKit's controls on a Mac).
        let close = app.buttons["dataVideoClose"].firstMatch
        XCTAssertFalse(close.frame.intersects(play.frame), "Close is on Play/Pause")
        XCTAssertFalse(close.frame.intersects(seek.frame), "Close is on the seek bar")
        XCTAssertEqual(app.state, .runningForeground, "the app stopped while seeking")
        close.tap()
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
        XCTAssertTrue(app.descendants(matching: .any)["dataVideoPlayer"].firstMatch.waitForExistence(timeout: 90), "no player")
        sleep(4)
        assertLandscape()
        keep("Video tab playing")
        app.buttons["dataVideoClose"].firstMatch.tap()
        XCTAssertTrue(watch.waitForExistence(timeout: 30), "not back in the Video tab")
    }

    /// The composite laps' video (FBBD-370), as Android's Video tab: with all of the session on video the
    /// realistic optimal and the raw theoretical best are offered after the whole session; a composite
    /// lap has no handle, is written as BetterDriver-<id>-raw-theoretical.mp4 and is watched in the app
    /// (Arek, 2026-11-01: it could not be watched).
    func testTheCompositeLapIsSavedAndWatchedFromTheVideoTab() {
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
        // The best lap is picked when the tab opens: wait until the raw theoretical best is the picked
        // row (tapped again once if the first tap did not take, as on the iPad job), then until its
        // handle row has gone, rather than reading it at once.
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
        let noHandle = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: app.buttons["dataVideoHandle6"].firstMatch
        )
        XCTAssertEqual(XCTWaiter.wait(for: [noHandle], timeout: 15), .completed, "a composite lap has no handle")
        XCTAssertTrue(save.isEnabled, "no Save video for the composite lap")
        XCTAssertTrue(watch.isEnabled, "no Watch for the composite lap")
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

        // Watched in the app, with the composite's own overlay (the same composition as the saved file).
        let on = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: watch)
        XCTAssertEqual(XCTWaiter.wait(for: [on], timeout: 30), .completed, "Watch did not come back after saving")
        watch.tap()
        XCTAssertTrue(
            app.descendants(matching: .any)["dataVideoPlayer"].firstMatch.waitForExistence(timeout: 60),
            "the composite lap did not play"
        )
        sleep(2)
        keep("Composite lap playing")
        app.buttons["dataVideoClose"].firstMatch.tap()
        XCTAssertTrue(watch.waitForExistence(timeout: 30), "not back in the Video tab")
    }

    /// The composite lap's video under the lap times (FBBD-370), as Android's lap analysis: "Realistic
    /// optimal video" says what the video leaves out, opens the sheet on the realistic optimal with its
    /// parts, and Export saves the chosen composite lap (BetterDriver-<id>-raw-theoretical.mp4).
    func testTheOptimalLapIsSavedFromUnderTheLapTimes() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoVideoLong", "-noFilesSheet"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 180), "no demo sessions")
        rows.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "no summary")

        let button = app.buttons["optimalVideoButton"].firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 60), "no optimal-lap video under the lap times")
        XCTAssertEqual(button.label, "Realistic optimal video")
        // The parts are looked for on video off the main queue: the button turns on when they are found.
        let on = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: button)
        XCTAssertEqual(XCTWaiter.wait(for: [on], timeout: 180), .completed,
                       "the optimal-lap video stayed off: \(app.staticTexts["optimalVideoNote"].firstMatch.label)")
        for _ in 0..<4 where !button.isHittable { app.swipeUp() }
        keep("Optimal-lap video under the lap times")
        button.tap()

        // The sheet, by its title; the button tapped again once if the first tap did not take.
        let title = app.staticTexts["optimalVideoSheet"].firstMatch
        if !title.waitForExistence(timeout: 15) {
            keep("Composite lap sheet not open")
            button.tap()
        }
        XCTAssertTrue(title.waitForExistence(timeout: 30), "no composite lap sheet")
        XCTAssertEqual(title.label, "Composite lap video")
        let realistic = app.buttons["optimalVideoKind-1"].firstMatch
        let raw = app.buttons["optimalVideoKind-2"].firstMatch
        XCTAssertTrue(raw.waitForExistence(timeout: 10), "no raw theoretical best in the sheet")
        XCTAssertTrue(realistic.label.hasPrefix("Realistic optimal"), realistic.label)
        XCTAssertTrue(raw.label.hasPrefix("Raw theoretical best"), raw.label)
        keep("Composite lap sheet")
        XCTAssertTrue(raw.isEnabled, "the raw theoretical best is not on video: \(raw.label)")
        let picked = NSPredicate(format: "isSelected == true")
        raw.tap()
        if XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: picked, object: raw)], timeout: 5) != .completed {
            raw.tap()
        }
        XCTAssertEqual(
            XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: picked, object: raw)], timeout: 15), .completed,
            "the raw theoretical best was not picked"
        )
        let export = app.buttons["optimalVideoExport"].firstMatch
        XCTAssertTrue(export.isEnabled, "no Export for the raw theoretical best")
        export.tap()

        let saved = app.descendants(matching: .any)["optimalVideoSaved"].firstMatch
        let failed = app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH 'The video was not saved'")).firstMatch
        let done = XCTWaiter.wait(
            for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true"), object: saved)], timeout: 300
        )
        XCTAssertFalse(failed.exists, failed.exists ? failed.label : "")
        XCTAssertEqual(done, .completed, "the composite lap's video was not saved from under the lap times")
        XCTAssertTrue(saved.label.hasSuffix("-raw-theoretical.mp4"), saved.label)
        keep("Optimal-lap video saved")
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

    /// Seconds of "4:05 / 10:00" ([part] 0: where it is, 1: how long).
    private func seconds(_ clock: String, part: Int) -> Int {
        let halves = clock.components(separatedBy: " / ")
        guard halves.count == 2 else { return -1 }
        return halves[part].split(separator: ":").reduce(0) { $0 * 60 + (Int($1) ?? 0) }
    }

    private func waitFor(timeout: TimeInterval, _ ok: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if ok() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return ok()
    }

    private func keep(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "\(device) \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
