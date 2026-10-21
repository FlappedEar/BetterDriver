package com.flappedear.betterdriver.debug

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.log.LoadLine
import com.flappedear.betterdriver.recorder.log.LoadSample
import java.io.File
import java.io.IOException

/**
 * Logs how hard this process works, once a minute while a session records (FBBD-281): CPU, garbage
 * collections, allocations, heap and threads, as a [LoadLine]. Every process runs one (UI, recorder,
 * camera, coach), so a shared log says which of them takes the phone's time, and the CI replay drive
 * holds each to a budget. Outside a recording ([DebugLog.session] null) it logs nothing.
 *
 * One daemon thread, awake once a second for a volatile read; a sample reads a few counters and
 * /proc/self/status.
 */
object ProcessLoad {
    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        Thread(::run, "load").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * Each line covers one recording only: a recording that starts or ends mid-minute starts a fresh
     * minute, and its last part-minute is logged for it when it ends (Codex).
     */
    private fun run() {
        var session = DebugLog.session
        var base = sample()
        while (true) {
            try {
                Thread.sleep(CheckMs)
            } catch (_: InterruptedException) {
                return
            }
            val now = DebugLog.session
            if (now != session) {
                if (session != null) log(session, base, sample(), MinPartMs)
                session = now
                base = sample()
            } else if (now != null && SystemClock.elapsedRealtime() - base.uptimeMillis >= PeriodMs) {
                val next = sample()
                log(now, base, next, PeriodMs)
                base = next
            }
        }
    }

    private fun log(session: String, from: LoadSample, to: LoadSample, minMs: Long) {
        val line = LoadLine.between(from, to)?.takeIf { it.wallMillis >= minMs } ?: return
        DebugLog.forSession(session, LoadLine.TAG, line.text())
    }

    private fun sample(): LoadSample {
        val runtime = Runtime.getRuntime()
        return LoadSample(
            uptimeMillis = SystemClock.elapsedRealtime(),
            cpuMillis = Process.getElapsedCpuTime(),
            gcCount = stat("art.gc.gc-count"),
            gcMillis = stat("art.gc.gc-time"),
            allocatedBytes = stat("art.gc.bytes-allocated"),
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            heapMaxBytes = runtime.maxMemory(),
            threads = threads(),
        )
    }

    private fun stat(name: String): Long = Debug.getRuntimeStat(name)?.toLongOrNull() ?: 0L

    /** All the process's threads, native ones too, as Linux counts them. */
    private fun threads(): Int = try {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Threads:") }?.substringAfter(':')?.trim()?.toIntOrNull()
        } ?: 0
    } catch (_: IOException) {
        0
    }

    private const val PeriodMs = 60_000L

    /** How often the thread looks whether a recording started or ended: one volatile read. */
    private const val CheckMs = 1_000L

    /** A recording's last part-minute shorter than this is not logged: too short to say anything. */
    private const val MinPartMs = 5_000L
}
