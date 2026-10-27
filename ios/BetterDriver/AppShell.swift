import SwiftUI
import UIKit

/// The app's top level. On the iPhone a tab bar (UI-282 B): Drive records, Sessions holds the
/// sessions and their review, Tracks the saved tracks and their editor (FBBD-336). The iPad shows the
/// review shell, with Tracks in its session list (FBBD-331).
struct AppShell: View {
    @StateObject private var recorder = RecordingController()

    var body: some View {
        if UIDevice.current.userInterfaceIdiom == .phone {
            TabView {
                DriveView()
                    .tabItem { Label("Drive", systemImage: "steeringwheel") }
                RootView()
                    .tabItem { Label("Sessions", systemImage: "list.bullet.rectangle") }
                TracksView()
                    .tabItem { Label("Tracks", systemImage: "flag.checkered") }
            }
            .environmentObject(recorder)
        } else {
            RootView()
        }
    }
}
