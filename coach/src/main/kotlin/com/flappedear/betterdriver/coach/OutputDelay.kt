package com.flappedear.betterdriver.coach

import kotlin.math.roundToInt

/**
 * The audio output's own delay (FBBD-49), such as a Bluetooth headset's, measured with no help from
 * the driver: a moment of silence is played on the coach voice's output and Android's audio clock is
 * read while it plays. Android tells how many frames the app's sound has handed to the output (the
 * playback head) and when a given frame reached the output's end, the headset included when it reports
 * its delay. The frames handed over but not yet heard are the delay.
 */
object OutputDelay {
    /** Readings at the least for a measurement; their median is taken. */
    const val MinReadings = 3

    /** The delay is rounded to this, finer than anyone hears. */
    const val RoundMillis = 10

    /**
     * One reading, in milliseconds: at [nowNanos] the head had handed over [headFrames]; frame
     * [presentedFrame] reached the output at [presentedNanos] (both on one monotonic clock). The frame
     * playing at [nowNanos] is [presentedFrame] plus the time since, and the head is ahead of it by the
     * delay.
     */
    fun readingMillis(
        headFrames: Long,
        presentedFrame: Long,
        presentedNanos: Long,
        nowNanos: Long,
        sampleRate: Int,
    ): Double {
        val ahead = (headFrames - presentedFrame) * MillisPerSecond / sampleRate
        return ahead - (nowNanos - presentedNanos) / NanosPerMilli
    }

    /**
     * The delay from a playback's readings: their median, rounded to [RoundMillis] and kept within 0 to
     * [CoachSettings.MaxAudioDelayMillis]. Null with fewer than [MinReadings].
     */
    fun of(readings: List<Double>): Int? {
        if (readings.size < MinReadings) return null
        val s = readings.sorted()
        val mid = s.size / 2
        val median = if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
        return ((median / RoundMillis).roundToInt() * RoundMillis).coerceIn(0, CoachSettings.MaxAudioDelayMillis)
    }

    /**
     * The delay a coached session uses: the one measured at its start when there is one, else the one
     * set in the coach settings ([setMillis]), the fallback when Android gives no audio clock.
     */
    fun effective(setMillis: Int, measuredMillis: Int?): AudioDelayUsed =
        if (measuredMillis != null) {
            AudioDelayUsed(measuredMillis, AudioDelayUsed.Measured)
        } else {
            AudioDelayUsed(setMillis, AudioDelayUsed.Set)
        }

    private const val MillisPerSecond = 1_000.0
    private const val NanosPerMilli = 1_000_000.0
}

/** The audio delay a session uses (FBBD-49) and where it came from: [Measured] or [Set]. */
data class AudioDelayUsed(val millis: Int, val source: String) {
    companion object {
        const val Measured = "measured"
        const val Set = "set"
    }
}
