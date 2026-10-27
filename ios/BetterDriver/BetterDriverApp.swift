import SwiftUI

/// BetterDriver for iPhone and iPad (FBBD-328). The analysis, timing and coach logic comes
/// from the shared Kotlin core (BetterDriverCore); this app is the native UI around it.
@main
struct BetterDriverApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            AppShell()
                .preferredColorScheme(.dark)
                .tint(Palette.accent)
        }
    }
}
