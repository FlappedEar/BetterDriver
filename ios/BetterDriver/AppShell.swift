import SwiftUI
import UIKit

/// The app's top level, as Android's bottom bar and tablet rail (UI-282 B, UI-136 B, FBBD-40): Drive
/// records, Sessions holds the sessions and their review, Car is the next session's car, Settings
/// holds the equipment, cars, tracks and coach (FBBD-338). The iPad only reviews, so it has no Drive
/// (Arek, 2026-10-30: "no recording on tablets, the rest for analysis stays").
struct AppShell: View {
    @StateObject private var recorder = RecordingController()
    @StateObject private var settings = SettingsModel.shared

    var body: some View {
        TabView {
            if UIDevice.current.userInterfaceIdiom == .phone {
                DriveView()
                    .tabItem { Label("Drive", systemImage: "steeringwheel") }
            }
            RootView()
                .tabItem { Label("Sessions", systemImage: "list.bullet.rectangle") }
            CarHome()
                .tabItem { Label("Car", systemImage: "car") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .environmentObject(recorder)
        .environmentObject(settings)
    }
}
