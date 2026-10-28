import BetterDriverCore
import Foundation
import Observation

/// The session list and the imports into it (FBBD-331, UI-292 A).
@MainActor
@Observable
final class SessionsModel {
    /// A running import, as Android's import card shows it (UI-192 B).
    struct ImportProgress: Equatable {
        let name: String
        var read: Int64
        var total: Int64
        let started: Date
    }

    /// A session already here with the same id as one being imported: keep or replace.
    struct Duplicate: Identifiable {
        let received: ReceivedImport
        let name: String
        var id: String { received.sessionId }
    }

    /// A session being added whose track differs from the saved track of its id: keep or replace.
    struct TrackChange: Identifiable {
        let received: ReceivedImport
        let replace: Bool
        let track: String
        var id: String { received.sessionId }
    }

    private(set) var sessions: [SessionRow] = []
    private(set) var importing: ImportProgress?
    /// Goes up with every session or track added, so an open review reads its session again.
    private(set) var revision = 0
    var duplicate: Duplicate?
    var trackChange: TrackChange?
    var message: String?
    private var queue: [URL] = []

    /// Files of a camera started by hand being copied into a session and lined up (UI-32, FBBD-343).
    struct GoProWork: Equatable {
        let sessionId: String
        let name: String
        let files: Int
        var copied: Int64
        let total: Int64
    }

    private(set) var goProWork: GoProWork?
    /// Each session's lined-up camera clips, one line per camera, as Android's session card shows them.
    private(set) var cameraLines: [String: [SessionCameraLine]] = [:]
    /// What the last adding of camera files to a session could not add, per session.
    private(set) var goProProblems: [String: [String]] = [:]

