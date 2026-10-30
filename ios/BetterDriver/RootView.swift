import SwiftUI

/// The app shell (FBBD-331, UI-290 B): a picked session fills the screen; ‹ Sessions closes it and
/// brings back the whole session list, beside the empty page, to pick or add another. On an iPhone the list and the session
/// take the screen in turn. Sessions arrive from Files, AirDrop or another app's Share sheet, or + in
/// the list (UI-292 A).
struct RootView: View {
    @State private var model = SessionsModel()
    @State private var selection: String?
    @State private var columns: NavigationSplitViewVisibility = .all
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            SessionsListView(model: model, selection: $selection)
                // iPadOS 26 puts its own sidebar button in the detail too: with ‹ Sessions beside it
                // there were two buttons doing the same (Arek, TestFlight 1441).
                .toolbar(removing: .sidebarToggle)
        } detail: {
            NavigationStack {
                Group {
                    if let id = selection, let session = model.row(id) {
                        SessionView(session: session, revision: model.revision).id(id)
                    } else {
                        PickSession()
                    }
                }
                .toolbar {
                    // Collapsed (iPhone, narrow iPad window) the system back button returns to the
                    // list and columns do nothing: a second ‹ there was dead (Arek, TestFlight).
                    if sizeClass == .regular && columns == .detailOnly {
                        ToolbarItem(placement: .topBarLeading) {
                            // Back to all sessions, as on Android: the session closes, the list
                            // comes back beside the empty page (Arek, TestFlight 1441).
                            Button {
                                selection = nil
                                columns = .all
                            } label: {
                                Label("Sessions", systemImage: "chevron.left").labelStyle(.titleAndIcon)
                            }
                            .accessibilityIdentifier("showSessions")
                        }
                    }
                }
            }
        }
        // Side by side also in portrait: an overlay list over the session hid it again on any tap.
        .navigationSplitViewStyle(.balanced)
        .toolbar(removing: .sidebarToggle)
        .onChange(of: selection) { _, id in
            if id != nil { columns = .detailOnly }
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
            Text("Pick a session to review")
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
