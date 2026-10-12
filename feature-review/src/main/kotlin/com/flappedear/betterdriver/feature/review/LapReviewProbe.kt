package com.flappedear.betterdriver.feature.review

import java.util.concurrent.atomic.AtomicLongArray

/**
 * What the lap review has done, for the emulator tests (FBBD-24, FBBD-93, FBBD-169): how many
 * display frames its frame loop has run, the cursor it set on the last one, and when each lap's
 * player last put a video frame on screen. It costs the review two field writes a display frame
 * and one a video frame.
 */
object LapReviewProbe {
    /** Display frames the lap review has run in this process. A paused, settled review adds none. */
    @Volatile
    var frames: Long = 0L
        private set

    /** The review cursor at the last frame: time since the start line, the same for both laps. */
    @Volatile
    var cursorMicros: Long = 0L
        private set

    private val released = AtomicLongArray(Lanes)

    /**
     * When lap [lane]'s player (0: A, 1: B) last released a video frame to the screen, on the
     * [System.nanoTime] clock; 0 before its first.
     */
    fun lastReleaseNanos(lane: Int): Long = released.get(lane)

    /** Written by the review on the main thread. */
    internal fun frame(cursor: Long) {
        frames += 1
        cursorMicros = cursor
    }

    /** Written by the player on its playback thread. */
    internal fun released(lane: Int, releaseNanos: Long) = released.set(lane, releaseNanos)

    /** Lap A and lap B. */
    internal const val Lanes = 2
}

/** Test tag of the lap review's delta chart, where a tap or a drag scrubs. */
const val ReviewChartTag = "lap-review-chart"
