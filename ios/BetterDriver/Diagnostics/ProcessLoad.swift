import BetterDriverCore
import Darwin
import Foundation

/// How hard the app works, logged once a minute while a session records, as the Android app's
/// ProcessLoad does (FBBD-281, FBBD-356): the shared LoadLine with CPU, memory and threads, so the
/// same log checks read both phones. iOS has one process and no garbage collector to count (Swift
/// frees by reference counting), so gc, gc_ms and alloc_mb stay 0; heap_mb is the memory iOS counts
/// against the app (its footprint) out of what it may use. Outside a recording it logs nothing.
///
/// One background thread, awake once a second for a locked read of the log's session.
enum ProcessLoad {
    private static let lock = NSLock()
    private static var started = false

    static func start(log: LogWriter) {
        let first = lock.withLock { () -> Bool in
            defer { started = true }
            return !started
        }
        guard first else { return }
        let thread = Thread { run(log: log) }
        thread.name = "load"
        thread.qualityOfService = .background
        thread.start()
    }

    /// Each line covers one recording only: a recording that starts or ends mid-minute starts a fresh
    /// minute, and its last part-minute is logged for it when it ends, as on Android.
    private static func run(log: LogWriter) {
        var session = log.session
        var base = sample()
        while true {
            Thread.sleep(forTimeInterval: checkSeconds)
            let now = log.session
            if now != session {
                if let session { write(log, session, from: base, to: sample(), minMs: minPartMs) }
                session = now
                base = sample()
            } else if let now, uptimeMillis() - base.uptimeMillis >= periodMs {
                let next = sample()
                write(log, now, from: base, to: next, minMs: periodMs)
                base = next
            }
        }
    }

    private static func write(_ log: LogWriter, _ session: String, from: LoadSample, to: LoadSample, minMs: Int64) {
        guard let line = LoadLine.companion.between(from: from, to: to), line.wallMillis >= minMs else { return }
        log.write("I", LoadLine.companion.TAG, line.text(), session: session)
    }

    /// The process's counters now.
    static func sample() -> LoadSample {
        let footprint = footprintBytes()
        return LoadSample(
            uptimeMillis: uptimeMillis(),
            cpuMillis: cpuMillis(),
            gcCount: 0,
            gcMillis: 0,
            allocatedBytes: 0,
            heapUsedBytes: footprint,
            heapMaxBytes: footprint + Int64(os_proc_available_memory()),
            threads: threadCount()
        )
    }

    private static func uptimeMillis() -> Int64 { Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000) }

    /// User and system CPU time of every thread of the process.
    private static func cpuMillis() -> Int64 {
        var usage = rusage()
        guard getrusage(RUSAGE_SELF, &usage) == 0 else { return 0 }
        func millis(_ t: timeval) -> Int64 { Int64(t.tv_sec) * 1_000 + Int64(t.tv_usec) / 1_000 }
        return millis(usage.ru_utime) + millis(usage.ru_stime)
    }

    /// The memory iOS counts against the app, as Xcode's memory gauge shows it.
    private static func footprintBytes() -> Int64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? Int64(info.phys_footprint) : 0
    }

    private static func threadCount() -> Int32 {
        var threads: thread_act_array_t?
        var count = mach_msg_type_number_t(0)
        guard task_threads(mach_task_self_, &threads, &count) == KERN_SUCCESS, let threads else { return 0 }
        let size = vm_size_t(MemoryLayout<thread_t>.stride * Int(count))
        for i in 0..<Int(count) { mach_port_deallocate(mach_task_self_, threads[i]) }
        vm_deallocate(mach_task_self_, vm_address_t(bitPattern: threads), size)
        return Int32(count)
    }

    private static let periodMs: Int64 = 60_000

    /// How often the thread looks whether a recording started or ended.
    private static let checkSeconds: TimeInterval = 1

    /// A recording's last part-minute shorter than this is not logged: too short to say anything.
    private static let minPartMs: Int64 = 5_000
}
