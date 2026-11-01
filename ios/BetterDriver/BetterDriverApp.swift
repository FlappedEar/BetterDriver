import SwiftUI

/// BetterDriver for iPhone and iPad (FBBD-328). The analysis, timing and coach logic comes
/// from the shared Kotlin core (BetterDriverCore); this app is the native UI around it.
@main
struct BetterDriverApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        UITestMode.resetIfAsked()
        // Before anything reads the data: a restore stopped after its commit is finished (FBBD-372).
        BackupBridge.finishOrClear()
    }

    var body: some Scene {
        WindowGroup {
            AppShell()
                .preferredColorScheme(.dark)
                .tint(Palette.accent)
        }
    }
}
