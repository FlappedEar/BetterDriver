package com.flappedear.betterdriver.recorder.log

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.FrameBuckets
import kotlin.jvm.Synchronized

private const val NanosPerMilli = 1_000_000L
private const val NanosPerSecond = 1_000_000_000L

/** Frames over 700 ms count as frozen, as Android vitals counts them. */
private const val FrozenNanos = 700 * NanosPerMilli

/** One closed stretch of frames: where it ended (the caller's nanosecond clock), its [EventKinds.UI_FRAMES] text. */
data class FrameSummary(val endNanos: Long, val text: String)

/**
 * Adds up the frames JankStats reports while a session records and closes a stretch every
 * [periodNanos] (FBBD-41, FBBD-62): frames, janky ones, frozen ones, the slowest, and a histogram
 * of frame times by [FrameBuckets], from which the drive's check works out the 95th percentile.
 * A stretch ends with the first frame after it, so a screen that draws nothing logs nothing.
 *
 * Common Kotlin, so the iPhone's track screen counts its frames the same way (FBBD-376).
 *
 * Thread-safe on the JVM: JankStats may report from its own thread. On iOS one thread calls it.
 */
class FrameStretch(private val periodNanos: Long = DefaultPeriodNanos) {
    private var startNanos = 0L
    private var endNanos = 0L
    private var frames = 0
    private var janky = 0
    private var frozen = 0
    private var worstNanos = 0L
    private val counts = IntArray(FrameBuckets.size + 1)

    /** Counts one frame; returns the stretch it closed, if it started a new one. */
    @Synchronized
    fun add(frameStartNanos: Long, durationNanos: Long, jank: Boolean): FrameSummary? {
        val closed = if (frames > 0 && frameStartNanos - startNanos >= periodNanos) take(frameStartNanos) else null
        if (frames == 0) startNanos = frameStartNanos
        frames++
        if (jank) janky++
        if (durationNanos > FrozenNanos) frozen++
        worstNanos = maxOf(worstNanos, durationNanos)
        endNanos = maxOf(endNanos, frameStartNanos + durationNanos)
        val bucket = FrameBuckets.indexOfFirst { durationNanos <= it * NanosPerMilli }
        counts[if (bucket < 0) FrameBuckets.size else bucket]++
        return closed
    }

    /** Closes the stretch now (the recording ended); null when no frame came since the last one. */
    @Synchronized
    fun flush(): FrameSummary? = if (frames == 0) null else take(endNanos)

    private fun take(untilNanos: Long): FrameSummary {
        val text = "frames=$frames janky=$janky frozen=$frozen worst_ms=${worstNanos / NanosPerMilli} " +
            "hist=${counts.joinToString(",")}"
        frames = 0
        janky = 0
        frozen = 0
        worstNanos = 0
        endNanos = 0
        counts.fill(0)
        return FrameSummary(untilNanos, text)
    }

    companion object {
        /** Every 30 s, so even a short outing has a few. */
        const val DefaultPeriodNanos = 30 * NanosPerSecond
    }
}
