import SwiftUI

/// BetterDriver for iPhone and iPad (FBBD-328). The analysis, timing and coach logic comes
/// from the shared Kotlin core (BetterDriverCore); this app is the native UI around it.
@main
struct BetterDriverApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        UITestMode.resetIfAsked()
        // Before Settings reads setup.json: an install from before the checklist counts as set up.
        FirstRunGate.shared.decideAtLaunch()
    }

    var body: some Scene {
        WindowGroup {
            AppShell()
                .preferredColorScheme(.dark)
                .tint(Palette.accent)
        }
    }
}
