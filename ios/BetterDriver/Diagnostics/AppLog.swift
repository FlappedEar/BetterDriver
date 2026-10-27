import BetterDriverCore
import Foundation
import MetricKit
import UIKit
import os

/// The app's log, iOS side (AppLog.kt and DebugLog.kt on Android, FBBD-146, FBBD-183): one continuous
/// file, Application Support/logs/betterdriver.log, kept across sessions and app restarts, in the
/// Android log's line format (LogLine.kt), so the same checks read both. Lines also go to the system
/// log. Crashes are written before the app ends: Objective-C and Kotlin exceptions with their stack,
/// signals (a Swift trap, a bad access, an abort) as a note the next start copies into the log, the
/// stack of those from iOS's own report (MetricKit).
/// Nothing deletes the log except Settings › Diagnostics › Clear log.
enum AppLog {
    static let shared = LogWriter(dir: AppFolders.base.appendingPathComponent("logs", isDirectory: true))

    static func d(_ tag: String, _ message: String) { shared.write("D", tag, message) }
    static func i(_ tag: String, _ message: String) { shared.write("I", tag, message) }
    static func w(_ tag: String, _ message: String) { shared.write("W", tag, message) }
    static func e(_ tag: String, _ message: String) { shared.write("E", tag, message) }

    /// The recording this app works for now, written on each line; nil outside one.
    static var session: String? {
        get { shared.session }
        set { shared.session = newValue }
    }

    /// At launch, before anything else logs: the start line, what ended the previous run, the crash
    /// handlers, the Kotlin core's lines and the app's comings and goings. Never blocks for long.
    @MainActor
    static func install() {
        let log = shared
        IosLog.shared.sink = { tag, message in log.write("I", tag, message) }
        log.write(
            "I", tag,
            "start main: BetterDriver \(AppInfo.version) on \(DeviceInfo.model), \(DeviceInfo.system), " +
                "memory \(ProcessInfo.processInfo.physicalMemory >> 20) MB"
        )
        CrashLog.install(log: log)
        AppEvents.shared.start(log: log)
    }

    private static let tag = "App"
}

/// The phone's model and system, for the log's start line and the shared zip's device.txt.
enum DeviceInfo {
    /// "iPhone14,5": the hardware model, as Android's Build.MODEL names the phone.
    static var model: String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
    }

    /// "iOS 18.1 (22B83)".
    static var system: String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        let build = ProcessInfo.processInfo.operatingSystemVersionString
            .components(separatedBy: "Build ").last?.trimmingCharacters(in: CharacterSet(charactersIn: ")")) ?? "?"
        return "iOS \(v.majorVersion).\(v.minorVersion).\(v.patchVersion) (\(build))"
    }
}

/// The continuous log [file] in [dir]. Lines are appended in order on the log's own queue, never on
/// the caller's thread; [flush] waits for them. A cleared log is simply started again.
final class LogWriter: @unchecked Sendable {
    let dir: URL
    var file: URL { dir.appendingPathComponent("betterdriver.log") }

    private let queue = DispatchQueue(label: "log-writer", qos: .utility)
    private let onQueue = DispatchSpecificKey<Bool>()
    private let lock = NSLock()
    private var current: String?
    private var handle: FileHandle?
    private let pid = ProcessInfo.processInfo.processIdentifier
    /// Also gets every line: the system log, as logcat on Android. Off in tests.
    private let echo: Bool

    init(dir: URL, echo: Bool = true) {
        self.dir = dir
        self.echo = echo
        queue.setSpecific(key: onQueue, value: true)
    }

    var session: String? {
        get { lock.withLock { current } }
        set { lock.withLock { current = newValue } }
    }

    func write(_ level: Character, _ tag: String, _ message: String) {
        let line = LogLineFormat.line(
            date: Date(), uptimeMicros: LogLineFormat.uptimeMicros(), pid: pid, thread: LogLineFormat.threadName(),
            level: level, tag: tag, session: session, message: message
        )
        if echo { Logger(subsystem: "com.flappedear.betterdriver", category: tag).log("\(message, privacy: .public)") }
        queue.async { self.append(line + "\n") }
    }

