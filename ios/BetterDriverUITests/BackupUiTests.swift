import XCTest

/// Back up and Restore in Settings › App, Tracks › Import and the track editor's Share (FBBD-372), as
/// on Android: each reaches its dialog or sheet with the app still running. What the files hold and
/// how a restore merges is BackupTests' (unit tests) and the JVM's BackupInteropTest.
final class BackupUiTests: XCTestCase {
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

    override func tearDown() {
        app.terminate()
        super.tearDown()
    }

    func testBackUpAsksAboutSessionsAndRestoreIsThere() {
        app.launchArguments = ["-freshInstall"]
        app.launch()
        openSettings()
        let appTopic = app.buttons["settings.app"].firstMatch
        XCTAssertTrue(appTopic.waitForExistence(timeout: 10), "no App in Settings")
        appTopic.tap()
        let backUp = app.buttons["backUp"].firstMatch
        XCTAssertTrue(backUp.waitForExistence(timeout: 10), "no Back up in Settings › App")
        XCTAssertTrue(app.buttons["restore"].firstMatch.exists, "no Restore in Settings › App")
        keep("Settings App, Back up and Restore")
        backUp.tap()
        XCTAssertTrue(app.switches["includeSessions"].firstMatch.waitForExistence(timeout: 10), "no Include sessions")
        XCTAssertTrue(app.staticTexts["No sessions yet"].waitForExistence(timeout: 30), "sessions not counted")
        XCTAssertTrue(app.buttons["chooseFile"].firstMatch.exists, "no Choose file")
        keep("Back up dialog")
        app.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(backUp.waitForExistence(timeout: 10))
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testTracksImportAndTheEditorsShareAreThere() {
        app.launchArguments = ["-freshInstall", "-demoSessions"]
        app.launch()
        openSettings()
        let tracks = app.buttons["settings.tracks"].firstMatch
        XCTAssertTrue(tracks.waitForExistence(timeout: 10), "no Tracks in Settings")
        tracks.tap()
        XCTAssertTrue(app.buttons["importTrack"].firstMatch.waitForExistence(timeout: 10), "no Import in Tracks")
        let row = app.buttons.matching(identifier: "trackRow").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 60), "no saved demo track")
        keep("Tracks with Import")
        row.tap()
        let share = app.buttons["shareTrack"].firstMatch
        XCTAssertTrue(share.waitForExistence(timeout: 60), "no Share in the track editor")
        share.tap()
        assertShareSheet("Share track")
    }

    private func openSettings() {
        let bottom = app.tabBars.buttons["Settings"]
        let settings = bottom.waitForExistence(timeout: 30) ? bottom : app.buttons["Settings"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 30), "no Settings tab")
        settings.tap()
    }

    /// The system share sheet over the app, which keeps running (as ShareEntryPointsTests checks it).
    private func assertShareSheet(_ name: String, timeout: TimeInterval = 60) {
        let signs = [
            app.otherElements["ActivityListView"].firstMatch,
            app.descendants(matching: .any).matching(identifier: "ActivityListView").firstMatch,
            app.buttons["Copy"].firstMatch,
            app.cells["Copy"].firstMatch,
            app.buttons["Save to Files"].firstMatch,
            app.cells["Save to Files"].firstMatch,
        ]
        var shown = false
        let deadline = Date().addingTimeInterval(timeout)
        while !shown && Date() < deadline && app.state == .runningForeground {
            shown = signs.contains { $0.exists }
            if !shown { sleep(2) }
        }
        keep(name)
        XCTAssertEqual(app.state, .runningForeground, "\(name): the app stopped")
        XCTAssertTrue(shown, "\(name): no share sheet")
    }

    private func keep(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "\(device) \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
