import SwiftUI

/// Sessions, as Android's (FBBD-40): on the iPad the list stays beside the picked session's review,
/// or beside a hint to pick one, the picked row marked; the list button over the session hides the
/// list so the review takes the whole width, or shows it again (Arek 2026-10-31: the list was stuck
/// beside the session), as on an Android tablet held sideways. On the iPhone the list and the session
/// take the screen in turn. Sessions arrive from Files, AirDrop or another app's Share sheet, or
/// Import session in the list (UI-292 A).
struct RootView: View {
    @State private var model = SessionsModel.shared
    @State private var selection: String?
    @State private var columns: NavigationSplitViewVisibility = .all
    /// The list beside a session hidden by its button; shown again once the session is closed.
    @State private var listHidden = false
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            SessionsListView(model: model, selection: $selection)
                // Android's list beside the analysis: two fifths, between a phone's width and a card's.
                .navigationSplitViewColumnWidth(min: 320, ideal: 380, max: 440)
                // The session's own button hides and shows the list, so no system sidebar button.
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
                    if sizeClass == .regular && selection != nil {
                        ToolbarItem(placement: .topBarLeading) { listButton }
                    }
                }
            }
        }
        .navigationSplitViewStyle(.balanced)
        .onChange(of: selection) { _, id in
            if id == nil { listHidden = false }
            showColumns()
        }
        .onChange(of: listHidden) { showColumns() }
        // Turned or resized, the iPad keeps what the list button picked: only a collapsed split
        // (iPhone) shows one column at a time.
        .onChange(of: columns) { showColumns() }
        .onChange(of: sizeClass) { showColumns() }
        // Track files and backups have their own flows (FBBD-372); the rest are sessions.
        .onOpenURL { url in if !IncomingRoute.take(url) { model.importFiles([url]) } }
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


extension RootView {
    /// The columns on the iPad: the list beside, unless it was hidden over a picked session.
    fileprivate var wanted: NavigationSplitViewVisibility {
        selection != nil && listHidden ? .detailOnly : .all
    }

    fileprivate func showColumns() {
        guard sizeClass == .regular, columns != wanted else { return }
        columns = wanted
    }

    /// Over the session: hides the list beside it, or shows it again, as Android's (same icon role).
    fileprivate var listButton: some View {
        Button { listHidden.toggle() } label: {
            Label(listHidden ? "Show session list" : "Hide session list", systemImage: "sidebar.left")
        }
        .accessibilityIdentifier("toggleSessionList")
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
