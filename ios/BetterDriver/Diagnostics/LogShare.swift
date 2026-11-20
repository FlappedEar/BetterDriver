import Foundation
import OSLog
import UIKit

/// Shares the app's log (Android: DebugLogShare.kt, FBBD-146, FBBD-183) as one zip through the share
/// sheet (AirDrop, Files, Mail), so after a drive Arek sends one file and every check is worked out
/// from it. The zip holds the continuous log, a device line and this run's system log (the iOS
/// counterpart of logcat: the camera's and Bluetooth's own messages). Sharing never deletes the log.
enum LogShare {
    /// System log entries added to the zip, newest kept.
    static let systemLines = 20_000

    /// Writes the zip; blocking, so off the main thread.
    static func prepare(log: LogWriter = AppLog.shared) throws -> URL {
        log.write("I", tag, "preparing the log to share")
        log.flush()
        let fm = FileManager.default
        let out = fm.temporaryDirectory.appendingPathComponent("logs-out", isDirectory: true)
        try? fm.removeItem(at: out)
        // The phone's own time, as on its clock (FBBD-217).
        let stamp = DateFormatter()
        stamp.locale = Locale(identifier: "en_US_POSIX")
        stamp.dateFormat = "yyyyMMdd-HHmmss"
        let name = "BetterDriver-log-\(stamp.string(from: Date()))"
        let folder = out.appendingPathComponent(name, isDirectory: true)
        try fm.createDirectory(at: folder, withIntermediateDirectories: true)
        try Data(deviceText().utf8).write(to: folder.appendingPathComponent("device.txt"))
        for file in log.files() {
            try fm.copyItem(at: file, to: folder.appendingPathComponent(file.lastPathComponent))
        }
        try Data(systemLog(since: log.clearedAt).utf8).write(to: folder.appendingPathComponent("oslog.txt"))
        let zip = out.appendingPathComponent("\(name).zip")
        try zipFolder(folder, to: zip)
        try? fm.removeItem(at: folder)
        return zip
    }

    /// iOS zips a folder when it is read "for uploading"; the zip lasts only inside the block.
    private static func zipFolder(_ folder: URL, to zip: URL) throws {
        var coordinated: NSError?
        var copied: Error?
        NSFileCoordinator().coordinate(readingItemAt: folder, options: .forUploading, error: &coordinated) { temp in
            do {
                try FileManager.default.copyItem(at: temp, to: zip)
            } catch {
                copied = error
            }
        }
        if let error = coordinated ?? copied { throw error }
    }

    static func deviceText() -> String {
        "BetterDriver \(AppInfo.version)\n\(DeviceInfo.model), \(DeviceInfo.system)\nPrepared \(Date())\n"
    }

    /// This run's entries in the system log; iOS lets an app read its own process's. Only those after
    /// [since], the last Clear log, so a cleared log stays cleared (review v1.34.1 F14, FBBD-483), and
    /// not the app's own lines, which are in its log already (and private in the system log). The
    /// newest [keep] are kept as they stream past, never all of them at once (F15).
    static func systemLog(since: Date?, keep: Int = systemLines) -> String {
        do {
            let store = try OSLogStore(scope: .currentProcessIdentifier)
            let start = since.map { store.position(date: $0) } ?? store.position(timeIntervalSinceLatestBoot: 0)
            var lines = NewestLogLines(capacity: keep)
            for case let entry as OSLogEntryLog in try store.getEntries(at: start) {
                if let since, entry.date < since { continue }
                if entry.subsystem == AppLog.subsystem { continue }
                lines.append("\(entry.date) \(entry.subsystem) \(entry.category) \(entry.composedMessage)")
            }
            return lines.ordered.joined(separator: "\n") + "\n"
        } catch {
            return "system log not available: \(error.localizedDescription)\n"
        }
    }

    /// The share sheet for [url], over whatever is on screen; on iPad as a popover from its middle.
    @MainActor
    static func share(_ url: URL, onClosed: (() -> Void)? = nil) { share([url], onClosed: onClosed) }

    /// The share sheet for several files at once, as a session's VBO + video (FBBD-443): saving them
    /// to Files puts them side by side in the folder picked.
    /// [onClosed] runs when the sheet closes, sent or not: AirDrop calls it only once the transfer has
    /// ended (5 Nov: "completed, to AirDrop, after 239 s"), so a copy made to share can go then (FBBD-467).
    @MainActor
    static func share(_ urls: [URL], onClosed: (() -> Void)? = nil) {
        guard let first = urls.first else { return }
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard var top = scene?.windows.first(where: \.isKeyWindow)?.rootViewController else {
            AppLog.w(tag, "no window to show the share sheet for \(first.lastPathComponent)")
            onClosed?()
            return
        }
        while let shown = top.presentedViewController { top = shown }
        let sheet = UIActivityViewController(activityItems: urls, applicationActivities: nil)
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = top.view
            popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
            popover.permittedArrowDirections = []
        }
        let more = urls.count > 1 ? " and \(urls.count - 1) more" : ""
        let name = first.lastPathComponent
        let shown = Date()
        // The result is logged too, so a drive log says whether the AirDrop to the iPad went through
        // (FBBD-462; 5 Nov log: only "shown" was in it; the 8 GB AirDrop's result was only in the system log).
        sheet.completionWithItemsHandler = { activity, completed, _, error in
            AppLog.i(tag, resultLine(name: name, activity: activity?.rawValue, completed: completed,
                                     error: error, seconds: Date().timeIntervalSince(shown)))
            onClosed?()
        }
        top.present(sheet, animated: true)
        AppLog.i(tag, "share sheet shown for \(name)\(more)")
    }

    /// The line logged when the share sheet closes: where it went (AirDrop, Files…), done or cancelled.
    static func resultLine(name: String, activity: String?, completed: Bool, error: Error?, seconds: TimeInterval) -> String {
        let to = activity.map { $0.replacingOccurrences(of: "com.apple.UIKit.activity.", with: "") } ?? "none"
        let outcome = error.map { "failed: \($0.localizedDescription)" } ?? (completed ? "completed" : "cancelled")
        return "share of \(name) \(outcome), to \(to), after \(String(format: "%.0f", seconds)) s"
    }

    private static let tag = "DebugLogs"
}

/// The newest [capacity] lines of a stream, held in a ring: memory stays at [capacity] lines however
/// many go past (review v1.34.1 F15, FBBD-483).
struct NewestLogLines {
    let capacity: Int
    private var ring: [String] = []
    private var next = 0

    init(capacity: Int) { self.capacity = max(0, capacity) }

    mutating func append(_ line: String) {
        guard capacity > 0 else { return }
        if ring.count < capacity {
            ring.append(line)
        } else {
            ring[next] = line
        }
        next = (next + 1) % capacity
    }

    /// Oldest first.
    var ordered: [String] {
        ring.count < capacity ? ring : Array(ring[next...] + ring[..<next])
    }
}
