import SwiftUI

/// The app shell (FBBD-331, UI-290 B): a picked session fills the screen; ‹ Sessions or a swipe
/// from the left edge brings the session list back over it. On an iPhone the list and the session
/// take the screen in turn. Sessions arrive from Files, AirDrop or another app's Share sheet, or + in
/// the list (UI-292 A).
struct RootView: View {
    @State private var model = SessionsModel()
    @State private var selection: String?
    @State private var columns: NavigationSplitViewVisibility = .all

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            SessionsListView(model: model, selection: $selection)
        } detail: {
            NavigationStack {
                Group {
                    if let id = selection, let session = model.row(id) {
                        SessionView(session: session).id(id)
                    } else {
                        PickSession()
                    }
                }
                .toolbar {
                    if columns == .detailOnly {
                        ToolbarItem(placement: .topBarLeading) {
                            Button { columns = .all } label: {
                                Label("Sessions", systemImage: "chevron.left").labelStyle(.titleAndIcon)
                            }
                            .accessibilityIdentifier("showSessions")
                        }
                    }
                }
            }
        }
        .navigationSplitViewStyle(.prominentDetail)
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
