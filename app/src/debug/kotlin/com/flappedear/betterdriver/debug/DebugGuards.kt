package com.flappedear.betterdriver.debug

import android.os.Build
import android.os.StrictMode
import android.os.strictmode.Violation
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.metrics.performance.JankStats
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Responsiveness guardrails of debug builds (FBBD-41). StrictMode watches the main thread of every
 * process (UI, recorder, camera, coach) for disk and network access and the VM for closables never
 * closed; JankStats counts slow frames while a session records. Both only log, to logcat and the
 * debug log, so a log Arek sends shows them; neither ever stops the app.
 *
 * Release builds compile the empty twin in src/release instead, so none of this is in them.
 */
object DebugGuards {
    private const val Tag = "Guardrails"
    private const val AppPackage = "com.flappedear.betterdriver"

    /** Violations are written from here, never from the thread that caused them. */
    private val reports = Executors.newSingleThreadExecutor { Thread(it, "strictmode-log").apply { isDaemon = true } }
    private val violations = ViolationCounts()
    private val jank = JankMinutes()

    /** One JankStats per activity window. Main thread only. */
    private val trackers = WeakHashMap<ComponentActivity, JankStats>()

    /** Called once per process from Application.onCreate, on its main thread, after the debug log starts. */
    fun install(process: String) {
        // Built on the current policies, so Android's own defaults stay on: network on the main
        // thread still throws, as it does in release.
        val thread = StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy())
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyLog()
        val vm = StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
            .detectLeakedClosableObjects()
            .penaltyLog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            thread.penaltyListener(reports) { v -> report(v) }
            vm.penaltyListener(reports) { v -> report(v) }
        }
        StrictMode.setThreadPolicy(thread.build())
        StrictMode.setVmPolicy(vm.build())
        // The debug log writes each line at once, from whichever thread logs it; that is its point
        // (nothing is lost in a crash), so its own writes are not reported.
        DebugLog.fileWrites = { write -> allowDisk(write) }
        DebugLog.i(Tag, "StrictMode on in $process")
    }

    /**
     * Lets [block] read and write files on this thread without a StrictMode report. Only for the few
     * reads that must happen before the first screen (settings, the setup) and writes that must be
     * done before the next step; each caller says why.
     */
    inline fun <T> allowDisk(block: () -> T): T {
        val old = StrictMode.allowThreadDiskWrites()
        try {
            return block()
        } finally {
            StrictMode.setThreadPolicy(old)
        }
    }

    /**
     * Counts [activity]'s frames while [recording] and logs a summary every minute; when the
     * recording ends, the last part minute is logged too. A new activity (the track screen turns)
     * carries on the same minute. Main thread.
     */
    fun trackJank(activity: ComponentActivity, recording: Boolean) {
        val stats = trackers[activity] ?: if (recording) startTracking(activity) else null
        stats?.isTrackingEnabled = recording
        if (!recording) jank.flush()?.let { DebugLog.i(Tag, it.line() + ", recording ended") }
    }

    private fun startTracking(activity: ComponentActivity): JankStats {
        val stats = JankStats.createAndTrack(activity.window) { frame ->
            jank.add(frame.frameStartNanos, frame.frameDurationUiNanos, frame.isJank)
                ?.let { DebugLog.i(Tag, it.line()) }
        }
        trackers[activity] = stats
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    stats.isTrackingEnabled = false
                    trackers.remove(activity)
                }
            },
        )
        return stats
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun report(v: Violation) {
        val place = violationPlace(v.javaClass.simpleName, v.stackTrace.toList(), AppPackage)
        val n = violations.count(place) ?: return
        if (n == 1) DebugLog.w(Tag, "StrictMode: $place", v) else DebugLog.w(Tag, "StrictMode: $place, $n times")
    }
}
