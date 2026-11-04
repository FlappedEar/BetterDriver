import Foundation

/// The app as the UI smoke test runs it on the simulator (ios-release.yml gate): `-uiTest` adds a
/// simulated GPS receiver to the START sheet and skips the place lookup; `-uiTestReset` starts from a
/// fresh install's state (no sessions, tracks, settings or defaults).
enum UITestMode {
    static let on = ProcessInfo.processInfo.arguments.contains("-uiTest")

    /// Every UI test's launch (FBBD-457, XCUIApplication.quiet()): the coach speaks at no volume.
    static let quietVoice = ProcessInfo.processInfo.environment["BD_QUIET_VOICE"] == "1"

    /// Call once at launch, before anything reads the app's files or defaults.
    static func resetIfAsked() {
        guard ProcessInfo.processInfo.arguments.contains("-uiTestReset") else { return }
        let fm = FileManager.default
        let base = AppFolders.base
        for item in (try? fm.contentsOfDirectory(at: base, includingPropertiesForKeys: nil)) ?? [] {
            try? fm.removeItem(at: item)
        }
        if let id = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: id)
        }
    }
}
