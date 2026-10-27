import SwiftUI
import UIKit

/// The app's top level, the Android app's bottom bar and tablet rail (UI-282 B, UI-136 B) on the
/// iPhone and the iPad alike: Drive records, Sessions holds the sessions and their review, Car is the
/// next session's car, Settings holds the equipment, cars, tracks and coach (FBBD-338).
struct AppShell: View {
    @StateObject private var recorder = RecordingController()
    @StateObject private var settings = SettingsModel.shared

    var body: some View {
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
    }
}
