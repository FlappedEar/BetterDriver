package com.flappedear.betterdriver.replay

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import com.flappedear.betterdriver.recorder.FrameBuckets
import java.util.Locale

/**
 * The app window's frames split into their parts (FBBD-266), as Android measures them: the UI time
 * JankStats logs for the drive check (delay before the frame started, input, animation, layout,
 * draw, sync with the render thread) and the render thread's own. The line says which part makes a
 * slow frame slow, for every frame and for those of 16 ms or more. Follows the resumed activity:
 * the track screen turning to landscape makes a new one.
 */
class FrameParts {
    private val thread = HandlerThread("FrameParts").apply { start() }
    private val handler = Handler(thread.looper)
    private val all = Sums()
    private val slow = Sums()
    private val ui = mutableListOf<Long>()
    private var window: Window? = null
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> add(metrics) }

    /** Measures [activity]'s window from now on, instead of the one before. Main thread. */
    fun follow(activity: Activity) {
        if (activity.window === window) return
        window?.removeOnFrameMetricsAvailableListener(listener)
        activity.window.addOnFrameMetricsAvailableListener(listener, handler)
        window = activity.window
    }

    /** Main thread. */
    fun stop() {
        window?.removeOnFrameMetricsAvailableListener(listener)
        window = null
        thread.quitSafely()
    }

    @Synchronized
    private fun add(metrics: FrameMetrics) {
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return
        val parts = LongArray(Parts.size) { metrics.getMetric(Parts[it].second) }
        val uiNanos = parts.take(UiParts).sum()
        ui += uiNanos
        all.add(parts)
        if (uiNanos >= SlowNanos) slow.add(parts)
    }

    @Synchronized
    fun text(): String {
        val sorted = ui.sorted()
        val p95 = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * P95).toInt()] / NanosPerMs
        // As the drive check counts them: frames per bucket of FrameBuckets ms, then over the last.
        val hist = IntArray(FrameBuckets.size + 1)
        sorted.forEach { n ->
            val b = FrameBuckets.indexOfFirst { n <= it * NanosPerMs }
            hist[if (b < 0) FrameBuckets.size else b]++
        }
        return String.format(
            Locale.ROOT,
            "frames=%d ui_p95_ms=%.1f hist=%s | all: %s | ui>=16ms: %s",
            sorted.size,
            p95,
            hist.joinToString(","),
            all,
            slow,
        )
    }

    private class Sums {
        var frames = 0
        val nanos = LongArray(Parts.size)

        fun add(parts: LongArray) {
            frames++
            parts.forEachIndexed { i, v -> nanos[i] += v }
        }

        override fun toString(): String = "n=$frames " + Parts.indices.joinToString(" ") { i ->
            val mean = if (frames == 0) 0.0 else nanos[i] / frames / NanosPerMs
            String.format(Locale.ROOT, "%s=%.2f", Parts[i].first, mean)
        }
    }

    private companion object {
        /** The first [UiParts] are the UI time JankStats counts (frameDurationUiNanos); then the render thread's. */
        val Parts = listOf(
            "delay" to FrameMetrics.UNKNOWN_DELAY_DURATION,
            "input" to FrameMetrics.INPUT_HANDLING_DURATION,
            "anim" to FrameMetrics.ANIMATION_DURATION,
            "layout" to FrameMetrics.LAYOUT_MEASURE_DURATION,
            "draw" to FrameMetrics.DRAW_DURATION,
            "sync" to FrameMetrics.SYNC_DURATION,
            "cmd" to FrameMetrics.COMMAND_ISSUE_DURATION,
            "swap" to FrameMetrics.SWAP_BUFFERS_DURATION,
            // API 31; the CI emulator is 34.
            "gpu" to FrameMetrics.GPU_DURATION,
        )
        const val UiParts = 6
        const val SlowNanos = 16_000_000L
        const val NanosPerMs = 1_000_000.0
        const val P95 = 0.95
    }
}
