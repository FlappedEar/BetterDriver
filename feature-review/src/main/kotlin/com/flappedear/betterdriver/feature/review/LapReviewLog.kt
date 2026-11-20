package com.flappedear.betterdriver.feature.review

import com.flappedear.betterdriver.recorder.log.DebugLog

/**
 * One `LapReview` line in the app log each time lap review playback stops (FBBD-455): the shared
 * [LapReviewStats] line (FBBD-429, the same numbers iOS writes), the review speed and the platform.
 * Without it an Android lap review stutter cannot be told from a log. Main thread only.
 */
internal class LapReviewLog(private val write: (String) -> Unit = { DebugLog.i(Tag, it) }) {
    val stats = LapReviewStats()

    /** When the current stretch of playback began, on the display frame clock; null while still. */
    private var playedFromNanos: Long? = null

    /** A display frame while playing, at [nowNanos], whose frame loop work took [workNanos]. */
    fun frame(workNanos: Long, nowNanos: Long) {
        if (playedFromNanos == null) playedFromNanos = nowNanos
        stats.frame(workNanos / NanosPerMicro)
    }

    /** Playback stopped (pause, end of the laps or the review closing) at [nowNanos]: logs it once. */
    fun stopped(nowNanos: Long, speed: Double): String? {
        val from = playedFromNanos ?: return null
        playedFromNanos = null
        val line = stats.line((nowNanos - from) / NanosPerMilli)?.let { "$it, speed $speed, Android" }
        stats.reset()
        line?.let(write)
        return line
    }

    private companion object {
        const val Tag = "LapReview"
        const val NanosPerMicro = 1_000L
        const val NanosPerMilli = 1_000_000L
    }
}
