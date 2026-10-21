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
 * holds each to a budget. Outside a recording ([DebugLog.session] null) it only takes the sample.
 *
 * One daemon thread asleep between samples; a sample reads a few counters and /proc/self/status.
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

    private fun run() {
        var last = sample()
        while (true) {
            try {
                Thread.sleep(PeriodMs)
            } catch (_: InterruptedException) {
                return
            }
            val now = sample()
            if (DebugLog.session != null) LoadLine.between(last, now)?.let { DebugLog.i(LoadLine.TAG, it.text()) }
            last = now
        }
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
}
