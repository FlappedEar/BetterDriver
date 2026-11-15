import XCTest
import UIKit

/// Every screen shows its title where a title belongs (FBBD-475): TestFlight 1582 on iOS 27 drew
/// no title at the top of any tab, and the Sessions tab's "Sessions" over its first session card.
/// On each tab, and on a page opened from Settings, the title must be on screen, above everything
/// the screen shows under it, and over none of it. Runs on GitHub's iOS 18 simulators (ios.yml) and
/// in the TestFlight gate on the iOS 26 SDK the app ships with (ios-release.yml). Each problem is
/// reported at once, with a screenshot of the screen it is on and the frames in the log.
final class ScreenTitlesTests: XCTestCase {
    private let app = XCUIApplication.quiet()
    private var phone: Bool { UIDevice.current.userInterfaceIdiom == .phone }
    private var problems: [String] = []
    private var tabs: [String] { phone ? ["Drive", "Sessions", "Car", "Settings"] : ["Sessions", "Settings"] }

    override func setUp() {
        continueAfterFailure = true
        app.launchArguments = ["-uiTest", "-uiTestReset"]
        addUIInterruptionMonitor(withDescription: "Permission") { alert in
            for label in ["Allow", "OK", "Allow While Using App"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }
    }

    func testEveryTabAndASettingsPageShowItsTitleAtTheTop() {
        app.launch()
        for name in tabs {
            openTab(name)
            sleep(1)
            check(screen: name, title: name)
        }
        // A page one level down keeps its title too.
        openTab("Settings")
        let video = app.buttons["settings.video"].firstMatch
        if video.waitForExistence(timeout: 10) {
            video.tap()
            sleep(1)
            check(screen: "Settings › Video", title: "Video")
        } else {
            problems.append("Settings: no Video row")
        }
        if !problems.isEmpty {
            XCTFail("\(problems.count) title problems on the \(phone ? "iPhone" : "iPad"):\n" + problems.joined(separator: "\n"))
        }
    }

    private struct Node {
        let type: XCUIElement.ElementType
        let label: String
        let frame: CGRect
        let inTabBar: Bool
        let inNavigationBar: Bool
    }

    /// The screen's title: shown, inside the window, above every text and button the screen shows
    /// under it (in the title's column) and over none of them.
    private func check(screen: String, title: String) {
        keep(screen)
        guard let root = try? app.snapshot() else {
            problems.append("\(screen): no snapshot of the screen")
            return
        }
        let window = root.frame
        var nodes: [Node] = []
        walk(root, inTabBar: false, inNavigationBar: false) { nodes.append($0) }
        let shown = nodes.filter {
            $0.frame.width >= 1 && $0.frame.height >= 1 && window.contains($0.frame.insetBy(dx: 1, dy: 1))
        }
        // Not a tab's own name: the iPad's tabs are buttons in a bar at the top.
        let tabButtons = shown.filter { $0.type == .button && $0.label == title }.map(\.frame)
        let titles = shown.filter { node in
            node.type == .staticText && node.label == title && !node.inTabBar
                && !tabButtons.contains { $0.contains(node.frame.insetBy(dx: 1, dy: 1)) }
        }
        guard let titleNode = titles.min(by: { $0.frame.minY < $1.frame.minY }) else {
            let all = nodes.filter { $0.type == .staticText && $0.label == title }.map { "\($0.frame)" }
            problems.append("\(screen): title \"\(title)\" not shown (found at \(all))")
            return
        }
        let t = titleNode.frame
        print("TITLES \(screen): \"\(title)\" at \(t), in the navigation bar: \(titleNode.inNavigationBar)")
        // The title's column: what a reader sees under it, not the iPad's session beside the list.
        let column = CGRect(x: t.minX, y: window.minY, width: max(t.width, 240), height: window.height)
        let content = shown.filter {
            !$0.inTabBar && !$0.inNavigationBar && !$0.label.isEmpty && $0.frame != t
                && [.staticText, .button, .switch].contains($0.type)
                && !tabs.contains($0.label) && $0.frame.intersects(column)
        }
        for node in content {
            let overlap = t.intersection(node.frame)
            let smaller = min(t.width * t.height, node.frame.width * node.frame.height)
            if !overlap.isNull, overlap.width * overlap.height > 0.3 * smaller {
                problems.append("\(screen): title \(t) over \"\(node.label.prefix(40))\" \(node.frame)")
            } else if node.frame.minY < t.minY - 1 {
                problems.append("\(screen): \"\(node.label.prefix(40))\" \(node.frame) above the title \(t)")
            }
        }
        // Under the status bar, at the top: not halfway down the screen.
        if t.minY > window.minY + 200 {
            problems.append("\(screen): title \(t) too far down (window \(window))")
        }
    }

    private func walk(_ node: XCUIElementSnapshot, inTabBar: Bool, inNavigationBar: Bool, _ visit: (Node) -> Void) {
        let tabBar = inTabBar || node.elementType == .tabBar
        let navigationBar = inNavigationBar || node.elementType == .navigationBar
        visit(Node(
            type: node.elementType,
            label: node.label.trimmingCharacters(in: .whitespacesAndNewlines),
            frame: node.frame,
            inTabBar: tabBar,
            inNavigationBar: navigationBar
        ))
        for child in node.children { walk(child, inTabBar: tabBar, inNavigationBar: navigationBar, visit) }
    }

    /// A tab: in the tab bar at the bottom, or on an iPad in the bar at the top.
    private func openTab(_ name: String) {
        let bottom = app.tabBars.buttons[name]
        if bottom.waitForExistence(timeout: 10) { bottom.tap(); return }
        let top = app.buttons[name].firstMatch
        if top.waitForExistence(timeout: 5) { top.tap() } else { problems.append("no \(name) tab") }
    }

    private func keep(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Title \(phone ? "iPhone" : "iPad") \(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
