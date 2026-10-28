import XCTest

/// The review on the iPad and iPhone simulators (FBBD-331): a fresh install with no sessions, then
/// the Android app's built-in demo sessions on every tab. Nothing may crash; a screenshot of each
/// screen stays in the test results, which CI uploads as the ios-review-screenshots artifact.
/// Each test that needs it starts from -freshInstall.
final class ReviewScreensTests: XCTestCase {
    private let app = XCUIApplication()
    private var device: String { UIDevice.current.userInterfaceIdiom == .phone ? "iPhone" : "iPad" }

    override func setUp() {
        continueAfterFailure = false
        // A permission prompt (Bluetooth, location) on a fresh install is answered, not left in the way.
        addUIInterruptionMonitor(withDescription: "Permission prompt") { alert in
            let allow = alert.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Allow'")).firstMatch
            (allow.exists ? allow : alert.buttons.firstMatch).tap()
            return true
        }
    }

    func testAFreshInstallOpensEveryScreenWithoutSessions() {
        app.launchArguments = ["-freshInstall"]
        app.launch()
        // Every tab the app has (Drive, Sessions, Tracks, ...), as a new user would open them.
        let tabs = app.tabBars.buttons
        for i in 0..<tabs.count {
            let tab = tabs.element(boundBy: i)
            let label = tab.label
            tab.tap()
            XCTAssertEqual(app.state, .runningForeground, "\(label): the app stopped")
            keep("\(label), fresh install")
        }
        openSessions()
        let empty = app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH 'No sessions yet'")).firstMatch
        XCTAssertTrue(empty.waitForExistence(timeout: 30), "no empty session list")
        keep("Sessions, fresh install")
    }

