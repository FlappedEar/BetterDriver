import XCTest

/// Corner corrections in the track editor (FBBD-366, Android's FBBD-179) on the iPad and iPhone
/// simulators: once the progression has analysed the Jastrząb demo sessions, the track's corners are
/// listed in its editor; Split makes a corner of two and marks it Edited, and Restore automatic
/// brings the corners found from the laps back. A screenshot of each stays in the test results.
final class TrackCornersTests: XCTestCase {
    private let app = XCUIApplication()
    private var device: String { UIDevice.current.userInterfaceIdiom == .phone ? "iPhone" : "iPad" }

    override func setUp() {
        continueAfterFailure = false
        // Every session at the track is analysed on a busy CI simulator: the most xcodebuild allows.
        executionTimeAllowance = 600
        addUIInterruptionMonitor(withDescription: "Permission prompt") { alert in
            let allow = alert.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Allow'")).firstMatch
            (allow.exists ? allow : alert.buttons.firstMatch).tap()
            return true
        }
    }

    func testTheTracksCornersAreCorrectedInTheEditorAndRestored() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoTrackDay"]
        app.launch()
        analyseTheJastrzabSessions()
        openTracks()
        let track = app.buttons.matching(identifier: "trackRow")
            .matching(NSPredicate(format: "label CONTAINS 'Jastrz'")).firstMatch
        XCTAssertTrue(track.waitForExistence(timeout: 30), "no Jastrząb track")
        track.tap()
        let first = app.buttons["segment-c1"].firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 60), "no corners in the editor")
        XCTAssertTrue(app.staticTexts["Found from your laps. Tap one here or on the map to correct it."].exists)
        keep("Track editor, corners")
        first.tap()
        let split = app.buttons["segmentSplit"].firstMatch
        XCTAssertTrue(split.waitForExistence(timeout: 10), "no corner controls")
        XCTAssertTrue(app.buttons["Corner"].firstMatch.exists, "no Corner chip")
        keep("Track editor, corner 1")
        split.tap()
        sleep(2)
        // Stored at once: the corner list comes back with it marked as the driver's.
        let done = app.buttons["segmentDone"].firstMatch
        let stored = NSPredicate(format: "isEnabled == true")
        expectation(for: stored, evaluatedWith: done)
        waitForExpectations(timeout: 30)
        done.tap()
        XCTAssertTrue(app.staticTexts["Edited"].firstMatch.waitForExistence(timeout: 30), "the split was not kept")
        XCTAssertTrue(app.staticTexts["Corrected by you. Every session here is analysed with these."].exists)
        keep("Track editor, corner split")
        app.buttons["Restore automatic"].firstMatch.tap()
        let restore = app.alerts.buttons["Restore"].firstMatch
        XCTAssertTrue(restore.waitForExistence(timeout: 10), "no restore question")
        restore.tap()
        let found = app.staticTexts["Found from your laps. Tap one here or on the map to correct it."]
        XCTAssertTrue(found.waitForExistence(timeout: 30), "the corners were not restored")
        XCTAssertFalse(app.staticTexts["Edited"].exists)
        keep("Track editor, restored")
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// The progression at Jastrząb analyses its two demo sessions, which stores the track's corners.
    private func analyseTheJastrzabSessions() {
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 1).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "no summary")
        let more = app.buttons["sessionMore"].firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 10), "no More menu")
        more.tap()
        app.buttons["Progression at this track"].firstMatch.tap()
        let both = app.staticTexts["2 sessions on 1 day"].firstMatch
        var shown = false
        for _ in 0..<36 where !shown {
            sleep(5)
            shown = both.exists
        }
        XCTAssertTrue(shown, "no progression of the two Jastrząb sessions")
        app.buttons["Done"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 30), "not back on the session")
    }

    /// Tracks: in Settings on an iPhone, a button over the session list on an iPad.
    private func openTracks() {
        let settings = app.tabBars.buttons["Settings"]
        if settings.waitForExistence(timeout: 5) {
            settings.tap()
            let tracks = app.buttons["settings.tracks"].firstMatch
            XCTAssertTrue(tracks.waitForExistence(timeout: 10), "no Tracks in Settings")
            tracks.tap()
            return
        }
        let list = app.buttons["showSessions"].firstMatch
        if list.waitForExistence(timeout: 5) { list.tap() }
        let open = app.buttons["openTracks"].firstMatch
        XCTAssertTrue(open.waitForExistence(timeout: 10), "no Tracks button")
        open.tap()
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
