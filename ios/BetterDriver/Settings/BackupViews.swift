import BetterDriverCore
import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    /// A backup of the app's data (FBBD-156, FBBD-372), the file Android's Back up writes.
    static let bdbackup = UTType(exportedAs: "com.flappedear.betterdriver.backup")
    /// One track with its lines (FBBD-80, FBBD-372), the file Android's track Share writes.
    static let bdtrack = UTType(exportedAs: "com.flappedear.betterdriver.track")
}

/// The shared core's backup and track files (FBBD-372): the iPhone writes and reads the files the
/// Android app writes and reads, with the same checks and merge rules.
enum BackupBridge {
    static let backups = IosBackups(base: AppFolders.base.path)
    static let tracks = IosTrackShare(tracksRoot: AppFolders.tracks.path)
    static let tag = "Backups"

    /// Where files to hand to the share sheet or the file mover are written.
    static var outbox: URL { FileManager.default.temporaryDirectory.appendingPathComponent("backup", isDirectory: true) }

    /// At app start, before anything reads the data: finishes a restore the app was stopped in, as Android.
    static func finishOrClear() {
        do {
            try backups.finishOrClear()
        } catch {
            AppLog.e(tag, "could not finish the restore: \(error.localizedDescription)")
        }
    }

    /// BetterDriver-backup-2026-10-31.bdbackup, with today's local date, as Android names it.
    static func fileName(_ now: Date = Date()) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return BackupTexts.shared.fileName(isoDate: f.string(from: now))
    }

    /// When the backup was made and with which version, as Android shows it.
    static func made(_ s: BackupSummary) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_GB")
        f.dateFormat = "d MMM yyyy, HH:mm"
        let at = f.string(from: Date(timeIntervalSince1970: Double(s.createdUtcMillis) / 1000))
        return BackupTexts.shared.made(at: at, appVersion: s.appVersion)
    }
}

/// Back up and Restore in Settings › App (FBBD-372), as Android's Backups: the work runs off the
/// main thread; the last result stays as a line under the rows.
@MainActor
final class BackupModel: ObservableObject {
    static let shared = BackupModel()

    struct Line: Equatable {
        var text: String
        var fraction: Double?
        var running = false
        var failed = false
    }

    struct Offer: Identifiable {
        let id = UUID()
        let backup: StagedBackup
    }

    @Published var line: Line?
    @Published var asking = false
    @Published var sessions: (count: Int, bytes: Int64)?
    /// A written backup waiting for the user to pick where it goes.
    @Published var written: URL?
    /// A checked backup waiting for Merge or Replace.
    @Published var offer: Offer?
    var running: Bool { line?.running == true }

    func askBackUp() {
        sessions = nil
        asking = true
        Task.detached {
            let size = BackupBridge.backups.sessionsSize()
            await MainActor.run { self.sessions = (Int(size.count), size.bytes) }
        }
    }

    func backUp(includeSessions: Bool) {
        asking = false
        line = Line(text: "Backing up…", fraction: 0, running: true)
        let name = BackupBridge.fileName()
        Task.detached {
            do {
                let path = try BackupBridge.backups.write(
                    outRoot: BackupBridge.outbox.path, fileName: name, includeSessions: includeSessions,
                    appVersion: AppInfo.version
                ) { done, total in
                    let f = total.int64Value > 0 ? Double(done.int64Value) / Double(total.int64Value) : 0
                    Task { @MainActor in self.line = Line(text: "Backing up…", fraction: f, running: true) }
                }
                AppLog.i(BackupBridge.tag, "backup written: \(name), sessions \(includeSessions)")
                await MainActor.run {
                    self.line = Line(text: "Choose where to keep it…", running: true)
                    self.written = URL(fileURLWithPath: path)
                }
            } catch {
                AppLog.w(BackupBridge.tag, "backup failed: \(error.localizedDescription)")
                await MainActor.run { self.line = Line(text: "Backup failed: \(error.localizedDescription)", failed: true) }
            }
        }
    }

    /// The file mover's answer for [written].
    func moved(_ result: Result<URL, Error>) {
        written = nil
        switch result {
        case .success:
            line = Line(text: "Backed up.")
        case .failure(let error):
            line = Line(text: "Backup failed: \(error.localizedDescription)", failed: true)
        }
        try? FileManager.default.removeItem(at: BackupBridge.outbox)
    }

    /// The file mover was closed without a place: nothing was kept.
    func notMoved() {
        guard written != nil else { return }
        written = nil
        line = Line(text: "Backup not saved.", failed: true)
        try? FileManager.default.removeItem(at: BackupBridge.outbox)
    }

