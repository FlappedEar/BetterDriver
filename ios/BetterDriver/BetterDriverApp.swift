import SwiftUI

/// BetterDriver for iPhone and iPad (FBBD-328). The analysis, timing and coach logic comes
/// from the shared Kotlin core (BetterDriverCore); this app is the native UI around it.
@main
struct BetterDriverApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    /// A restore that failed after its commit closes the app until it is finished (FBBD-476).
    @StateObject private var restore = RestoreGate.shared

    init() {
        UITestMode.resetIfAsked()
        // Copies left by an earlier run's shares and exports, video included, free their space (FBBD-467).
        LeftoverCopies.sweep()
        // The Health-like bars (UI-307 A, FBBD-421), before the first screen draws them.
        HealthStyle.applyBars()
        // Before anything reads the data: a restore stopped after its commit is finished (FBBD-372).
        // If it cannot be, nothing reads the data until Try again finishes it (review v1.34.1 F2).
        RestoreGate.shared.check(then: Self.startData)
    }

    /// What reads the data at launch, once no restore is unfinished.
    private static func startData() {
        // Before Settings reads setup.json: an install from before the checklist counts as set up.
        FirstRunGate.shared.decideAtLaunch()
        // The chosen devices' Bluetooth links stay open from now until the app closes (Arek 2026-11-05).
        HeldConnections.shared.follow(SettingsModel.shared)
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if restore.blocked {
                    RestoreUnfinishedView(gate: restore)
                } else {
                    AppShell()
                }
            }
            .preferredColorScheme(.dark)
            .tint(Palette.accent)
        }
    }
}
