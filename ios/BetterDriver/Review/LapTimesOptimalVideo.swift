import BetterDriverCore
import SwiftUI

/// "Realistic optimal video" under the lap times (FBBD-163, FBBD-178; iOS FBBD-370), as Android's lap
/// analysis: the button with what the video will leave out or why it cannot be made beside it, then the
/// video's progress with Stop, then how it ended. The button opens the sheet that offers both composite
/// laps (the realistic optimal first) with their parts; Export saves the MP4 and hands it to Files, as
/// the Video tab does. The parts are looked for on the session's video off the main queue; the texts are
/// the shared core's (CompositeLapChoice).
struct LapTimesOptimalVideo: View {
    let sessionId: String

    @State private var choice = CompositeLapChoice(options: nil)
    @State private var session: DataVideoSession?
    @State private var durations: [String: KotlinLong] = [:]
    @State private var open = false
    @State private var progress: Double?
    @State private var failure: String?
    @State private var saved: ExportedFile?
    /// The saved file's name, said under the button in place of Files' Save sheet in the UI tests.
    @State private var savedName: String?
    @State private var job: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Button(OptimalVideoButtonKt.RealisticOptimalVideoLabel) { open = true }
                    .buttonStyle(.bordered)
                    .tint(Palette.accent)
                    .disabled(!choice.enabled || progress != nil)
                    .accessibilityIdentifier("optimalVideoButton")
                DimText(choice.note)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("optimalVideoNote")
            }
            if let progress {
                HStack {
                    ProgressView(value: progress).tint(Palette.accent)
                    Button("Stop") { job?.cancel() }
                }
                DimText("Making the video…")
            }
            if let savedName {
                DimText("Saved \(savedName)").accessibilityIdentifier("optimalVideoSaved")
            }
            if let failure { DimText(failure, color: TrackColors.loss) }
        }
        .task(id: sessionId) { await load() }
        .sheet(isPresented: $open) {
            LapTimesOptimalVideoSheet(choice: choice) { part in
                open = false
                save(part)
            } onCancel: {
                open = false
            }
        }
        .sheet(item: $saved) { file in
            SaveToFiles(url: file.url) { saved = nil }
        }
    }

    /// The session's video and its composite laps; without video, why neither can be filmed.
    private func load() async {
        let id = sessionId
        do {
            if let opened = try await DataVideoMaker.open(id) {
                let (s, d) = opened
                session = s
                durations = d
                let options = await Task.detached(priority: .utility) { s.compositeOptions(durations: d) }.value
                choice = CompositeLapChoice(options: options)
            } else {
                let options = try await Task.detached(priority: .utility) { () -> CompositeOptions in
                    try DataVideo(sessionsRoot: AppFolders.sessions.path, tracksRoot: AppFolders.tracks.path)
                        .compositeOptionsWithoutVideo(id: id)
                }.value
                choice = CompositeLapChoice(options: options)
            }
        } catch {
            choice = CompositeLapChoice(options: OptimalVideoButtonKt.unreadableCompositeOptions())
        }
    }

    /// The UI tests (-noFilesSheet) stop at the saved file, as the Video tab's.
    private static var skipFilesSheet: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-noFilesSheet")
        #else
        false
        #endif
    }

    private func save(_ part: Int32) {
        guard let session else { return }
        let durations = self.durations
        failure = nil
        savedName = nil
        progress = 0
        job = Task {
            do {
                let (asset, overlay) = try await DataVideoMaker.make(session, part: part, durations: durations)
                let url = try await DataVideoSaver.save(asset, overlay, name: session.fileName(part: part)) { p in
                    Task { @MainActor in if progress != nil { progress = p } }
                }
                if Self.skipFilesSheet {
                    savedName = url.lastPathComponent
                } else {
                    saved = ExportedFile(url: url)
                }
            } catch is CancellationError {
            } catch {
                failure = "The video was not saved: \(error.localizedDescription)"
            }
            progress = nil
        }
    }
}

/// Android's OptimalVideoSheet: what a composite lap's video is made of, before it is made. The
/// realistic optimal or the raw theoretical best as a radio choice, then every part of the chosen one in
/// track order with the lap it comes from and its time, those without video marked and summed up.
struct LapTimesOptimalVideoSheet: View {
    let choice: CompositeLapChoice
    let onExport: (Int32) -> Void
    let onCancel: () -> Void

    @State private var picked: Int32 = 0

    var body: some View {
        let kind = choice.kinds.first { $0.part == picked } ?? choice.kinds.first
        VStack(alignment: .leading, spacing: 8) {
            Text(OptimalVideoButtonKt.CompositeLapVideoTitle)
                .font(.headline)
                .foregroundStyle(Palette.onBackground)
            ForEach(choice.kinds, id: \.part) { k in kindRow(k) }
            DimText(OptimalVideoButtonKt.CompositeLapVideoAbout)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array((kind?.parts ?? []).enumerated()), id: \.offset) { _, p in partRow(p) }
                }
            }
            .frame(maxHeight: 320)
            if let missing = kind?.missing { DimText(missing, color: Palette.warning) }
            if let why = kind?.unavailable { DimText(why, color: TrackColors.loss) }
            HStack {
                Spacer()
                Button("Cancel", action: onCancel)
                Button("Export") { if let kind { onExport(kind.part) } }
                    .buttonStyle(.bordered)
                    .tint(Palette.accent)
                    .disabled(kind?.enabled != true)
                    .accessibilityIdentifier("optimalVideoExport")
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Palette.background)
        .presentationDetents([.medium, .large])
        .onAppear { picked = choice.preselected }
        .accessibilityIdentifier("optimalVideoSheet")
    }

    private func kindRow(_ k: CompositeKindLine) -> some View {
        Button { picked = k.part } label: {
            HStack(spacing: 10) {
                Image(systemName: picked == k.part ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(k.enabled ? Palette.accent : Palette.onSurfaceVariant)
                VStack(alignment: .leading, spacing: 2) {
                    Text(k.name).foregroundStyle(k.enabled ? Palette.onBackground : Palette.onSurfaceVariant)
                    DimText(k.note)
                }
                Spacer()
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!k.enabled)
        .accessibilityIdentifier("optimalVideoKind\(k.part)")
        .accessibilityAddTraits(picked == k.part ? .isSelected : [])
    }

    /// "Corner 3   Lap 5 · 0:05.12   No video".
    private func partRow(_ p: CompositePartLine) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(p.name)
                .foregroundStyle(p.noVideo ? Palette.onSurfaceVariant : Palette.onBackground)
                .frame(maxWidth: .infinity, alignment: .leading)
            VStack(alignment: .trailing, spacing: 2) {
                DimText(p.detail)
                if let video = p.video { DimText(video, color: p.noVideo ? TrackColors.loss : Palette.warning) }
            }
        }
        .padding(.vertical, 4)
    }
}
