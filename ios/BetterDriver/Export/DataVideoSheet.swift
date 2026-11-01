import AVKit
import BetterDriverCore
import SwiftUI

/// What Video with data shows (FBBD-152), as Android's sheet: the whole session, the composite laps
/// (realistic optimal and raw theoretical best, FBBD-163/178, iOS FBBD-370: saved only, as on Android)
/// or one lap, each lap with its time and the best lap chosen to start with; laps the camera missed
/// cannot be chosen.
/// A lap starts a few seconds before its line and ends as long after (FBBD-346, FlappedEar Overlays'
/// Handle: 5 to 8 s, 6 to start with). Watch hands it with the overlay to [onWatch], which closes the sheet
/// and plays it full screen in landscape (a player over the sheet lost its way when the phone turned);
/// Save video writes the MP4 and hands it to Files.
struct DataVideoSheet: View {
    let sessionId: String
    let title: String
    let onWatch: (DataVideoPlayback) -> Void
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            DataVideoPanel(sessionId: sessionId, title: title, onWatch: onWatch)
                .navigationTitle("Video with data")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel", action: onClose)
                    }
                }
        }
        .accessibilityIdentifier("dataVideoSheet")
    }
}

/// What Video with data offers, in its sheet and as the session's Video tab: the parts, Watch and
/// Save video. Leaving it stops a video being made.
struct DataVideoPanel: View {
    let sessionId: String
    let title: String
    let onWatch: (DataVideoPlayback) -> Void

