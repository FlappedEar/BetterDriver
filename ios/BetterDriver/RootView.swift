import SwiftUI

/// Sessions, as Android's (FBBD-40): on the iPad the list stays beside the picked session's review,
/// or beside a hint to pick one, the picked row marked; on the iPhone the list and the session take
/// the screen in turn. Sessions arrive from Files, AirDrop or another app's Share sheet, or Import
/// session in the list (UI-292 A).
struct RootView: View {
    @State private var model = SessionsModel.shared
    @State private var selection: String?
    @State private var columns: NavigationSplitViewVisibility = .all
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            SessionsListView(model: model, selection: $selection)
                // Android's list beside the analysis: two fifths, between a phone's width and a card's.
                .navigationSplitViewColumnWidth(min: 320, ideal: 380, max: 440)
                // The list never hides on the iPad, so no sidebar button (iPadOS 26 adds one here).
                .toolbar(removing: .sidebarToggle)
        } detail: {
            NavigationStack {
                if let id = selection, let session = model.row(id) {
                    SessionView(session: session, revision: model.revision).id(id)
                } else {
                    PickSession()
                }
            }
        }
        .navigationSplitViewStyle(.balanced)
        // Turned or resized, the iPad keeps the list: only a collapsed split (iPhone) shows one at a time.
        .onChange(of: columns) { _, now in
            if sizeClass == .regular && now != .all { columns = .all }
        }
        .onOpenURL { url in model.importFiles([url]) }
        .confirmationDialog(
            "This session is already here",
            isPresented: Binding(get: { model.duplicate != nil }, set: { if !$0 { model.resolveDuplicate(replace: false) } }),
            titleVisibility: .visible
        ) {
            Button("Replace it") { model.resolveDuplicate(replace: true) }
            Button("Keep the one here", role: .cancel) { model.resolveDuplicate(replace: false) }
        } message: {
            Text(model.duplicate.map { "\($0.name) holds a session you already have." } ?? "")
        }
        .confirmationDialog(
            "The track in this file has changed",
            isPresented: Binding(get: { model.trackChange != nil }, set: { if !$0 { model.resolveTrack(replace: false) } }),
            titleVisibility: .visible
        ) {
            Button("Use the track from the file") { model.resolveTrack(replace: true) }
            Button("Keep the track here", role: .cancel) { model.resolveTrack(replace: false) }
        } message: {
            Text(model.trackChange.map { "\($0.track) in this file has other lines or corners than yours. Every session at this track is timed with the one you keep." } ?? "")
        }
        .alert(
            model.message ?? "",
            isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })
        ) {
            Button("OK", role: .cancel) {}
        }
    }
}

private struct PickSession: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("BetterDriver")
                .font(.largeTitle.weight(.semibold))
                .foregroundStyle(Palette.onBackground)
            Text("Pick a session on the left to review it, or import one")
                .foregroundStyle(Palette.onSurfaceVariant)
            Text("Core \(CoreBridge.version)")
                .font(.footnote.monospacedDigit())
                .foregroundStyle(Palette.onSurfaceVariant)
                .accessibilityIdentifier("coreVersion")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.background)
    }
}

#Preview {
    RootView().preferredColorScheme(.dark)
}
