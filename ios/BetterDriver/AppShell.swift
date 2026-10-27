import SwiftUI
import UIKit

/// The app's top level. On the iPhone a tab bar (UI-282 B): Drive records, Sessions holds the
/// sessions and their review. The iPad shows the review shell alone (FBBD-331).
struct AppShell: View {
    @StateObject private var recorder = RecordingController()

    var body: some View {
        if UIDevice.current.userInterfaceIdiom == .phone {
            TabView {
                DriveView()
                    .tabItem { Label("Drive", systemImage: "steeringwheel") }
                RootView()
                    .tabItem { Label("Sessions", systemImage: "list.bullet.rectangle") }
            }
            .environmentObject(recorder)
        } else {
            RootView()
        }
    }
}
