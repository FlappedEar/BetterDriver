package com.flappedear.betterdriver.coach

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The headset audio-delay test (FBBD-49). A click is played at random gaps and the driver taps when he
 * hears it. A tap's time after the click is the audio delay plus his reaction time, so the test runs
 * twice: once on the phone speaker, which is nearly immediate, for his reaction time, and once on the
 * headset. The audio delay is the difference of the two means, with outliers discarded from each.
 */
object AudioDelay {
    /** Taps in one run. */
    const val Taps = 20

    /** Gaps between clicks, random so a tap cannot be timed by rhythm. */
    const val MinGapMillis = 1_500L
    const val MaxGapMillis = 4_000L

    /** A tap sooner than this after a click was a guess, not a reaction. */
    const val MinTapMillis = 80L

    /** A tap later than this was not for the last click; less than the shortest gap. */
    const val MaxTapMillis = 1_400L

    /** Taps used at the least for a run's mean, after outliers are discarded. */
    const val MinSamples = 5

    /** The delay is rounded to this, finer than anyone hears. */
    const val RoundMillis = 10

    fun nextGapMillis(random: Random = Random.Default): Long = random.nextLong(MinGapMillis, MaxGapMillis + 1)

    /**
     * One run's taps, as their delays in milliseconds after the click: their mean and standard
     * deviation, leaving out taps far from the median (beyond [OutlierMads] median absolute deviations,
     * and never closer than [MinOutlierMillis]), such as a missed click tapped late. Null with fewer than
     * [MinSamples] taps left.
     */
    fun stats(delays: List<Long>): TapStats? {
        val kept = withoutOutliers(delays).takeIf { it.size >= MinSamples } ?: return null
        val mean = kept.average()
        val variance = kept.sumOf { (it - mean) * (it - mean) } / (kept.size - 1)
        return TapStats(mean, sqrt(variance), kept.size, delays.size - kept.size)
    }

    /**
     * The headset's audio delay: its mean tap time less the phone speaker's, rounded to [RoundMillis] and
     * kept within 0 to [CoachSettings.MaxAudioDelayMillis]. [DelayResult.spreadMillis] is how far the
     * difference of the two means is uncertain (their standard errors combined).
     */
    fun delay(headset: TapStats, baseline: TapStats): DelayResult {
        val raw = headset.meanMillis - baseline.meanMillis
        val rounded = (raw / RoundMillis).roundToInt() * RoundMillis
        val millis = rounded.coerceIn(0, CoachSettings.MaxAudioDelayMillis)
        val spread = sqrt(
            headset.spreadMillis * headset.spreadMillis / headset.used +
                baseline.spreadMillis * baseline.spreadMillis / baseline.used,
        )
        return DelayResult(millis, spread.roundToInt(), capped = rounded > CoachSettings.MaxAudioDelayMillis)
    }

    private fun withoutOutliers(delays: List<Long>): List<Long> {
        if (delays.isEmpty()) return delays
        val median = median(delays.map(Long::toDouble))
        val mad = median(delays.map { abs(it - median) })
        val limit = max(OutlierMads * MadToSigma * mad, MinOutlierMillis)
        return delays.filter { abs(it - median) <= limit }
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }

    private const val OutlierMads = 3.0

    /** Scales a median absolute deviation to a standard deviation, for normally spread taps. */
    private const val MadToSigma = 1.4826
    private const val MinOutlierMillis = 60.0
}

/** One run's taps (FBBD-49): mean and standard deviation in milliseconds, of the [used] taps kept. */
data class TapStats(val meanMillis: Double, val spreadMillis: Double, val used: Int, val discarded: Int)

/** The measured audio delay (FBBD-49); [capped] when it was more than the setting allows. */
data class DelayResult(val millis: Int, val spreadMillis: Int, val capped: Boolean)

/**
 * The taps of one run (FBBD-49), matched to clicks. Times are milliseconds on one clock. A tap counts
 * once for the last click, between [AudioDelay.MinTapMillis] and [AudioDelay.MaxTapMillis] after it;
 * any other tap is ignored. The run is [done] at [taps] counted taps.
 */
class TapRun(val taps: Int = AudioDelay.Taps) {
    private var clickAt: Long? = null
    private var answered = false
    private val list = mutableListOf<Long>()

    /** Counted taps' delays after their clicks, in order. */
    val delays: List<Long> get() = list

    /** Clicks played. */
    var clicks = 0
        private set

    val done: Boolean get() = list.size >= taps

    fun onClick(atMillis: Long) {
        clickAt = atMillis
        answered = false
        clicks++
    }

    /** The tap's delay after the click, or null if it does not count. */
    fun onTap(atMillis: Long): Long? {
        val d = clickAt?.let { atMillis - it }
            ?.takeIf { !done && !answered && it in AudioDelay.MinTapMillis..AudioDelay.MaxTapMillis }
            ?: return null
        answered = true
        list += d
        return d
    }
}
