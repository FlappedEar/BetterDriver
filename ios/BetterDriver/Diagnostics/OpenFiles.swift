import Darwin
import Foundation
import UIKit

/// The files the app holds open when it goes to the background, as one log line (FBBD-430).
/// macOS suspends the iPad app in the background, and it killed it once (RUNNINGBOARD 0xdead10cc)
/// for a file lock held at suspension. Which file was not in the log; this line names it next time.
enum OpenFiles {
    /// One open regular file: its path and whether another open of it finds an flock lock on it.
    /// POSIX record locks (SQLite's) are not seen this way: a process never conflicts with its own.
    struct Entry: Equatable {
        let path: String
        let locked: Bool
    }

    /// Paths kept in the line; the count says how many there were.
    static let listed = 40

    /// Every regular file this process has open, by descriptor. [probe] false only lists them.
    static func list(probe: Bool = true) -> [Entry] {
        let limit = min(Int32(getdtablesize()), 4096)
        var buffer = [CChar](repeating: 0, count: Int(MAXPATHLEN))
        var entries: [Entry] = []
        var seen = Set<String>()
        for fd in 0..<limit {
            var info = stat()
            guard fstat(fd, &info) == 0, info.st_mode & S_IFMT == S_IFREG else { continue }
            let got = buffer.withUnsafeMutableBytes { fcntl(fd, F_GETPATH, $0.baseAddress!) }
            guard got != -1 else { continue }
            let path = buffer.withUnsafeBufferPointer { String(cString: $0.baseAddress!) }
            guard seen.insert(path).inserted else { continue }
            entries.append(Entry(path: path, locked: probe && flockHeld(path)))
        }
        return entries
    }

    /// True when an exclusive flock on [path] through a fresh descriptor is refused, so some open of
    /// it, in this process or another, holds a lock. A lock it gets is let go at once.
    static func flockHeld(_ path: String) -> Bool {
        let fd = open(path, O_RDONLY | O_NONBLOCK)
        if fd < 0 { return false }
        defer { close(fd) }
        if flock(fd, LOCK_EX | LOCK_NB) != 0 { return errno == EWOULDBLOCK }
        flock(fd, LOCK_UN)
        return false
    }

    /// "3 open files, 1 locked: ~/Library/x.lock (locked), ~/Library/y.log"; locked ones first,
    /// the home folder as ~ (no user name in the log).
    static func line(_ entries: [Entry], home: String = NSHomeDirectory()) -> String {
        let locked = entries.filter(\.locked).count
        let shown = (entries.filter(\.locked) + entries.filter { !$0.locked }).prefix(listed).map { e in
            let path = e.path.hasPrefix(home) ? "~" + e.path.dropFirst(home.count) : e.path
            return e.locked ? "\(path) (locked)" : path
        }
        var text = "\(entries.count) open files, \(locked) locked"
        if !shown.isEmpty { text += ": " + shown.joined(separator: ", ") }
        if entries.count > shown.count { text += ", +\(entries.count - shown.count) more" }
        return text
    }

    /// On going to the background: the line, written and flushed while a background task keeps the
    /// app awake, so it is on file before any suspension. While a session records only the list:
    /// no probe of the recording's own lock.
    @MainActor
    static func logOnBackground(_ log: LogWriter) {
        var task = UIBackgroundTaskIdentifier.invalid
        task = UIApplication.shared.beginBackgroundTask(withName: "open-files") {
            UIApplication.shared.endBackgroundTask(task)
            task = .invalid
        }
        let probe = log.session == nil
        DispatchQueue.global(qos: .utility).async {
            log.write("I", tag, line(list(probe: probe)))
            log.flush()
            DispatchQueue.main.async {
                if task != .invalid { UIApplication.shared.endBackgroundTask(task) }
                task = .invalid
            }
        }
    }

    static let tag = "OpenFiles"
}
