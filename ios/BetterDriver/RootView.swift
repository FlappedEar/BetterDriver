import SwiftUI

/// The app shell: a sidebar with the sessions on iPad (a stack on iPhone) and the selected
/// session's review. Sessions arrive with the .bdsession import (FBBD-331); its screens are
/// decided in UI review rounds before they are built, so this shell only holds the frame.
struct RootView: View {
    var body: some View {
        NavigationSplitView {
            List {
                Section("Sessions") {
                    Text("No sessions yet")
                        .foregroundStyle(Palette.onSurfaceVariant)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Palette.background)
            .navigationTitle("BetterDriver")
        } detail: {
            VStack(spacing: 12) {
                Text("BetterDriver")
                    .font(.largeTitle.weight(.semibold))
                    .foregroundStyle(Palette.onBackground)
                Text("Core \(CoreBridge.version)")
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .accessibilityIdentifier("coreVersion")
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Palette.background)
        }
    }
}

#Preview {
    RootView().preferredColorScheme(.dark)
}
