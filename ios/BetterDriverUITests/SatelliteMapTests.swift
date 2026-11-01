import XCTest

/// Apple's satellite photo on iOS (FBBD-381), as Android's satellite layers, on the iPad and iPhone
/// simulators: the track editor opens on Satellite and switches to Map and back; the perfect lap in 3D
/// opens with the photo under the road where MapKit gives one (the drawn road without it) and nothing
/// crashes either way. A screenshot of each stays in the test results.
final class SatelliteMapTests: XCTestCase {
    private let app = XCUIApplication()
    private var device: String { UIDevice.current.userInterfaceIdiom == .phone ? "iPhone" : "iPad" }

    override func setUp() {
        continueAfterFailure = false
        executionTimeAllowance = 600
        addUIInterruptionMonitor(withDescription: "Permission prompt") { alert in
            let allow = alert.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Allow'")).firstMatch
            (allow.exists ? allow : alert.buttons.firstMatch).tap()
            return true
        }
    }

    func testTheTrackEditorAndThe3dLapShowApplesSatellitePhoto() {
        app.launchArguments = ["-freshInstall", "-demoSessions"]
        app.launch()
        openSessions()
        // Newest first: Silesia Ring, then Jastrząb.
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 1).waitForExistence(timeout: 120), "no demo sessions")

        openTracks()
        let track = app.buttons.matching(identifier: "trackRow")
            .matching(NSPredicate(format: "label CONTAINS 'Jastrz'")).firstMatch
        XCTAssertTrue(track.waitForExistence(timeout: 30), "no Jastrząb track")
        track.tap()
        let picker = app.buttons["mapPicker"].firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 60), "no track editor")
        XCTAssertTrue(picker.label.contains("Satellite"), "the editor does not open on Satellite: \(picker.label)")
        sleep(3)
        keep("Track editor, satellite")
        pick("Map", on: picker)
        XCTAssertTrue(picker.label.contains("Map: Map"), "Map was not picked: \(picker.label)")
        sleep(2)
        keep("Track editor, map")
        pick("Satellite", on: picker)
        XCTAssertTrue(picker.label.contains("Satellite"), "Satellite was not picked again: \(picker.label)")
        app.buttons["editorBack"].firstMatch.tap()
        XCTAssertTrue(track.waitForExistence(timeout: 10), "the editor did not close")

        openSessions()
        XCTAssertTrue(rows.element(boundBy: 1).waitForExistence(timeout: 30), "no demo sessions")
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "no summary")
        app.buttons["tabMap"].firstMatch.tap()
        let line = app.buttons["mapViewLine"].firstMatch
        XCTAssertTrue(line.waitForExistence(timeout: 30), "no Map tab")
        line.tap()
        let play = app.buttons["perfectLapButton"].firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 120), "no perfect lap in 3D")
        play.tap()
        let player = app.descendants(matching: .any).matching(identifier: "perfectLapPlayer").firstMatch
        XCTAssertTrue(player.waitForExistence(timeout: 30), "no 3D player")
        // Time for the first snapshots of the photo to arrive, or fail without signal.
        sleep(8)
        keep("Perfect lap in 3D, satellite")
        XCTAssertEqual(app.state, .runningForeground)
        // By its label: the player's own identifier on its stack is what the Back button reports too.
        let back = app.buttons.matching(NSPredicate(format: "identifier == 'perfectLapBack' OR label == '‹ Back'")).firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 10), "no Back on the 3D player")
        back.tap()
        XCTAssertTrue(play.waitForExistence(timeout: 10), "the 3D player did not close")
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// Google's photo (FBBD-411), with the app's stub Google (-stubSatellite: tiles in two colours with
    /// their address on them, no network): the editor opens on it with its credit, and the perfect lap
    /// in 3D draws its tiles under the road with Google's name on the picture.
    func testWithAGoogleKeyTheEditorAndThe3dLapShowGooglesTiles() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-stubSatellite"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: 1).waitForExistence(timeout: 120), "no demo sessions")

        openTracks()
        let track = app.buttons.matching(identifier: "trackRow")
            .matching(NSPredicate(format: "label CONTAINS 'Jastrz'")).firstMatch
        XCTAssertTrue(track.waitForExistence(timeout: 30), "no Jastrząb track")
        track.tap()
        let picker = app.buttons["mapPicker"].firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 60), "no track editor")
        XCTAssertTrue(picker.label.contains("Satellite (Google)"), "the editor does not open on Google: \(picker.label)")
        let credit = app.staticTexts["mapCredit"].firstMatch
        XCTAssertTrue(credit.waitForExistence(timeout: 10), "no credit on Google's photo")
        XCTAssertTrue(credit.label.contains("Google Maps"), "credit: \(credit.label)")
        sleep(3)
        keep("Track editor, Google satellite (stub)")
        pick("Satellite", on: picker)
        XCTAssertFalse(picker.label.contains("Google"), "Apple's photo was not picked: \(picker.label)")
        XCTAssertFalse(credit.exists, "Google's credit stays on Apple's photo")
        app.buttons["editorBack"].firstMatch.tap()
        XCTAssertTrue(track.waitForExistence(timeout: 10), "the editor did not close")

        openSessions()
        XCTAssertTrue(rows.element(boundBy: 1).waitForExistence(timeout: 30), "no demo sessions")
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "no summary")
        app.buttons["tabMap"].firstMatch.tap()
        let line = app.buttons["mapViewLine"].firstMatch
        XCTAssertTrue(line.waitForExistence(timeout: 30), "no Map tab")
        line.tap()
        let play = app.buttons["perfectLapButton"].firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 120), "no perfect lap in 3D")
        play.tap()
        let picture = app.descendants(matching: .any).matching(identifier: "perfectLapPicture").firstMatch
        XCTAssertTrue(picture.waitForExistence(timeout: 30), "no 3D picture")
        let google = NSPredicate(format: "value CONTAINS 'Google Maps'")
        expectation(for: google, evaluatedWith: picture)
        waitForExpectations(timeout: 60)
        keep("Perfect lap in 3D, Google satellite (stub)")
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// Picks [name] from the editor's map menu.
    private func pick(_ name: String, on picker: XCUIElement) {
        let item = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@ OR label == %@", name, "✓ \(name)")).firstMatch
        // A tap at the button's point, again while the menu is not up: as the session More menu's tests,
        // the menu can close while it opens.
        for _ in 0..<4 where !(item.exists && item.isHittable) {
            picker.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            _ = item.waitForExistence(timeout: 5)
        }
        XCTAssertTrue(item.exists, "no \(name) in the map menu")
        keep("Track editor, map menu")
        item.tap()
        sleep(1)
    }

    private func openTracks() {
        let bottom = app.tabBars.buttons["Settings"]
        if bottom.waitForExistence(timeout: 5) { bottom.tap() } else { app.buttons["Settings"].firstMatch.tap() }
        let tracks = app.buttons["settings.tracks"].firstMatch
        XCTAssertTrue(tracks.waitForExistence(timeout: 10), "no Tracks in Settings")
        tracks.tap()
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
