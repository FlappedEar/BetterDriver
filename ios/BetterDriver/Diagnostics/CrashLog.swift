import BetterDriverCore
import Foundation

/// The file a signal handler writes to: opened at install, so the handler only calls write(2).
private var crashFd: Int32 = -1

/// The signals that end the app on a crash: a Swift trap (force unwrap, fatalError), a bad access,
/// an abort (what an uncaught Objective-C or Kotlin exception ends in), an illegal instruction.
private let crashSignals: [Int32] = [SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGTRAP, SIGSYS]

/// Frames written for a signal; the handler's own buffer, on its own stack.
private let crashFrames = 64

/// Writes [text] to the crash file; async-signal-safe.
private func crashWrite(_ text: StaticString) {
    _ = write(crashFd, text.utf8Start, text.utf8CodeUnitCount)
}

/// The signal handler: "signal <n>" and the stack to the crash file, then the default action, so iOS
/// still ends the app and writes its own crash report. Only async-signal-safe calls.
private func onCrashSignal(_ signal: Int32) {
    if crashFd >= 0 {
        crashWrite("signal ")
        var digits: (UInt8, UInt8, UInt8, UInt8) = (0, 0, 0, 0)
        withUnsafeMutableBytes(of: &digits) { buf in
            var n = Int(signal)
            var at = buf.count
            repeat {
                at -= 1
                buf[at] = UInt8(48 + n % 10)
                n /= 10
            } while n > 0 && at > 0
            _ = write(crashFd, buf.baseAddress! + at, buf.count - at)
        }
        crashWrite("\n")
        withUnsafeTemporaryAllocation(of: UnsafeMutableRawPointer?.self, capacity: crashFrames) { frames in
            let count = backtrace(frames.baseAddress!, Int32(crashFrames))
            backtrace_symbols_fd(frames.baseAddress!, count, crashFd)
        }
    }
    Darwin.signal(signal, SIG_DFL)
    raise(signal)
}

/// Crash capture for [AppLog] (Android: the uncaught exception handler in AppLog.kt). Exceptions are
/// written to the log with their stack before the app ends; a signal leaves a note in crash.txt,
/// which the next start copies into the log (formatting a log line is not safe inside a signal).
enum CrashLog {
    static func install(log: LogWriter) {
        let note = log.dir.appendingPathComponent("crash.txt")
        reportPrevious(note: note, log: log)
        try? FileManager.default.createDirectory(at: log.dir, withIntermediateDirectories: true)
        crashFd = open(note.path, O_WRONLY | O_CREAT | O_TRUNC | O_APPEND, 0o644)
        installSignalHandlers()
        objcLog = log
        NSSetUncaughtExceptionHandler { exception in
            let stack = exception.callStackSymbols.joined(separator: "\n")
            CrashLog.objcLog?.writeNow(
                "E", "Crash", "uncaught \(exception.name.rawValue): \(exception.reason ?? "")\n\(stack)"
            )
        }
        KotlinCrashLog.shared.install { trace in
            log.writeNow("E", "Crash", "uncaught Kotlin exception\n\(trace)")
        }
    }

    /// For the Objective-C handler, which cannot capture.
    private static var objcLog: LogWriter?

    /// What a signal left in [note] last run, as one Crash line of the log; the note is emptied after.
    static func reportPrevious(note: URL, log: LogWriter) {
        guard let text = try? String(contentsOf: note, encoding: .utf8), !text.isEmpty else { return }
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        let number = lines.first.flatMap { Int32($0.dropFirst("signal ".count)) }
        let name = number.map(signalName) ?? "a signal"
        let stack = lines.dropFirst().joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        log.write("E", "Crash", "the previous run ended by \(name)\n\(stack)")
        try? FileManager.default.removeItem(at: note)
    }

    static func signalName(_ number: Int32) -> String {
        let names: [Int32: String] = [
            SIGABRT: "SIGABRT", SIGSEGV: "SIGSEGV", SIGBUS: "SIGBUS", SIGILL: "SIGILL",
            SIGFPE: "SIGFPE", SIGTRAP: "SIGTRAP", SIGSYS: "SIGSYS",
        ]
        return "\(names[number] ?? "signal") (\(number))"
    }

    private static func installSignalHandlers() {
        // Its own stack, so a stack overflow can still be written.
        let size = Int(SIGSTKSZ) * 4
        var stack = stack_t(ss_sp: UnsafeMutableRawPointer.allocate(byteCount: size, alignment: 16), ss_size: size, ss_flags: 0)
        sigaltstack(&stack, nil)
        for sig in crashSignals {
            var action = sigaction()
            action.__sigaction_u.__sa_handler = onCrashSignal
            action.sa_flags = SA_ONSTACK
            sigemptyset(&action.sa_mask)
            sigaction(sig, &action, nil)
        }
    }
}
