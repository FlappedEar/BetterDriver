package com.flappedear.betterdriver.feature.review

import com.flappedear.betterdriver.timing.replay.LapAlignment

/**
 * Lap [b] against lap [a] (FBBD-93): the laps' [TraceComparison], which the iPad review shares
 * (FBBD-331), with the sessions and video they come from.
 */
class Comparison(val a: ReviewLap, val b: ReviewLap, stepMeters: Double = LapAlignment.DefaultStepMeters) {
    private val traces = TraceComparison(a.trace, b.trace, stepMeters)

    val alignment: LapAlignment get() = traces.alignment

    /** Until the slower lap reaches the line. */
    val lengthMicros: Long get() = traces.lengthMicros

    val series: List<SeriesPoint> get() = traces.series

    fun aMicros(micros: Long): Long = traces.aMicros(micros)
    fun bMicros(micros: Long): Long = traces.bMicros(micros)
    fun aFraction(micros: Long): Double = traces.aFraction(micros)
    fun bFraction(micros: Long): Double = traces.bFraction(micros)
    fun gapMetersAt(micros: Long): Double = traces.gapMetersAt(micros)
    fun microsAtDistance(meters: Double): Long = traces.microsAtDistance(meters)
}

/** The video frame of this lap at [lapMicros] since its start; null where the lap has no video. */
fun ReviewLap.videoAt(lapMicros: Long): VideoTarget? {
    val pos = video?.at(trace.lap.startMicros + lapMicros) ?: return null
    return if (pos.mediaMicros < 0) null else VideoTarget(pos.clip, pos.mediaMicros / MicrosPerMilli)
}

private const val MicrosPerMilli = 1_000L