    /// Checks the backup at [url], then offers Merge or Replace.
    func open(_ url: URL) {
        guard !running else { return }
        line = Line(text: "Checking the backup…", fraction: 0, running: true)
        Task.detached {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let staged = try BackupBridge.backups.open(path: url.path) { done, total in
                    let f = total.int64Value > 0 ? Double(done.int64Value) / Double(total.int64Value) : 0
                    Task { @MainActor in self.line = Line(text: "Checking the backup…", fraction: f, running: true) }
                }
                AppLog.i(BackupBridge.tag, "backup checked: \(url.lastPathComponent)")
                await MainActor.run {
                    self.line = nil
                    self.offer = Offer(backup: staged)
                }
            } catch {
                AppLog.w(BackupBridge.tag, "restore failed: \(error.localizedDescription)")
                await MainActor.run { self.line = Line(text: "Restore failed: \(error.localizedDescription)", failed: true) }
            }
        }
    }

    func restore(replace: Bool) {
        guard let o = offer else { return }
        offer = nil
        line = Line(text: "Restoring…", running: true)
        Task.detached {
            do {
                let result = try BackupBridge.backups.restore(backup: o.backup, replace: replace)
                let text = BackupTexts.shared.result(r: result)
                AppLog.i(BackupBridge.tag, "restored (\(replace ? "replace" : "merge")): \(text)")
                await MainActor.run {
                    self.line = Line(text: text)
                    SettingsModel.shared.reload()
                    NotificationCenter.default.post(name: .tracksChanged, object: nil)
                    SessionsModel.shared.reload()
                }
            } catch {
                AppLog.w(BackupBridge.tag, "restore failed: \(error.localizedDescription)")
                await MainActor.run { self.line = Line(text: "Restore failed: \(error.localizedDescription)", failed: true) }
            }
        }
    }

    /// The restore sheet was closed: the checked backup is dropped, nothing changes.
    func cancel() {
        guard let o = offer else { return }
        offer = nil
        Task.detached { BackupBridge.backups.discard(backup: o.backup) }
    }
}

/// Back up and Restore, two rows of Settings › App as on Android, then the progress or result line.
/// Both are off while a session records.
struct BackupRows: View {
    @ObservedObject private var model = BackupModel.shared
    @EnvironmentObject private var recorder: RecordingController
    @State private var picking = false

    var body: some View {
        let idle = !recorder.isRecording && !model.running
        let detail = recorder.isRecording ? "Not while recording" : nil
        ListRow("Back up", value: detail ?? "To a file", action: idle ? { model.askBackUp() } : nil)
            .accessibilityIdentifier("backUp")
        ListRow("Restore", value: detail ?? "From a file", action: idle ? { picking = true } : nil)
            .accessibilityIdentifier("restore")
            // Here, not with the other prompts: one view holds one file importer.
            .fileImporter(isPresented: $picking, allowedContentTypes: [.bdbackup, .data]) { result in
                if case .success(let url) = result { model.open(url) }
            }
        if let line = model.line {
            VStack(alignment: .leading, spacing: 4) {
                if line.running {
                    if let f = line.fraction { ProgressView(value: f) } else { ProgressView().frame(maxWidth: .infinity) }
                }
                DimText(line.text, color: line.failed ? TrackColors.loss : Palette.onSurfaceVariant)
                    .accessibilityIdentifier("backupLine")
            }
            .padding(.vertical, 8)
        }
    }
}

/// The sheets and pickers of Back up and Restore, at the app's top so a backup opened from Files
/// or AirDrop gets its restore sheet too.
struct BackupPrompts: ViewModifier {
    @ObservedObject private var model = BackupModel.shared

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: $model.asking) {
                BackupDialog(sessions: model.sessions, onBackUp: { model.backUp(includeSessions: $0) }) {
                    model.asking = false
                }
                    .presentationDetents([.medium])
            }
            .sheet(item: $model.offer, onDismiss: { model.cancel() }) { offer in
                RestoreSheet(summary: offer.backup.summary, onMerge: { model.restore(replace: false) }) {
                    model.restore(replace: true)
                }
            }
            .fileMover(
                isPresented: Binding(get: { model.written != nil }, set: { if !$0 { model.notMoved() } }),
                file: model.written
            ) { model.moved($0) }
    }
}

/// Before the file is written: what goes in, and "Include sessions" with their count and size.
private struct BackupDialog: View {
    let sessions: (count: Int, bytes: Int64)?
    let onBackUp: (Bool) -> Void
    let onCancel: () -> Void
    @State private var include = false

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(BackupTexts.shared.WhatGoesIn).foregroundStyle(Palette.onBackground)
                Toggle(isOn: $include) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Include sessions (without video)").foregroundStyle(Palette.onBackground)
                        DimText(BackupTexts.shared.sessionsOption(
                            count: sessions.map { KotlinInt(int: Int32($0.count)) }, bytes: sessions?.bytes ?? 0
                        ))
                    }
                }
                .tint(Palette.accent)
                .accessibilityIdentifier("includeSessions")
                Spacer()
            }
            .padding(16)
            .background(Palette.background)
            .navigationTitle("Back up")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Choose file") { onBackUp(include) }.accessibilityIdentifier("chooseFile")
                }
            }
        }
    }
}

