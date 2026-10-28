import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Sessions by day (UI-139 C), with + to import files (UI-292 A) and the import's progress.
struct SessionsListView: View {
    @Bindable var model: SessionsModel
    @Binding var selection: String?
    @State private var picking = false
    @State private var showingTracks = false
    @State private var notYet: String?
    @State private var deleting: SessionRow?

    var body: some View {
        List(selection: $selection) {
            if let progress = model.importing {
                ImportCard(progress: progress)
                    .listRowBackground(Palette.surface)
            }
            if model.sessions.isEmpty && model.importing == nil {
                Text("No sessions yet. Open a .bdsession or .vbo from Files, AirDrop or another app, or tap +.")
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .listRowBackground(Palette.background)
            }
            ForEach(model.days) { day in
                Section {
                    ForEach(day.sessions) { session in
                        SessionRowView(session: session)
                            .tag(session.id)
                            // Android's session card buttons: Delete, Share, and the coach's review.
                            .contextMenu {
                                Button("Coach review") { notYet = "Coach review" }
                                Button("Share") { notYet = "Sharing a session" }
                                Button("Delete", role: .destructive) { deleting = session }
                            }
                            .listRowBackground(selection == session.id ? Palette.surfaceVariant : Palette.background)
                    }
                } header: {
                    HStack {
                        Text(dayTitle(day.day, day.sessions))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Palette.onSurfaceVariant)
                        Spacer()
                        // Android's day heading opens the day at its track (FBBD-315).
                        Button("Track day") { notYet = "Track day" }
                            .font(.caption.weight(.semibold))
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Palette.background)
        .navigationTitle("Sessions")
        .toolbar {
            // The iPhone has Tracks as a tab of its own (AppShell).
            if UIDevice.current.userInterfaceIdiom == .pad {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Tracks") { showingTracks = true }
                        .accessibilityIdentifier("openTracks")
                }
            }
            ToolbarItem(placement: .primaryAction) {
                Button { picking = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Import sessions")
                    .accessibilityIdentifier("importSessions")
            }
        }
        .sheet(isPresented: $showingTracks) { TracksView() }
        .notImplementedAlert($notYet)
        .alert(
            "Delete session?",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            presenting: deleting
        ) { session in
            Button("Delete", role: .destructive) {
                if selection == session.id { selection = nil }
                model.delete(session)
            }
            .accessibilityIdentifier("confirmDelete")
            Button("Cancel", role: .cancel) {}
        } message: { session in
            Text(deleteText(session))
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.bdsession, .vbo, .commaSeparatedText], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { model.importFiles(urls) }
        }
    }

    /// Android's delete question (UI-48 A): the session, what goes and how much space it frees.
    private func deleteText(_ session: SessionRow) -> String {
        let title = [session.start.formatted(date: .abbreviated, time: .shortened), session.track]
            .filter { !$0.isEmpty }.joined(separator: " · ")
        let (bytes, video) = ReviewBridge.footprint(session.id)
        let what = video ? "its data and video" : "its data"
        let size = bytes.map { " (\(sizeText($0)))" } ?? ""
        let device = UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
        return "\(title): \(what)\(size) will be removed from this \(device). " +
            "Files still on a GoPro stay there. This can't be undone."
    }

    /// "820 KB", "350 MB", "2.4 GB", as Android.
    private func sizeText(_ bytes: Int64) -> String {
        switch bytes {
        case 1_000_000_000...: return String(format: "%.1f GB", Double(bytes) / 1e9)
        case 1_000_000...: return "\(bytes / 1_000_000) MB"
        default: return "\(max(1, bytes / 1_000)) KB"
        }
    }

    private func dayTitle(_ day: Date, _ sessions: [SessionRow]) -> String {
        let date = day.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
        let tracks = Array(Set(sessions.map(\.track).filter { !$0.isEmpty })).sorted()
        return tracks.isEmpty ? date : "\(date) · \(tracks.joined(separator: ", "))"
    }
}

private struct SessionRowView: View {
    let session: SessionRow

    var body: some View {
        HStack {
            Text(session.start.formatted(date: .omitted, time: .shortened))
                .font(.body.weight(.semibold).monospacedDigit())
                .foregroundStyle(Palette.onBackground)
            Spacer()
            if let d = session.durationSeconds {
                Text("\(Int(d / 60)) min")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(Palette.onSurfaceVariant)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("sessionRow")
    }
}

/// Import progress: MB done of the file's size, percent and time left (UI-192 B).
private struct ImportCard: View {
    let progress: SessionsModel.ImportProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Importing \(progress.name)")
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .truncationMode(.middle)
            ProgressView(value: fraction)
                .tint(Palette.accent)
            Text(detail)
                .font(.caption.monospacedDigit())
                .foregroundStyle(Palette.onSurfaceVariant)
        }
        .padding(.vertical, 4)
        .accessibilityIdentifier("importProgress")
    }

    private var fraction: Double {
        progress.total > 0 ? min(1, Double(progress.read) / Double(progress.total)) : 0
    }

    private var detail: String {
        let mb = { (b: Int64) in Int((Double(b) / 1_000_000).rounded()) }
        var text = "\(mb(progress.read)) of \(mb(progress.total)) MB · \(Int(fraction * 100)) %"
        let elapsed = Date().timeIntervalSince(progress.started)
        if fraction > 0.02 && elapsed > 2 {
            let left = elapsed / fraction - elapsed
            text += left < 60 ? " · under 1 min left" : " · about \(Int((left / 60).rounded())) min left"
        }
        return text
    }
}
