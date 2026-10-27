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

    init() {
        ReviewBridge.clearStaging()
        ReviewBridge.addDemosIfAsked()
        reload()
        // A track edited on the Tracks screen (FBBD-336): an open review reads its session again.
        NotificationCenter.default.addObserver(forName: .tracksChanged, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.revision += 1
                self?.reload()
            }
        }
    }

    func reload() {
        sessions = ReviewBridge.sessions()
    }

    func row(_ id: String) -> SessionRow? { sessions.first { $0.id == id } }

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