    /// Writes [text] now, on the caller's thread, after every line before it: a crash's last words.
    func writeNow(_ level: Character, _ tag: String, _ message: String) {
        let line = LogLineFormat.line(
            date: Date(), uptimeMicros: LogLineFormat.uptimeMicros(), pid: pid, thread: LogLineFormat.threadName(),
            level: level, tag: tag, session: session, message: message
        )
        if DispatchQueue.getSpecific(key: onQueue) == true {
            append(line + "\n")
        } else {
            queue.sync { append(line + "\n") }
        }
    }

    /// Every line written so far is on file when this returns.
    func flush() {
        if DispatchQueue.getSpecific(key: onQueue) != true { queue.sync {} }
    }

    /// The log and anything else in its folder that ends in .log, by name.
    func files() -> [URL] {
        let all = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return all.filter { $0.pathExtension == "log" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    /// Their size in bytes. Reads the disk: off the main thread.
    func bytes() -> Int64 {
        flush()
        return files().reduce(0) { sum, url in
            sum + Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
    }

    /// Deletes the log; the next line starts it again. Only on the user's say-so.
    func clear() {
        queue.sync {
            try? handle?.close()
            handle = nil
            files().forEach { try? FileManager.default.removeItem(at: $0) }
        }
        write("I", "App", "log cleared")
    }

    private func append(_ text: String) {
        do {
            if handle == nil {
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                if !FileManager.default.fileExists(atPath: file.path) {
                    FileManager.default.createFile(atPath: file.path, contents: nil)
                }
                let h = try FileHandle(forWritingTo: file)
                try h.seekToEnd()
                handle = h
            }
            try handle?.write(contentsOf: Data(text.utf8))
        } catch {
            // Full or unreadable storage: this line is lost, the app carries on; the next one reopens.
            try? handle?.close()
            handle = nil
        }
    }
}

/// One line as Android's LogLine.format writes it (FBBD-183, FBBD-217):
/// `2026-10-12T12:36:08.593+02:00 4711.204518 main 2646 main I Recording [-]: message`
enum LogLineFormat {
    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone.autoupdatingCurrent
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSxxx"
        return f
    }()

    // swiftlint:disable:next function_parameter_count
    static func line(
        date: Date, uptimeMicros: UInt64, pid: Int32, thread: String, level: Character, tag: String,
        session: String?, message: String
    ) -> String {
        let micros = String(format: "%llu.%06llu", uptimeMicros / 1_000_000, uptimeMicros % 1_000_000)
        let session = session.flatMap { $0.isEmpty ? nil : $0 } ?? "-"
        let thread = thread.isEmpty ? "-" : thread.replacingOccurrences(of: " ", with: "_")
        return "\(formatter.string(from: date)) \(micros) main \(pid) \(thread) \(level) \(tag) [\(session)]: \(message)"
    }

    /// The phone's clock since boot, sleep included, as Android's elapsedRealtime: it never jumps.
    static func uptimeMicros() -> UInt64 { clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000 }

    /// "main", the thread's name, or the queue it runs on.
    static func threadName() -> String {
        if Thread.isMainThread { return "main" }
        if let name = Thread.current.name, !name.isEmpty { return name }
        return String(cString: __dispatch_queue_get_label(nil))
    }
}

/// The app going to the background and back, memory warnings and its end, as lines of the log, and
/// the crash and hang reports iOS hands the app a day later (MetricKit).
final class AppEvents: NSObject, MXMetricManagerSubscriber {
    static let shared = AppEvents()
    private var log: LogWriter?
    private var observers: [NSObjectProtocol] = []

    @MainActor
    func start(log: LogWriter) {
        guard self.log == nil else { return }
        self.log = log
        // The app's end is written at once: iOS may stop it right after.
        let events: [(Notification.Name, Character, String, Bool)] = [
            (UIApplication.didEnterBackgroundNotification, "I", "background", false),
            (UIApplication.willEnterForegroundNotification, "I", "foreground", false),
            (UIApplication.didReceiveMemoryWarningNotification, "W", "memory warning", false),
            (UIApplication.willTerminateNotification, "I", "terminating", true),
        ]
        observers = events.map { name, level, text, now in
            NotificationCenter.default.addObserver(forName: name, object: nil, queue: nil) { _ in
                if now { log.writeNow(level, "App", text) } else { log.write(level, "App", text) }
            }
        }
        MXMetricManager.shared.add(self)
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads {
            let json = String(decoding: payload.jsonRepresentation(), as: UTF8.self)
            log?.write("E", "Crash", "iOS diagnostic report: \(json)")
        }
    }
}