    @State private var loading = true
    @State private var session: DataVideoSession?
    @State private var durations: [String: KotlinLong] = [:]
    @State private var parts: [DataVideoPart] = []
    @State private var picked: Int32?
    @State private var handle: Int32 = 6
    @State private var failure: String?
    @State private var progress: Double?
    @State private var saved: ExportedFile?
    /// The saved file's name, said in the sheet in place of Files' Save sheet in the UI tests.
    @State private var savedName: String?
    @State private var job: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            DimText(
                "\(title). Speed, lap time, delta to your best lap, G and the track map are drawn over the video " +
                    "on this phone."
            )
            if loading {
                DimText("Reading laps and video…")
                ProgressView().tint(Palette.accent)
            } else if let failure {
                DimText(failure, color: TrackColors.loss)
            } else {
                list
                if let picked, picked > 0 { handleRow }
            }
            Spacer(minLength: 0)
            if let progress {
                HStack {
                    ProgressView(value: progress).tint(Palette.accent)
                    Button("Stop") { job?.cancel() }
                }
                DimText("Making the video…")
            }
            if let savedName {
                DimText("Saved \(savedName)")
                    .accessibilityIdentifier("dataVideoSaved")
            }
            HStack {
                Spacer()
                Button("Save video") { save() }
                    .buttonStyle(.bordered)
                    .disabled(picked == nil || progress != nil)
                    .accessibilityIdentifier("dataVideoSave")
                Button("Watch") { watch() }
                    .buttonStyle(.borderedProminent)
                    .tint(Palette.accent)
                    .foregroundStyle(Palette.onAccent)
                    // A composite lap is only saved, as on Android: its overlay is the composite's own.
                    .disabled(picked == nil || progress != nil || isComposite)
                    .accessibilityIdentifier("dataVideoWatch")
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Palette.background)
        .task { await load() }
        .onDisappear { job?.cancel() }
        .sheet(item: $saved) { file in
            SaveToFiles(url: file.url) { saved = nil }
        }
    }

    /// The picked part is a composite lap.
    private var isComposite: Bool {
        picked.map { DataVideoSession.companion.isComposite(part: $0) } ?? false
    }

    private var list: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(parts, id: \.part) { p in
                    Button { picked = p.part } label: {
                        HStack(spacing: 10) {
                            Image(systemName: picked == p.part ? "largecircle.fill.circle" : "circle")
                                .foregroundStyle(p.enabled ? Palette.accent : Palette.onSurfaceVariant)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.name)
                                    .foregroundStyle(p.enabled ? Palette.onBackground : Palette.onSurfaceVariant)
                                if !p.note.isEmpty {
                                    DimText(p.note, color: p.best && p.enabled ? Palette.best : Palette.onSurfaceVariant)
                                }
                            }
                            Spacer()
                        }
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!p.enabled)
                    .accessibilityIdentifier("dataVideoPart\(p.part)")
                    .accessibilityAddTraits(picked == p.part ? .isSelected : [])
                }
                if !parts.contains(where: { $0.part > 0 }) {
                    DimText("No laps: the session was not at a saved track with a start/finish line.")
                }
            }
        }
    }

    /// Seconds of video before and after the lap, as Android's chips.
    private var handleRow: some View {
        VStack(alignment: .leading, spacing: 4) {
            DimText("Seconds before and after the lap")
            HStack(spacing: 8) {
                ForEach([Int32(5), 6, 7, 8], id: \.self) { s in
                    Button("\(s) s") { handle = s }
                        .buttonStyle(.bordered)
                        .tint(handle == s ? Palette.accent : Palette.onSurfaceVariant)
                        .accessibilityIdentifier("dataVideoHandle\(s)")
                        .accessibilityAddTraits(handle == s ? .isSelected : [])
                }
            }
        }
    }

    private func load() async {
        do {
            guard let opened = try await DataVideoMaker.open(sessionId) else {
                failure = "This session has no video."
                loading = false
                return
            }
            let (s, d) = opened
            session = s
            durations = d
            // The composite laps need the lap analysis: read off the main queue.
            parts = await Task.detached(priority: .userInitiated) { s.parts(durations: d) }.value
            // The best lap when it is on video, else the whole video, as on Android.
            picked = parts.first { $0.best && $0.enabled }?.part ?? parts.first { $0.part == 0 && $0.enabled }?.part
            if parts.allSatisfy({ !$0.enabled }) { failure = "This session's video could not be read." }
        } catch {
            failure = "This session's video could not be read: \(error.localizedDescription)"
        }
        loading = false
    }

    private func watch() {
        guard let session, let picked else { return }
        let durations = self.durations
        let handle = self.handle
        Task {
            do {
                let (asset, overlay) = try await DataVideoMaker.make(
                    session, part: picked, durations: durations, handleSeconds: handle
                )
                onWatch(DataVideoPlayback(asset: asset, overlay: overlay, session: session, part: picked, durations: durations))
            } catch {
                failure = "The video could not be opened: \(error.localizedDescription)"
            }
        }
    }

    /// The UI tests (-noFilesSheet) stop at the saved file: Files' Save sheet runs out of the app, and
    /// a test cannot close it steadily.
    private static var skipFilesSheet: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-noFilesSheet")
        #else
        false
        #endif
    }

    private func save() {
        guard let session, let picked else { return }
        let durations = self.durations
        let handle = self.handle
        progress = 0
        job = Task {
            do {
                let (asset, overlay) = try await DataVideoMaker.make(
                    session, part: picked, durations: durations, handleSeconds: handle
                )
                let url = try await DataVideoSaver.save(asset, overlay, name: session.fileName(part: picked)) { p in
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

/// A video with its overlay, ready to play.
struct DataVideoPlayback: Identifiable {
    let id = UUID()
    let asset: AVComposition
    let overlay: AVVideoComposition
    let session: DataVideoSession
    let part: Int32
    let durations: [String: KotlinLong]
}

/// The video with data, full screen in landscape (the video's shape), with the system's player controls.
struct DataVideoPlayerPage: View {
    let playback: DataVideoPlayback
    let onClose: () -> Void

    @State private var player = AVPlayer()
    /// Whether the page shows wider than tall, for the UI test.
    @State private var wide = false

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
                .background(GeometryReader { g in
                    Color.clear
                        .onAppear { wide = g.size.width > g.size.height }
                        .onChange(of: g.size) { _, size in wide = size.width > size.height }
                })
            VideoPlayer(player: player)
                .ignoresSafeArea(edges: .bottom)
                // The video's own shape with its overlay, for the UI test: always landscape.
                .accessibilityValue(playback.overlay.renderSize.width > playback.overlay.renderSize.height ? "wide" : "tall")
                .accessibilityIdentifier("dataVideoPlayer")
            Button { onClose() } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title)
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, .black.opacity(0.6))
            }
            .padding(16)
            .accessibilityLabel("Close")
            .accessibilityValue(wide ? "landscape" : "portrait")
            .accessibilityIdentifier("dataVideoClose")
        }
        .onAppear {
            let item = AVPlayerItem(asset: playback.asset)
            item.videoComposition = playback.overlay
            player.replaceCurrentItem(with: item)
            player.play()
        }
        .onDisappear { player.pause() }
        .landscape()
    }
}

/// Watch video (FBBD-341), as Android's on the session sheet: all of the session's video with the data
/// overlay, also for a drive without a track or laps.
struct DataVideoWatchPage: View {
    let sessionId: String
    let onClose: () -> Void

    @State private var playback: DataVideoPlayback?
    @State private var failure: String?

    var body: some View {
        Group {
            if let playback {
                DataVideoPlayerPage(playback: playback, onClose: onClose)
            } else {
                ZStack(alignment: .topLeading) {
                    Palette.background.ignoresSafeArea()
                    VStack(spacing: 12) {
                        if let failure {
                            DimText(failure)
                        } else {
                            ProgressView().tint(Palette.accent)
                            DimText("Reading the video…")
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    Button("Close", action: onClose)
                        .padding(16)
                        .accessibilityIdentifier("dataVideoClose")
                }
            }
        }
        .task { await load() }
        // Turned while the video is read, so the player opens the right way up.
        .landscape()
    }

    private func load() async {
        do {
            guard let opened = try await DataVideoMaker.open(sessionId) else {
                failure = "This session has no video."
                return
            }
            let (session, durations) = opened
            let whole: Int32 = 0
            let (asset, overlay) = try await DataVideoMaker.make(session, part: whole, durations: durations)
            playback = DataVideoPlayback(asset: asset, overlay: overlay, session: session, part: whole, durations: durations)
        } catch {
            failure = "This session's video could not be read: \(error.localizedDescription)"
        }
    }
}
