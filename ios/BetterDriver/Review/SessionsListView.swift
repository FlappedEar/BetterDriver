import BetterDriverCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Sessions by day (UI-139 C), with Import session above them (UI-292 A) and the import's progress.
struct SessionsListView: View {
    @Bindable var model: SessionsModel
    @Binding var selection: String?
    @State private var picking = false
    @State private var notYet: String?
    @State private var deleting: SessionRow?
    @State private var trackDay: TrackDayLink?
    @State private var watching: SessionRow?
    @State private var coachReview: CoachReviewLink?
    @State private var comparingLaps = false
    /// The camera whose files Files is picking, and for which session (UI-32).
    @State private var addingTo: (sessionId: String, camera: ListedCamera)?
    @State private var pickingCameraFiles = false

    var body: some View {
        List(selection: $selection) {
            actions
                .listRowBackground(Palette.background)
            if let progress = model.importing {
                ImportCard(title: "Importing \(progress.name)", progress: progress)
                    .listRowBackground(Palette.surface)
            }
            if let progress = model.sharing {
                ImportCard(title: "Preparing to share", progress: progress, identifier: "shareProgress")
                    .listRowBackground(Palette.surface)
            }
            if let work = model.goProWork {
                CameraFilesCard(work: work)
                    .listRowBackground(Palette.surface)
            }
            if model.sessions.isEmpty && model.importing == nil {
                Text("No sessions yet. Open a .bdsession or .vbo from Files, AirDrop or another app, or tap Import session.")
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .listRowBackground(Palette.background)
            }
            ForEach(model.days) { day in
                // The day as a section title, with the track when the day had one, then a Track day card
                // for each track driven twice that day (FBBD-315, UI-308 A), as Android's DayHeading.
                VStack(alignment: .leading, spacing: 8) {
                    HealthSectionTitle(day.title)
                    ForEach(day.trackDays) { link in
                        trackDayCard(link, named: day.trackOnRows)
                    }
                }
                .padding(.top, 8)
                .listRowBackground(Palette.background)
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                // Two session cards a row (UI-308 A); the track on each when the day had more than one.
                ForEach(Self.pairs(day.sessions), id: \.first!.id) { pair in
                    HStack(alignment: .top, spacing: 8) {
                        ForEach(pair) { session in card(session, withTrack: day.trackOnRows) }
                        if pair.count < 2 { Color.clear.frame(maxWidth: .infinity, maxHeight: 1) }
                    }
                    .listRowBackground(Palette.background)
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Palette.background)
        .navigationTitle("Sessions")
        .sheet(item: $trackDay) { TrackDayView(link: $0) }
        .fullScreenCover(item: $coachReview) { CoachReviewScreen(link: $0) }
        .fullScreenCover(isPresented: $comparingLaps) { LapPickerScreen() }
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

    /// Android's session card buttons: Watch video (FBBD-341, only with video), Add for each camera started by hand without
    /// video here yet (UI-32), Delete, Share, and the coach's review. Behind ⋮ on the row, as Android,
    /// and on a long press.
    @ViewBuilder private func rowActions(_ session: SessionRow) -> some View {
        // Only with a whole video file to play (FBBD-426): left out, not greyed out, without, as Android.
        if model.rowFacts[session.id]?.video == true && !PhoneVideoFinishing.shared.busy(sessionId: session.id) {
            Button("Watch video") { watching = session }
        }
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

    /// Android's Sessions header: Import session and Compare laps from any sessions at a track (FBBD-92).
    /// In the list, not the toolbar: iPadOS 26 folded them into a "..." menu in the narrow sidebar
    /// (Arek, TestFlight 1441). Tracks are in Settings, as Android.
    private var actions: some View {
        HStack(spacing: 16) {
            Button {
                addingTo = nil
                picking = true
            } label: { Label("Import session", systemImage: "plus") }
                .accessibilityIdentifier("importSessions")
            Button("Compare laps") { comparingLaps = true }
                .accessibilityIdentifier("compareLaps")
            Spacer(minLength: 0)
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(Palette.accent)
        .buttonStyle(.borderless)
        .labelStyle(.titleAndIcon)
        .lineLimit(1)
        .minimumScaleFactor(0.8)
    }

    private func addFiles(_ session: SessionRow, _ camera: ListedCamera) {
        if model.addDemoCameraFile(sessionId: session.id, camera: camera) { return }
        addingTo = (session.id, camera)
        pickingCameraFiles = true
    }

    /// One session as a card (UI-308 A), as Android's: a tap opens its review, ⋮ its actions.
    private func card(_ session: SessionRow, withTrack: Bool) -> some View {
        SessionsCard(
            session: session,
            withTrack: withTrack,
            selected: selection == session.id,
            cameras: model.cameraLines[session.id] ?? [],
            onCamera: model.onCamera[session.id] ?? [],
            download: model.goProDownload?.sessionId == session.id ? model.goProDownload : nil,
            canDownload: model.canDownload,
            onDownload: { model.downloadCameraFiles(sessionId: session.id, camera: $0) },
            problems: model.goProProblems[session.id] ?? [],
            facts: model.rowFacts[session.id],
            open: { selection = session.id },
            actions: AnyView(rowActions(session))
        )
        // Android's session card buttons: Watch video (FBBD-341), Add for each camera started by hand
        // without video here yet (UI-32), Delete, Share, and the coach's review.
        .contextMenu { rowActions(session) }
    }

    /// The Track day card (FBBD-315, UI-308 A): the day's best lap in purple and each session's best as a
    /// bar; it opens the whole day.
    private func trackDayCard(_ link: TrackDayLink, named: Bool) -> some View {
        let bests = link.ids.compactMap { model.rowFacts[$0]?.bestMillis?.int64Value }
        return Button { trackDay = link } label: {
            HealthCard(
                title: named ? "Track day · \(link.track)" : "Track day",
                icon: .laps,
                trailing: "\(link.ids.count) sessions",
                opens: true
            ) {
                HealthValueRow {
                    if let best = bests.min() {
                        HealthValue(value: CoreBridge.lapTime(millis: best), color: Palette.best)
                        HealthDetail("best of the day")
                    } else {
                        HealthDetail("The whole day at \(link.track)")
                    }
                } chart: {
                    HealthMiniBars(values: bests.map { Double($0) })
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("trackDayLink")
    }

    /// The day's sessions two by two, newest first, as Android's rows of cards.
    private static func pairs(_ sessions: [SessionRow]) -> [[SessionRow]] {
        stride(from: 0, to: sessions.count, by: 2).map { Array(sessions[$0..<min($0 + 2, sessions.count)]) }
    }

    /// Android's delete question (UI-48 A), from the shared core: the session, what goes and how much
    /// space it frees.
    private func deleteText(_ session: SessionRow) -> String {
        let title = [session.start.formatted(date: .abbreviated, time: .shortened), session.track]
            .filter { !$0.isEmpty }.joined(separator: " · ")
        let (bytes, video) = ReviewBridge.footprint(session.id)
        let device = UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
        return SessionsListKt.sessionDeleteText(
            title: title, bytes: bytes.map { KotlinLong(value: $0) }, video: video, device: device
        )
    }
}

/// One session as a card (UI-308 A), as Android's SessionRow: its start time (and track when the day had
/// more than one) in amber with ⋮, its best lap large in purple, then the car, how long, its laps and what
/// was recorded, from the shared core. A tap opens its review; ⋮ opens its actions. The card whose review
/// shows beside the list on an iPad is outlined. Its camera lines and downloads (UI-31, UI-32) follow.
private struct SessionsCard: View {
    let session: SessionRow
    let withTrack: Bool
    let selected: Bool
    let cameras: [SessionCameraLine]
    /// Open GoPro cameras still holding this session's files (UI-31), and the download running here.
    let onCamera: [CameraOnCamera]
    let download: SessionsModel.CameraDownload?
    let canDownload: Bool
    let onDownload: (CameraOnCamera) -> Void
    let problems: [String]
    /// Its detail line and best lap, once read.
    let facts: SessionRowFacts?
    let open: () -> Void
    /// The card's ⋮ menu, as Android's session card.
    let actions: AnyView

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button(action: open) { main }
                .buttonStyle(.plain)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(accessibilityText)
                .accessibilityAddTraits(.isButton)
                .accessibilityIdentifier("sessionRow")
                // ⋮ over the card, not inside its button: the card is one element to tap, its menu another.
                .overlay(alignment: .topTrailing) {
                    Menu { actions } label: {
                        Image(systemName: "ellipsis")
                            .rotationEffect(.degrees(90))
                            .frame(width: 32, height: 32)
                            .contentShape(Rectangle())
                    }
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Session actions")
                    .accessibilityIdentifier("sessionActions")
                }
            extras
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .padding(.bottom, 10)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: HealthStyle.cornerRadius))
        .overlay(
            RoundedRectangle(cornerRadius: HealthStyle.cornerRadius)
                .stroke(selected ? Palette.accent : Color.clear, lineWidth: 2)
        )
    }

    private var title: String {
        SessionsListKt.sessionRowTitle(
            time: session.start.formatted(date: .omitted, time: .shortened), track: track, withTrack: withTrack
        )
    }

    /// Where it was driven, as its day groups it: the saved track its laps are timed at once read, else
    /// the name it was recorded with (FBBD-453).
    private var track: String { facts?.facts?.trackName ?? session.track }

    private var detail: String {
        facts?.detail ?? session.durationSeconds.map { "\(Int($0 / 60)) min" } ?? ""
    }

    /// What VoiceOver and the tests read: the time and track (also when the card leaves the track to
    /// the day's title), the best lap and the detail.
    private var accessibilityText: String {
        [session.start.formatted(date: .omitted, time: .shortened), track, facts?.best ?? "", detail]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private var main: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Image(systemName: HealthIcon.laps.rawValue)
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 18, height: 18)
                Text(title)
                    .font(.system(size: 15, weight: .semibold).monospacedDigit())
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                // Room for ⋮, drawn over the card.
                Color.clear.frame(width: 32, height: 32)
            }
            .foregroundStyle(Palette.accent)
            VStack(alignment: .leading, spacing: 2) {
                if let best = facts?.best { HealthValue(value: best, color: Palette.best, size: 22) }
                if !detail.isEmpty { HealthDetail(detail) }
            }
            .padding(.trailing, 8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    @ViewBuilder private var extras: some View {
        // Android's GoPro lines (UI-31, UI-32): green when lined up by GPS, amber otherwise.
        ForEach(cameras, id: \.sourceId) { line in
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(line.byGps ? Palette.gain : Palette.warning)
                        .frame(width: 8, height: 8)
                    Text(line.text)
                        .font(.subheadline)
                        .foregroundStyle(Palette.onBackground)
                }
                Text(line.how)
                    .font(.caption)
                    .foregroundStyle(Palette.onSurfaceVariant)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("cameraLine")
        }
        // Android's UI-31 A: video still on the camera offers Download; while it comes, file N and GB.
        ForEach(onCamera, id: \.sourceId) { camera in
            if let download, download.sourceId == camera.sourceId {
                CameraDownloadLine(name: camera.name, download: download)
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Circle().fill(Palette.warning).frame(width: 8, height: 8)
                        Text("\(camera.name) · video on the camera")
                            .font(.subheadline)
                            .foregroundStyle(Palette.onBackground)
                            .accessibilityIdentifier("cameraOnCamera")
                    }
                    if camera.address != nil {
                        Button("Download") { onDownload(camera) }
                            .buttonStyle(.bordered)
                            .tint(Palette.accent)
                            .disabled(!canDownload)
                            .accessibilityIdentifier("goProDownload")
                    }
                }
            }
        }
        ForEach(problems.prefix(3), id: \.self) { problem in
            Text(problem)
                .font(.caption)
                .foregroundStyle(TrackColors.loss)
                .accessibilityIdentifier("cameraProblem")
        }
    }
}

/// Import progress: MB done of the file's size, percent and time left (UI-192 B); the same card
/// shows a session being prepared to share (FBBD-393).
private struct ImportCard: View {
    let title: String
    let progress: SessionsModel.ImportProgress
    var identifier = "importProgress"

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
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
        .accessibilityIdentifier(identifier)
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

/// An Open GoPro camera's files coming over its Wi-Fi, as Android's GoPro line shows it (UI-31 A).
private struct CameraDownloadLine: View {
    let name: String
    let download: SessionsModel.CameraDownload

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Circle().fill(Palette.warning).frame(width: 8, height: 8)
                Text("\(name) · downloading file \(download.index)")
                    .font(.subheadline)
                    .foregroundStyle(Palette.onBackground)
                Spacer()
                if download.total > 0 {
                    Text(String(format: "%.1f / %.1f GB", Double(download.bytes) / 1e9, Double(download.total) / 1e9))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Palette.onSurfaceVariant)
                }
            }
            if download.total > 0 {
                ProgressView(value: min(1, Double(download.bytes) / Double(download.total)))
                    .tint(Palette.accent)
            } else {
                ProgressView().tint(Palette.accent)
            }
            Text("Keep the camera on and close to the phone.")
                .font(.caption)
                .foregroundStyle(Palette.onSurfaceVariant)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("cameraDownload")
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
