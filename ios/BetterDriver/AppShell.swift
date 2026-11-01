import SwiftUI
import UIKit

/// The app's top level, as Android's bottom bar and tablet rail (UI-282 B, UI-136 B, FBBD-40): Drive
/// records, Sessions holds the sessions and their review, Car is the next session's car, Settings
/// holds the equipment, cars, tracks and coach (FBBD-338). The iPad only reviews, so it has no Drive and
/// no Car with its live OBD-II values (the shared ReviewOnlyDevice; Arek, 2026-10-30 and 2026-11-01).
struct AppShell: View {
    // Held, not observed: the recorder publishes the track screen 4 times a second, and observing it
    // here rebuilt every tab with it, the session list's model included (FBBD-390). The tabs that show
    // its state observe it themselves.
    private let recorder = RecordingController.shared
    private let settings = SettingsModel.shared
    /// The first-run checklist over the tabs (FBBD-371), as Android's Setup start screen.
    @ObservedObject private var firstRun = FirstRunGate.shared

    var body: some View {
        // parity-list:top-level
        TabView {
            if UIDevice.current.userInterfaceIdiom == .phone {
                DriveView()
                    .tabItem { Label("Drive", systemImage: "steeringwheel") }
            }
            RootView()
                .tabItem { Label("Sessions", systemImage: "list.bullet.rectangle") }
            if UIDevice.current.userInterfaceIdiom == .phone {
                CarHome()
                    .tabItem { Label("Car", systemImage: "car") }
            }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .environmentObject(recorder)
        .environmentObject(settings)
        // A backup or track file opened from Files or AirDrop asks here (FBBD-372).
        .modifier(BackupPrompts())
        .modifier(TrackImportPrompts())
        .fullScreenCover(isPresented: $firstRun.showing) {
            FirstRunPage { firstRun.done() }
                .environmentObject(recorder)
                .environmentObject(settings)
                .preferredColorScheme(.dark)
                .tint(Palette.accent)
        }
    }
}