    init() {
        ReviewBridge.clearStaging()
        ReviewBridge.prepareForUITests()
        #if DEBUG
        // A GoPro started by hand in the equipment list, for adding its files (FBBD-343).
        if ProcessInfo.processInfo.arguments.contains("-demoGoPro"),
           !SettingsModel.shared.setup.equipment.contains(where: { $0.modelName == ModelName.goProFiles }) {
            SettingsModel.shared.update { SettingsCore.shared.addGoProFiles(setup: $0, goPro: .hero8Black, id: "demo-gopro") }
        }
        #endif
        reload()
        // A track edited on the Tracks screen (FBBD-336): an open review reads its session again.
        NotificationCenter.default.addObserver(forName: .tracksChanged, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.revision += 1
                self?.reload()
            }
        }
        // A recording ended: it is the newest session.
        NotificationCenter.default.addObserver(forName: .sessionsChanged, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.reload() }
        }
    }

    func reload() {
        sessions = ReviewBridge.sessions()
        let setup = SettingsModel.shared.setup
        let root = ReviewBridge.library.sessionsRoot
        cameraLines = Dictionary(uniqueKeysWithValues: sessions.map { s in
            (s.id, GoProCore.shared.lines(setup: setup, sessionsRoot: root, sessionId: s.id))
        })
    }

    /// The listed cameras started by hand without video in [session] yet: each offers Add, as on Android.
    func addableCameras(_ session: SessionRow) -> [ListedCamera] {
        guard goProWork == nil else { return [] }
        return GoProCore.shared.addable(
            setup: SettingsModel.shared.setup, sessionsRoot: ReviewBridge.library.sessionsRoot, sessionId: session.id
        )
    }

    /// Copies [urls], the files picked for [camera], into [sessionId] and lines them up (UI-32). Files of
    /// one recording go in together, in chapter order; a file that cannot be placed is reported.
    func addCameraFiles(sessionId: String, camera: ListedCamera, urls: [URL]) {
        guard goProWork == nil, !urls.isEmpty else { return }
        let scoped = urls.map { $0.startAccessingSecurityScopedResource() }
        let sizes = urls.map { (try? $0.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0 }
        let total = Int64(sizes.reduce(0, +))
        goProWork = GoProWork(sessionId: sessionId, name: camera.name, files: urls.count, copied: 0, total: total)
        goProProblems[sessionId] = nil
        let kind = GoProCore.shared.kindFor(setup: SettingsModel.shared.setup, sourceId: camera.sourceId)
        let picked = urls.map { PickedCameraFile(path: $0.path, name: $0.lastPathComponent) }
        let root = ReviewBridge.library.sessionsRoot
        let sourceId = camera.sourceId
        Task {
            let problems = await Task.detached(priority: .userInitiated) {
                GoProImport(sessionsRoot: root).run(sessionId: sessionId, sourceId: sourceId, kind: kind, picked: picked) { copied in
                    let bytes = copied.int64Value
                    Task { @MainActor in self.goProWork?.copied = bytes }
                }
            }.value
            for (url, started) in zip(urls, scoped) where started { url.stopAccessingSecurityScopedResource() }
            goProWork = nil
            if !problems.isEmpty { goProProblems[sessionId] = problems }
            revision += 1
            reload()
        }
    }

    /// The UI tests' stand-in for Files (FBBD-343), in debug builds only: a made-up GoPro file riding
    /// along a minute of [sessionId] from 30 s, added as if picked. False when not testing.
    func addDemoCameraFile(sessionId: String, camera: ListedCamera) -> Bool {
        #if DEBUG
        guard ProcessInfo.processInfo.arguments.contains("-demoGoPro") else { return false }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("GX010042.MP4")
        let root = ReviewBridge.library.sessionsRoot
        // Reading a long session takes a while in a debug build: off the main thread, as the import itself.
        Task {
            let written = await Task.detached(priority: .userInitiated) {
                DemoGoPro.shared.write(
                    sessionsRoot: root, id: sessionId, path: url.path, fromMicros: 30_000_000, seconds: 60, utcShiftMillis: 0
                )
            }.value
            if written {
                addCameraFiles(sessionId: sessionId, camera: camera, urls: [url])
            } else {
                goProProblems[sessionId] = ["The test GoPro file could not be written"]
            }
        }
        return true
        #else
        return false
        #endif
    }

    func row(_ id: String) -> SessionRow? { sessions.first { $0.id == id } }

    /// The session being written to share, while it is.
    private(set) var sharing: String?

    /// Writes [session] as a .bdsession, then opens the share sheet (AirDrop, Files, Quick Share to
    /// Android), as Android's Share; a failure is said in [message].
    func share(_ session: SessionRow) {
        guard sharing == nil else { return }
        let id = session.id
        sharing = id
        Task {
            let result: Result<URL, Error> = await Task.detached { Result { try ReviewBridge.share(id) } }.value
            sharing = nil
            switch result {
            case .success(let url): LogShare.share(url)
            case .failure(let error): message = "Could not share the session: \(error.localizedDescription)"
            }
        }
    }

    /// Deletes [session] and its video (FBBD-90); a failure is said in [message].
    func delete(_ session: SessionRow) {
        let id = session.id
        Task {
            let failure = await Task.detached { ReviewBridge.delete(id) }.value
            if let failure { message = failure }
            revision += 1
            reload()
        }
    }

    /// The sessions of one day.
    struct Day: Identifiable {
        let day: Date
        let sessions: [SessionRow]
        var id: Date { day }
    }

    /// Sessions by the day they were driven, newest day first.
    var days: [Day] {
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: sessions) { calendar.startOfDay(for: $0.start) }
        return grouped.keys.sorted(by: >).map { day in Day(day: day, sessions: grouped[day]!.sorted { $0.start > $1.start }) }
    }

    /// Imports [urls] one after the other, in file-name order (the recorder names files by time, UI-193 A).
    func importFiles(_ urls: [URL]) {
        queue += urls.sorted { $0.lastPathComponent < $1.lastPathComponent }
        if importing == nil && duplicate == nil && trackChange == nil { next() }
    }

    private func next() {
        guard !queue.isEmpty else { return }
        let url = queue.removeFirst()
        let name = url.lastPathComponent
        importing = ImportProgress(name: name, read: 0, total: 0, started: Date())
        Task.detached(priority: .userInitiated) {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                if ReviewBridge.isForeign(url) {
                    // A VBO or RaceChrono CSV becomes a new session with a new id: nothing to ask.
                    try ReviewBridge.importForeign(url) { read, total in
                        Task { @MainActor in self.importing?.read = read; self.importing?.total = total }
                    }
                    await self.imported()
                } else {
                    let received = try ReviewBridge.receive(url) { read, total in
                        Task { @MainActor in self.importing?.read = read; self.importing?.total = total }
                    }
                    await self.received(received, name: name)
                }
            } catch {
                await self.failed(name, error)
            }
        }
    }

    private func imported() {
        importing = nil
        revision += 1
        reload()
        next()
    }

    private func received(_ received: ReceivedImport, name: String) {
        importing = nil
        if ReviewBridge.exists(received) {
            duplicate = Duplicate(received: received, name: name)
        } else {
            add(received, replace: false)
        }
    }

    /// The answer to [duplicate]: replace the session here, or keep it and drop the import.
    func resolveDuplicate(replace: Bool) {
        guard let d = duplicate else { return }
        duplicate = nil
        add(d.received, replace: replace)
    }

    /// The answer to [trackChange]: use the file's track, or keep the one here.
    func resolveTrack(replace: Bool) {
        guard let t = trackChange else { return }
        trackChange = nil
        store(t.received, replace: t.replace, replaceTrack: replace)
    }

    private func add(_ received: ReceivedImport, replace: Bool) {
        let adds = replace || !ReviewBridge.exists(received)
        if adds && ReviewBridge.trackDiffers(received) {
            trackChange = TrackChange(received: received, replace: replace, track: ReviewBridge.trackName(received))
        } else {
            store(received, replace: replace, replaceTrack: false)
        }
    }

    private func store(_ received: ReceivedImport, replace: Bool, replaceTrack: Bool) {
        do {
            if try ReviewBridge.accept(received, replace: replace, replaceTrack: replaceTrack) { revision += 1 }
        } catch {
            message = "Could not add the session: \(error.localizedDescription)"
        }
        reload()
        next()
    }

    private func failed(_ name: String, _ error: Error) {
        importing = nil
        message = "\(name) was not imported: \(error.localizedDescription)"
        next()
    }
}
