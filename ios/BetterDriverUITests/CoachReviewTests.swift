import XCTest

/// The coach review (FBBD-99) on the iPad and iPhone simulators, as Android's: the Jastrząb demo
/// driven with the spoken coach on (-demoCues) offers "Coach review" with its cues from its row and from
/// the session's More menu, and the review lists the cues. A screenshot of each stays in the results.
final class CoachReviewTests: XCTestCase {
    private let app = XCUIApplication()
    private var device: String { UIDevice.current.userInterfaceIdiom == .phone ? "iPhone" : "iPad" }
    /// Newest first: Silesia Ring, then the coached Jastrząb.
    private let jastrzab = 1

    override func setUp() {
        continueAfterFailure = false
        addUIInterruptionMonitor(withDescription: "Permission prompt") { alert in
            let allow = alert.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Allow'")).firstMatch
            (allow.exists ? allow : alert.buttons.firstMatch).tap()
            return true
        }
    }

    func testTheCoachReviewOpensFromTheSessionsMoreMenu() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoCues"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: jastrzab).waitForExistence(timeout: 120), "no demo sessions")
        rows.element(boundBy: jastrzab).tap()
        XCTAssertTrue(app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120), "no summary")
        // The cue counts are read off the main thread after the list: ask the menu again until it has them.
        let more = app.buttons["sessionMore"].firstMatch
        let item = coachReviewItem()
        var offered = false
        for _ in 0..<12 where !offered {
            more.tap()
            offered = item.waitForExistence(timeout: 5)
            if !offered { app.tap() }
        }
        XCTAssertTrue(offered, "no Coach review in the More menu")
        keep("Session More, coach review")
        tapMenuItem(item)
        reviewShows("Coach review")
        app.buttons["Done"].firstMatch.tap()
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testTheCoachReviewOpensFromTheSessionRowOnlyWithCues() {
        app.launchArguments = ["-freshInstall", "-demoSessions", "-demoCues"]
        app.launch()
        openSessions()
        let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
        XCTAssertTrue(rows.element(boundBy: jastrzab).waitForExistence(timeout: 120), "no demo sessions")
        // Silesia Ring was driven without the coach: as on Android, no Coach review there.
        sleep(5)
        rows.element(boundBy: 0).press(forDuration: 1.2)
        XCTAssertTrue(app.buttons["Share"].firstMatch.waitForExistence(timeout: 10), "no session menu")
        XCTAssertFalse(coachReviewItem().exists, "Coach review on a session with no cues")
        app.tap()
        // The cue counts are read off the main thread after the list: ask the menu again until it has them.
        let item = coachReviewItem()
        var offered = false
        // The rows carry a detail line now (Android's): on an iPhone the coached session can be below the fold.
        let coached = rows.element(boundBy: jastrzab)
        for _ in 0..<4 where !coached.isHittable { app.swipeUp() }
        for _ in 0..<12 where !offered {
            coached.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5)).press(forDuration: 1.2)
            offered = item.waitForExistence(timeout: 5)
            if !offered { app.tap() }
        }
        XCTAssertTrue(offered, "no Coach review on the coached session")
        keep("Session row, coach review")
        tapMenuItem(item)
        reviewShows("Coach review, from the list")
        app.buttons["Done"].firstMatch.tap()
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// A menu item is tapped where it is: on a busy CI simulator XCUITest's scroll-to-visible on it
    /// can time out (kAXErrorCannotComplete) while the menu is already on screen.
    private func tapMenuItem(_ item: XCUIElement) {
        sleep(1)
        item.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }

    /// "Coach review" with its cue count under it.
    private func coachReviewItem() -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Coach review'")).firstMatch
    }

    /// The review lists the session's cues: a summary of them and a card for each.
    private func reviewShows(_ name: String) {
        let card = app.descendants(matching: .any).matching(identifier: "cueCard").firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 60), "no cues in the review")
        let summary = app.staticTexts.matching(NSPredicate(format: "label MATCHES '[0-9]+ cues?.*'")).firstMatch
        XCTAssertTrue(summary.exists, "no cue summary")
        keep(name)
        app.swipeUp()
        keep("\(name), scrolled")
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
