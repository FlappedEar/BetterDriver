import XCTest

/// Every screen of both tracks' demo sessions as Arek reads them, on the iPhone and the iPad
/// (FBBD-396): what each shows must be readable, not only there. On each tab of each session, the
/// whole Summary scrolled through, Settings and its Diagnostics, every text on screen is checked:
/// - not squeezed: a text with far less room than its words need is cut off or wrapped to a column
///   of letters (the iPad's Summary descriptions of TestFlight 1446);
/// - not over another text: two texts drawn on top of each other are unreadable.
/// Summary's best lap must be the one the shared core works out (DemoParityTest): Jastrząb lap 18
/// in 1:49.91, Silesia Ring lap 14 in 2:13.78. Every problem is reported at once, with a
/// screenshot of the screen it is on.
final class ScreenLayoutTests: XCTestCase {
    private let app = XCUIApplication()
    private var device: String { UIDevice.current.userInterfaceIdiom == .phone ? "iPhone" : "iPad" }
    private var problems: [String] = []

    override func setUp() {
        continueAfterFailure = true
        addUIInterruptionMonitor(withDescription: "Permission prompt") { alert in
            let allow = alert.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Allow'")).firstMatch
            (allow.exists ? allow : alert.buttons.firstMatch).tap()
            return true
        }
    }

    func testEveryScreenOfBothTracksIsReadable() {
        // Newest first: Silesia Ring (27 Sep), then Jastrząb (29 Aug), as ReviewScreensTests.
        let demos = [("Silesia Ring", "2:13.78", "Lap 14"), ("Jastrząb", "1:49.91", "Lap 18")]
        for (index, (name, best, lap)) in demos.enumerated() {
            app.launchArguments = index == 0 ? ["-freshInstall", "-demoSessions"] : ["-demoSessions"]
            app.launch()
            openTab("Sessions")
            let rows = app.descendants(matching: .any).matching(identifier: "sessionRow")
            guard rows.element(boundBy: index).waitForExistence(timeout: 120) else {
                problems.append("\(name): no session row")
                continue
            }
            check("\(name) Sessions")
            rows.element(boundBy: index).tap()
            guard app.staticTexts["BEST ACTUAL LAP"].waitForExistence(timeout: 120) else {
                problems.append("\(name): no Summary")
                continue
            }
            // The tab's numbers come off the main thread.
            sleep(3)
            let times = app.descendants(matching: .any).matching(identifier: "lapTimes").firstMatch
            let shown = times.exists ? summaryTexts(times) : []
            if !shown.contains(best) { problems.append("\(name) Summary: best lap \(best) not shown (\(shown))") }
            if !shown.contains(lap) { problems.append("\(name) Summary: \(lap) not shown (\(shown))") }
            // The whole Summary, a screen at a time.
            for page in 0..<6 {
                check("\(name) Summary \(page + 1)")
                app.swipeUp()
            }
            for tab in ["Compare", "Map", "Laps", "Corners", "Car"] {
                let button = app.buttons["tab\(tab)"].firstMatch
                guard button.waitForExistence(timeout: 10) else {
                    problems.append("\(name): no \(tab) tab")
                    continue
                }
                button.tap()
                sleep(3)
                check("\(name) \(tab)")
                app.swipeUp()
                check("\(name) \(tab), scrolled")
            }
            XCTAssertEqual(app.state, .runningForeground, "\(name): the app stopped")
        }
        openTab("Settings")
        check("Settings")
        let diagnostics = app.buttons["settings.diagnostics"].firstMatch
        if diagnostics.waitForExistence(timeout: 10) {
            for _ in 0..<3 where !diagnostics.isHittable { app.swipeUp() }
            diagnostics.tap()
            _ = app.descendants(matching: .any).matching(identifier: "diagnostics.share").firstMatch.waitForExistence(timeout: 10)
            check("Settings › Diagnostics")
        } else {
            problems.append("Settings: no Diagnostics")
        }
        if !problems.isEmpty {
            XCTFail("\(problems.count) problems on the \(device):\n" + problems.joined(separator: "\n"))
        }
    }

    /// The texts of Summary's lap times card.
    private func summaryTexts(_ card: XCUIElement) -> [String] {
        guard let snapshot = try? card.snapshot() else { return [] }
        var out: [String] = []
        walk(snapshot) { node, _ in
            if node.elementType == .staticText, !node.label.isEmpty { out.append(node.label) }
        }
        return out
    }

    /// One screen's texts against each other and the room they have; a screenshot of it stays.
    private func check(_ screen: String) {
        keep(screen)
        guard let root = try? app.snapshot() else {
            problems.append("\(screen): no snapshot of the screen")
            return
        }
        let window = root.frame
        var texts: [(String, CGRect)] = []
        walk(root) { node, inMap in
            guard node.elementType == .staticText, !inMap else { return }
            let label = node.label.trimmingCharacters(in: .whitespacesAndNewlines)
            let frame = node.frame
            // Only what is wholly on screen: a text half scrolled off is read after the next swipe.
            guard !label.isEmpty, frame.width >= 1, frame.height >= 1, window.contains(frame.insetBy(dx: 1, dy: 1)) else { return }
            texts.append((label, frame))
        }
        for (label, frame) in texts where label.count >= ScreenLayoutTests.squeezeMinLength {
            let perLetter = frame.width * frame.height / CGFloat(label.count)
            if perLetter < ScreenLayoutTests.minAreaPerLetter {
                problems.append("\(screen): squeezed (\(Int(frame.width))×\(Int(frame.height)) pt) \"\(label.prefix(60))\"")
            }
        }
        for i in texts.indices {
            for j in texts.indices where j > i && texts[i].0 != texts[j].0 {
                let a = texts[i].1
                let b = texts[j].1
                let overlap = a.intersection(b)
                guard !overlap.isNull else { continue }
                let smaller = min(a.width * a.height, b.width * b.height)
                if overlap.width * overlap.height > ScreenLayoutTests.maxOverlap * smaller {
                    problems.append("\(screen): \"\(texts[i].0.prefix(40))\" over \"\(texts[j].0.prefix(40))\"")
                }
            }
        }
    }

    /// Every element under [node], and whether it is inside a map (whose labels are the map's own).
    private func walk(_ node: XCUIElementSnapshot, inMap: Bool = false, _ visit: (XCUIElementSnapshot, Bool) -> Void) {
        let map = inMap || node.elementType == .map
        visit(node, map)
        for child in node.children { walk(child, inMap: map, visit) }
    }

    /// A tab: in the tab bar at the bottom, or on an iPad with iOS 18 in the bar at the top.
    private func openTab(_ name: String) {
        let bottom = app.tabBars.buttons[name]
        if bottom.waitForExistence(timeout: 5) { bottom.tap(); return }
        let top = app.buttons[name].firstMatch
        if top.waitForExistence(timeout: 5) { top.tap() }
    }

    private func keep(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "\(device) \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Texts this long are checked for room: a short one ("km/h") may sit in a small box.
    private static let squeezeMinLength = 12

    /// Room per letter, in square points, below which a text cannot show all its letters: an 11 pt
    /// caption letter takes about 6 × 13.
    private static let minAreaPerLetter: CGFloat = 30

    /// The share of the smaller of two texts another may cover.
    private static let maxOverlap: CGFloat = 0.3
}