    func testDeletingASessionAsksThenRemovesIt() {
        app.launchArguments = ["-freshInstall", "-demoSessions"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 1).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).press(forDuration: 1.2)
        app.buttons["Delete"].firstMatch.tap()
        // The alert nests its button in a second element with the same id: take the first.
        let confirm = app.alerts.buttons["confirmDelete"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 10), "no delete question")
        keep("Delete session?")
        confirm.tap()
        let gone = NSPredicate(format: "count == 1")
        expectation(for: gone, evaluatedWith: rows)
        waitForExpectations(timeout: 30)
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testSharingASessionWritesItWithoutAFailure() {
        app.launchArguments = ["-freshInstall", "-demoSessions"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).press(forDuration: 1.2)
        app.buttons["Share"].firstMatch.tap()
        // The share sheet runs out of the app; a failed bundle says so in an alert instead.
        let failed = app.alerts.firstMatch.waitForExistence(timeout: 30)
        XCTAssertFalse(failed, app.alerts.firstMatch.label)
        keep("Share session")
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testASessionHasOneBackThatReturnsToTheList() {
        app.launchArguments = ["-freshInstall", "-demoSessions"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["BEST LAP"].waitForExistence(timeout: 120), "no summary")
        keep("Session, back")
        let ownBack = app.buttons["showSessions"].firstMatch
        if UIDevice.current.userInterfaceIdiom == .phone {
            // The iPhone's list and session take the screen in turn: only the system back button.
            XCTAssertFalse(ownBack.exists, "a second back button on the iPhone")
            app.navigationBars.buttons.element(boundBy: 0).tap()
        } else {
            XCTAssertTrue(ownBack.waitForExistence(timeout: 10), "no ‹ Sessions on the iPad")
            ownBack.tap()
        }
        let back = NSPredicate(format: "isHittable == true")
        expectation(for: back, evaluatedWith: rows.element(boundBy: 0))
        waitForExpectations(timeout: 15)
    }

    func testASessionWithNoTrackShowsItsDriveOnTheMap() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoNoTrack"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 0).tap()
        // As Android (FBBD-342): the no-laps message, then the drive's figures, route and speed.
        XCTAssertTrue(app.staticTexts["DRIVE · WHOLE SESSION"].waitForExistence(timeout: 120), "no drive card")
        XCTAssertTrue(app.staticTexts["DISTANCE"].exists, "no distance")
        let map = app.descendants(matching: .any).matching(identifier: "driveMap").firstMatch
        XCTAssertTrue(map.exists, "no route")
        map.tap()
        keep("Drive, no laps")
        app.swipeUp()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "driveSpeedChart").firstMatch.waitForExistence(timeout: 10), "no speed chart")
        keep("Drive speed, no laps")
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testCsvAndGpxExportsAreWrittenWithoutAFailure() {
        // One launch per format: Files' Save sheet runs out of the app, so the test leaves it open
        // and starts again. Kotlin's DemoSessionsTest checks what the files hold.
        for (index, format) in ["CSV", "GPX"].enumerated() {
            app.launchArguments = index == 0 ? ["-freshInstall", "-demoSessions"] : ["-demoSessions"]
            app.launch()
            openSessions()
            let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
            XCTAssertTrue(rows.element(boundBy: 0).waitForExistence(timeout: 120), "no demo sessions")
            rows.element(boundBy: 0).tap()
            XCTAssertTrue(app.staticTexts["BEST LAP"].waitForExistence(timeout: 120), "no summary")
            let menu = app.buttons["exportMenu"].firstMatch
            XCTAssertTrue(menu.waitForExistence(timeout: 10), "no export menu")
            menu.tap()
            app.buttons[format].firstMatch.tap()
            // A failed export says so in an alert; a written one goes on to Files.
            let failed = app.alerts.firstMatch.waitForExistence(timeout: 15)
            XCTAssertFalse(failed, "\(format): \(app.alerts.firstMatch.label)")
            keep("Export \(format)")
            XCTAssertEqual(app.state, .runningForeground, "\(format): the app stopped")
        }
    }

    func testTheTrackDayOfTwoSessionsAtOneTrackOpens() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoTrackDay"]
        app.launch()
        openSessions()
        let link = app.buttons["trackDayLink"].firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 120), "no Track day link")
        link.tap()
        // The day is read off the main thread for a while. On a busy CI simulator a query made while it
        // reads can time out, so ask every 5 s instead of every second, for up to 3 minutes.
        let perfect = app.staticTexts["PERFECT"].firstMatch
        var shown = false
        for _ in 0..<36 where !shown {
            sleep(5)
            shown = perfect.exists
        }
        XCTAssertTrue(shown, "no track day")
        keep("Track day")
        app.swipeUp()
        keep("Track day, corners")
        XCTAssertEqual(app.state, .runningForeground)
        app.buttons["Done"].firstMatch.tap()
    }

    func testTheDemoSessionsOpenOnEveryTab() {
        // Newest first: Silesia Ring (27 Sep, 18 laps), then Jastrząb (29 Aug, 23 laps), as DemoParityTest.
        for (index, (name, laps)) in [("silesia-ring", "18"), ("jastrzab", "23")].enumerated() {
            // A fresh launch for each, so the list shows; the first starts empty and adds the demos.
            app.launchArguments = index == 0 ? ["-freshInstall", "-demoSessions"] : ["-demoSessions"]
            app.launch()
            openSessions()
            let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
            XCTAssertTrue(rows.element(boundBy: index).waitForExistence(timeout: 120), "no session row \(index)")
            rows.element(boundBy: index).tap()
            // Figure writes its label in capitals.
            XCTAssertTrue(app.staticTexts["BEST LAP"].waitForExistence(timeout: 120), "\(name): no summary")
            XCTAssertTrue(app.staticTexts[laps].exists, "\(name): not \(laps) laps")
            for tab in ["Summary", "Compare", "Map", "Laps", "Corners", "Car"] {
                app.buttons["tab\(tab)"].tap()
                // The tab's numbers come off the main thread.
                sleep(3)
                XCTAssertEqual(app.state, .runningForeground, "\(name) \(tab): the app stopped")
                keep("\(name) \(tab)")
            }
        }
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
