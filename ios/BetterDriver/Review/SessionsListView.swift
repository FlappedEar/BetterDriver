import SwiftUI
import UniformTypeIdentifiers

/// Sessions by day (UI-139 C), with + to import files (UI-292 A) and the import's progress.
struct SessionsListView: View {
    @Bindable var model: SessionsModel
    @Binding var selection: String?
    @State private var picking = false

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
                            .listRowBackground(selection == session.id ? Palette.surfaceVariant : Palette.background)
                    }
                } header: {
                    Text(dayTitle(day.day, day.sessions))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Palette.onSurfaceVariant)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Palette.background)
        .navigationTitle("Sessions")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { picking = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Import sessions")
                    .accessibilityIdentifier("importSessions")
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.bdsession, .vbo, .commaSeparatedText], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { model.importFiles(urls) }
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
