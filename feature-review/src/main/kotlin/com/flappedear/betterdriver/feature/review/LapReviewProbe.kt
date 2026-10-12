package com.flappedear.betterdriver.feature.review

/**
 * What the lap review's frame loop has done, for the emulator tests (FBBD-24, FBBD-93, FBBD-169):
 * how many display frames it has run, and the cursor it set on the last one. Written by the review
 * on the main thread only; it costs the review two field writes a frame.
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

    internal fun frame(cursor: Long) {
        frames += 1
        cursorMicros = cursor
    }
}

/** Test tag of the lap review's delta chart, where a tap or a drag scrubs. */
const val ReviewChartTag = "lap-review-chart"
