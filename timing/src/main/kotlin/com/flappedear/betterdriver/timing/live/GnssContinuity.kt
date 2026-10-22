package com.flappedear.betterdriver.timing.live

import kotlin.math.max

/**
 * A step between two fixes up to this long is never a GNSS gap: a few missed fixes, not a loss of
 * signal (FBBD-215).
 */
const val MinimumGnssGapSeconds = 0.5

/** How many median fix intervals a step may last and still be continuous. */
const val GnssGapIntervals = 3.0

/**
 * The longest step between two fixes that is still continuous data, for a receiver whose fixes
 * come every [medianIntervalSeconds]: three intervals, and at least [MinimumGnssGapSeconds]. 0 when
 * the interval is unknown. One rule for the live timer, the replay of saved laps and the analysis
 * (audit A11): 0.5 s for a 25 Hz RaceBox, 3 s for the phone's 1 Hz GPS (FBBD-275).
 */
fun gnssGapThresholdSeconds(medianIntervalSeconds: Double): Double =
    if (medianIntervalSeconds > 0.0 && medianIntervalSeconds.isFinite()) {
        max(medianIntervalSeconds * GnssGapIntervals, MinimumGnssGapSeconds)
    } else {
        0.0
    }

/**
 * [gnssGapThresholdSeconds] on a stream of fixes, for the [LapTimer]: the median is taken over the
 * last [window] intervals, so the rule follows the receiver the fixes come from. The first step has
 * no interval to go by and is continuous.
 */
class GnssContinuity(private val window: Int = DefaultWindow) {
    private val recent = LongArray(window)
    private var count = 0
    private var next = 0

    /** Whether a step of [intervalMicros] bridges a gap; the step then counts towards the median. */
    fun isGap(intervalMicros: Long): Boolean {
        val threshold = thresholdMicros()
        if (intervalMicros > 0) {
            recent[next] = intervalMicros
            next = (next + 1) % window
            if (count < window) count++
        }
        return threshold > 0 && intervalMicros > threshold
    }

    /** The longest continuous step now, in microseconds; 0 before the first interval. */
    fun thresholdMicros(): Long {
        if (count == 0) return 0
        val sorted = recent.copyOf(count).also { it.sort() }
        // The upper one of an even count, as the analysis takes a channel's median interval.
        val median = sorted[count / 2]
        return (gnssGapThresholdSeconds(median / MicrosPerSecond) * MicrosPerSecond).toLong()
    }

    private companion object {
        const val DefaultWindow = 15
    }
}
