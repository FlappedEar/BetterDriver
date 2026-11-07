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
        // Copies left by an earlier run's shares and exports, video included, free their space (FBBD-467).
        LeftoverCopies.sweep()
        // Before Settings reads setup.json: an install from before the checklist counts as set up.
        FirstRunGate.shared.decideAtLaunch()
        // The Health-like bars (UI-307 A, FBBD-421), before the first screen draws them.
        HealthStyle.applyBars()
        // The chosen devices' Bluetooth links stay open from now until the app closes (Arek 2026-11-05).
        HeldConnections.shared.follow(SettingsModel.shared)
    }

    var body: some Scene {
        WindowGroup {
            AppShell()
                .preferredColorScheme(.dark)
                .tint(Palette.accent)
        }
    }
}