/// The restore sheet, as Android's: when the checked backup was made and what it holds, then Merge
/// (recommended) or Replace, which asks once more. Closing it changes nothing.
private struct RestoreSheet: View {
    let summary: BackupSummary
    let onMerge: () -> Void
    let onReplace: () -> Void
    @State private var confirming = false

    var body: some View {
        let labels = BackupTexts.shared.contentLabels(s: summary)
        let values = BackupTexts.shared.contentValues(s: summary)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Restore backup").font(.title2.weight(.semibold)).foregroundStyle(Palette.onBackground)
                DimText(BackupBridge.made(summary))
                ShellCard {
                    ForEach(Array(labels.enumerated()), id: \.offset) { i, label in
                        ListRow(label, value: values[i], divider: i > 0)
                    }
                }
                DimText(BackupTexts.shared.MergeOrReplace)
                HStack(spacing: 12) {
                    Button { confirming = true } label: { Text("Replace…").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("restoreReplace")
                    Button(action: onMerge) { Text("Merge").frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("restoreMerge")
                }
                .tint(Palette.accent)
            }
            .padding(16)
        }
        .background(Palette.background)
        .alert("Replace with the backup?", isPresented: $confirming) {
            Button("Replace", role: .destructive, action: onReplace)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(BackupTexts.shared.ReplaceWarning)
        }
    }
}

/// Tracks › Import and a track file opened from Files or AirDrop (FBBD-80, FBBD-372), as Android's:
/// a new track is added, the same one says so, and one with other lines asks Keep or Replace.
@MainActor
final class TrackImportModel: ObservableObject {
    static let shared = TrackImportModel()

    struct Differs: Identifiable {
        let id = UUID()
        let track: Track
    }

    @Published var message: String?
    @Published var differs: Differs?

    func importFile(_ url: URL) {
        Task.detached {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let track = try BackupBridge.tracks.read(path: url.path)
                let match = BackupBridge.tracks.match(track: track)
                if match == TrackMatch.same {
                    await self.done("Track \(track.displayName) is already here.")
                } else if match == TrackMatch.different {
                    await MainActor.run { self.differs = Differs(track: track) }
                } else {
                    await self.store(track, "Added track \(track.displayName).")
                }
            } catch {
                AppLog.w("Imports", "track import failed: \(error.localizedDescription)")
                await self.done("Import failed: \(error.localizedDescription)")
            }
        }
    }

    /// The answer to [differs]: replace the track here, or keep it.
    func resolve(replace: Bool) {
        guard let d = differs else { return }
        differs = nil
        if replace {
            Task.detached { await self.store(d.track, "Replaced track \(d.track.displayName).") }
        } else {
            message = "Kept track \(d.track.displayName)."
        }
    }

    private func store(_ track: Track, _ text: String) async {
        do {
            try BackupBridge.tracks.save(track: track)
            AppLog.i("Imports", "track \(track.id) stored")
            await MainActor.run { NotificationCenter.default.post(name: .tracksChanged, object: nil) }
            await done(text)
        } catch {
            await done("Import failed: could not store track \(track.displayName)")
        }
    }

    private func done(_ text: String) async {
        await MainActor.run { self.message = text }
    }

    /// Writes saved track [id] as a track file and opens the share sheet, as Android's editor Share.
    static func share(_ id: String) -> String? {
        do {
            let path = try BackupBridge.tracks.write(id: id, outRoot: BackupBridge.outbox.path)
            AppLog.i("Transfers", "track \(id) shared")
            LogShare.share(URL(fileURLWithPath: path))
            return nil
        } catch {
            return "Share failed: \(error.localizedDescription)"
        }
    }
}

/// The track import's questions and result, at the app's top.
struct TrackImportPrompts: ViewModifier {
    @ObservedObject private var model = TrackImportModel.shared

    func body(content: Content) -> some View {
        content
            .confirmationDialog(
                "Track differs",
                isPresented: Binding(get: { model.differs != nil }, set: { if !$0 { model.resolve(replace: false) } }),
                titleVisibility: .visible
            ) {
                Button("Replace it") { model.resolve(replace: true) }
                Button("Keep the one here", role: .cancel) { model.resolve(replace: false) }
            } message: {
                Text(model.differs.map {
                    "\($0.track.displayName) on this device has other lines or corners. Keep the one here, or replace it with the one received?"
                } ?? "")
            }
            .alert(
                model.message ?? "",
                isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })
            ) {
                Button("OK", role: .cancel) {}
            }
    }
}

enum IncomingRoute {
    /// Track files and backups have their own flows; anything else is a session (.bdsession, VBO, CSV).
    /// Returns whether [url] was taken.
    @MainActor
    static func take(_ url: URL) -> Bool {
        switch url.pathExtension.lowercased() {
        case TrackShareKt.TrackFileExtension:
            TrackImportModel.shared.importFile(url)
            return true
        case BackupFormatKt.BackupExtension:
            BackupModel.shared.open(url)
            return true
        default:
            return false
        }
    }
}
