import BetterDriverCore
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
    @State private var trackDay: TrackDayLink?
    @State private var watching: SessionRow?
    @State private var coachReview: CoachReviewLink?
    /// The camera whose files Files is picking, and for which session (UI-32).
    @State private var addingTo: (sessionId: String, camera: ListedCamera)?
    @State private var pickingCameraFiles = false

    var body: some View {
        List(selection: $selection) {
            if let progress = model.importing {
                ImportCard(progress: progress)
                    .listRowBackground(Palette.surface)
            }
            if let work = model.goProWork {
                CameraFilesCard(work: work)
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
                        SessionRowView(
                            session: session,
                            cameras: model.cameraLines[session.id] ?? [],
                            problems: model.goProProblems[session.id] ?? []
                        )
                            .tag(session.id)
                            // Android's session card buttons: Watch video (FBBD-341), Add for each camera started
                            // by hand without video here yet (UI-32), Delete, Share, and the coach's review.
                            .contextMenu {
                                Button("Watch video") { watching = session }
                                ForEach(model.addableCameras(session), id: \.sourceId) { camera in
                                    Button("Add \(camera.name) video") { addFiles(session, camera) }
                                }
                                // Only on a session the coach spoke in, with its cues, as Android's card (FBBD-99).
                                if let cues = CoachReviewCounts.shared.counts[session.id] {
                                    Button { coachReview = CoachReviewLink(session) } label: {
                                        Text("Coach review")
                                        Text(CoachReviewCounts.text(cues))
                                    }
                                }
                                Button("Share") { model.share(session) }
                                Button("Delete", role: .destructive) { deleting = session }
                            }
                            .listRowBackground(selection == session.id ? Palette.surfaceVariant : Palette.background)
                    }
                } header: {
                    // Android's day heading: a "Track day ›" link for each track driven twice that day (FBBD-315).
                    let links = TrackDayLink.of(day.sessions)
                    VStack(alignment: .leading, spacing: 0) {
                        HStack {
                            Text(dayTitle(day.day, day.sessions))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Palette.onSurfaceVariant)
                            Spacer()
                            if links.count == 1 { trackDayButton("Track day ›", links[0]) }
                        }
                        if links.count > 1 {
                            ForEach(links) { trackDayButton("Track day at \($0.track) ›", $0) }
                        }
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
                Button {
                    addingTo = nil
                    picking = true
                } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Import sessions")
                    .accessibilityIdentifier("importSessions")
            }
        }
        .sheet(isPresented: $showingTracks) { TracksView() }
        .sheet(item: $trackDay) { TrackDayView(link: $0) }
        .fullScreenCover(item: $coachReview) { CoachReviewScreen(link: $0) }
        .fullScreenCover(item: $watching) { session in
            DataVideoWatchPage(sessionId: session.id) { watching = nil }
        }
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
        // One file picker for both: sessions to import, or a camera's MP4 files for a session (UI-32).
        .fileImporter(
            isPresented: Binding(
                get: { picking || pickingCameraFiles },
                set: { if !$0 { picking = false; pickingCameraFiles = false } }
            ),
            allowedContentTypes: addingTo != nil ? [.mpeg4Movie] : [.bdsession, .vbo, .commaSeparatedText],
            allowsMultipleSelection: true
        ) { result in
            let camera = addingTo
            addingTo = nil
            guard case .success(let urls) = result else { return }
            if let camera {
                model.addCameraFiles(sessionId: camera.sessionId, camera: camera.camera, urls: urls)
            } else {
                model.importFiles(urls)
            }
        }
    }

    private func addFiles(_ session: SessionRow, _ camera: ListedCamera) {
        if model.addDemoCameraFile(sessionId: session.id, camera: camera) { return }
        addingTo = (session.id, camera)
        pickingCameraFiles = true
    }

    private func trackDayButton(_ text: String, _ link: TrackDayLink) -> some View {
        Button(text) { trackDay = link }
            .font(.caption.weight(.semibold))
            .foregroundStyle(Palette.accent)
            .accessibilityIdentifier("trackDayLink")
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
    let cameras: [SessionCameraLine]
    let problems: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            times
            // Android's GoPro lines (UI-31, UI-32): green when lined up by GPS, amber otherwise.
            ForEach(cameras, id: \.sourceId) { line in
                HStack(spacing: 8) {
                    Circle()
                        .fill(line.byGps ? Palette.gain : Palette.warning)
                        .frame(width: 8, height: 8)
                    Text(line.text)
                        .font(.subheadline)
                        .foregroundStyle(Palette.onBackground)
                    Spacer()
                    Text(line.how)
                        .font(.caption)
                        .foregroundStyle(Palette.onSurfaceVariant)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("cameraLine")
            }
            ForEach(problems.prefix(3), id: \.self) { problem in
                Text(problem)
                    .font(.caption)
                    .foregroundStyle(TrackColors.loss)
                    .accessibilityIdentifier("cameraProblem")
            }
        }
    }

    private var times: some View {
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

/// A camera's files being copied into a session and lined up, as Android's GoPro line shows it (UI-32).
private struct CameraFilesCard: View {
    let work: SessionsModel.GoProWork

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Circle().fill(Palette.warning).frame(width: 8, height: 8)
                Text("\(work.name) · copying and lining up \(work.files == 1 ? "1 file" : "\(work.files) files")")
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
            }
            if work.total > 0 {
                ProgressView(value: min(1, Double(work.copied) / Double(work.total)))
                    .tint(Palette.accent)
                Text(String(format: "%.1f / %.1f GB", Double(work.copied) / 1e9, Double(work.total) / 1e9))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Palette.onSurfaceVariant)
            } else {
                ProgressView().tint(Palette.accent)
            }
        }
        .padding(.vertical, 4)
        .accessibilityIdentifier("cameraFilesProgress")
    }
}
