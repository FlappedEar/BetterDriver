import SwiftUI
import UIKit

/// The app's top level. On the iPhone a tab bar as Android's bottom bar (UI-282 B, UI-136 B): Drive
/// records, Sessions holds the sessions and their review, Car is the next session's car, Settings
/// holds the equipment, cars, tracks and coach (FBBD-338). The iPad shows the review shell, with
/// Tracks in its session list (FBBD-331, FBBD-336).
struct AppShell: View {
    // Held, not observed: the recorder publishes the track screen 4 times a second, and observing it
    // here rebuilt every tab with it, the session list's model included (FBBD-390). The tabs that show
    // its state observe it themselves.
    private let recorder = RecordingController.shared
    private let settings = SettingsModel.shared

    var body: some View {
        if UIDevice.current.userInterfaceIdiom == .phone {
            // parity-list:top-level
            TabView {
                DriveView()
                    .tabItem { Label("Drive", systemImage: "steeringwheel") }
                RootView()
                    .tabItem { Label("Sessions", systemImage: "list.bullet.rectangle") }
                CarHome()
                    .tabItem { Label("Car", systemImage: "car") }
                SettingsView()
                    .tabItem { Label("Settings", systemImage: "gearshape") }
            }
            .environmentObject(recorder)
            .environmentObject(settings)
        } else {
            RootView()
        }
    }
}
