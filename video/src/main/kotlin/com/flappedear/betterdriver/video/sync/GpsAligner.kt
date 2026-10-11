package com.flappedear.betterdriver.video.sync

import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.gpmf.GpsSample
import kotlin.math.sqrt

/** A speed reading on some timeline, in microseconds, with its GPS UTC time when known. */
data class SpeedPoint(val timeUs: Long, val speedMps: Double, val utcMillis: Long? = null)

/** A found offset: session time = video time + [sessionOffsetUs]. */
data class AlignmentResult(
    val sessionOffsetUs: Long,
    val alignment: Alignment,
    /** RMS speed difference at the chosen offset (m/s), or null when no matching was done. */
    val rmsSpeedErrorMps: Double? = null,
    val overlapUs: Long = 0,
)

/**
 * Finds the offset between a camera's GPS track (GoPro GPMF) and the session's GNSS.
 *
 * 1. A first guess: from GPS UTC on both sides when both have it, otherwise [prior] (the camera
 *    clock or the time the camera acknowledged the start command).
 * 2. Speed matching: the camera's GPS speed is compared with the session's GNSS speed at every
 *    offset within [searchWindowUs] of the guess, first in coarse steps and then in 10 ms steps,
 *    and the offset with the smallest RMS difference wins. Speed changes all the time on track, so
 *    the match is sharp; it needs the car to move during the overlap.
 *
 * A match is accepted only with at least [minOverlapUs] of overlap, enough speed variation and a
 * small RMS error; otherwise the UTC guess (or the prior) is returned as it is.
 *
 * Expected error with a good match: about one 10 ms step plus GPS timing jitter; GoPro's internal
 * GPS latency is unknown and is checked in the real-camera test (FBBD-22).
 */
class GpsAligner(
    private val searchWindowUs: Long = 3_000_000,
    private val minOverlapUs: Long = 20_000_000,
    private val maxRmsMps: Double = 1.5,
    private val minSpeedStdMps: Double = 2.0,
) {

    fun align(camera: List<GpsSample>, session: List<SpeedPoint>, prior: AlignmentResult? = null): AlignmentResult? {
        val usable = camera.filter { it.fix != 0 && it.fix != 1 }
        val cam = usable.map { SpeedPoint(it.videoTimeUs, it.speedMps, it.utcMillis) }
        return alignSpeeds(cam, session, prior)
    }

    fun alignSpeeds(
        camera: List<SpeedPoint>,
        session: List<SpeedPoint>,
        prior: AlignmentResult? = null,
    ): AlignmentResult? {
        if (camera.isEmpty() || session.isEmpty()) return prior
        val guess = utcGuess(camera, session) ?: prior
        val match = match(camera.sortedBy { it.timeUs }, session.sortedBy { it.timeUs }, guess)
        val accepted = match != null &&
            match.overlapUs >= minOverlapUs && match.rms <= maxRmsMps && match.std >= minSpeedStdMps
        return if (accepted) {
            val alignment = Alignment(AlignmentMethod.GPS_MATCH, MatchUncertaintyUs)
            AlignmentResult(match.offsetUs, alignment, match.rms, match.overlapUs)
        } else {
            guess
        }
    }

    /** Coarse search around the guess (or across everything without one), then a fine search. */
    private fun match(cam: List<SpeedPoint>, session: List<SpeedPoint>, guess: AlignmentResult?): Candidate? {
        val window = if (guess == null) wholeRange(cam, session) else searchWindowUs
        val center = guess?.sessionOffsetUs ?: 0L
        val grid = SpeedGrid(session)
        val coarse = search(cam, grid, center - window, center + window, CoarseStepUs) ?: return null
        return search(cam, grid, coarse.offsetUs - CoarseStepUs, coarse.offsetUs + CoarseStepUs, FineStepUs)
    }

    /** Offset from GPS UTC: each side's median of (time − UTC), differenced. */
    private fun utcGuess(camera: List<SpeedPoint>, session: List<SpeedPoint>): AlignmentResult? {
        val cam = camera.mapNotNull { p -> p.utcMillis?.let { p.timeUs - it * MicrosPerMilli } }
        val ses = session.mapNotNull { p -> p.utcMillis?.let { p.timeUs - it * MicrosPerMilli } }
        if (cam.isEmpty() || ses.isEmpty()) return null
        return AlignmentResult(median(ses) - median(cam), Alignment(AlignmentMethod.GPS_UTC, UtcUncertaintyUs))
    }

    private fun wholeRange(camera: List<SpeedPoint>, session: List<SpeedPoint>): Long {
        val span = { l: List<SpeedPoint> -> l.maxOf { it.timeUs } - l.minOf { it.timeUs } }
        return span(camera) + span(session)
    }

    private class Candidate(val offsetUs: Long, val rms: Double, val std: Double, val overlapUs: Long)

    private fun search(cam: List<SpeedPoint>, grid: SpeedGrid, from: Long, to: Long, step: Long): Candidate? {
        var best: Candidate? = null
        var offset = from
        val camFirst = cam.first().timeUs
        val camLast = cam.last().timeUs
        while (offset <= to) {
            // Skip offsets where the two tracks cannot overlap long enough.
            val overlap = minOf(camLast + offset, grid.endUs) - maxOf(camFirst + offset, grid.startUs)
            if (overlap < minOverlapUs) {
                offset += step
                continue
            }
            evaluate(cam, grid, offset)?.let { c ->
                val current = best
                if (c.overlapUs >= minOverlapUs && (current == null || c.rms < current.rms)) best = c
            }
            offset += step
        }
        return best
    }

    private fun evaluate(cam: List<SpeedPoint>, grid: SpeedGrid, offsetUs: Long): Candidate? {
        var n = 0
        var sumSq = 0.0
        var sum = 0.0
        var sumSqSpeed = 0.0
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        for (p in cam) {
            val t = p.timeUs + offsetUs
            val s = grid.at(t) ?: continue
            val d = p.speedMps - s
            sumSq += d * d
            sum += s
            sumSqSpeed += s * s
            n++
            if (t < first) first = t
            if (t > last) last = t
        }
        if (n < MinPoints) return null
        val mean = sum / n
        val variance = (sumSqSpeed / n - mean * mean).coerceAtLeast(0.0)
        return Candidate(offsetUs, sqrt(sumSq / n), sqrt(variance), last - first)
    }

    /** Session speed resampled every 10 ms by linear interpolation; gaps over 1 s have no value. */
    private class SpeedGrid(points: List<SpeedPoint>) {
        val startUs = points.first().timeUs
        val endUs = points.last().timeUs
        private val values: DoubleArray

        init {
            val n = ((points.last().timeUs - startUs) / FineStepUs + 1).toInt()
            values = DoubleArray(n) { Double.NaN }
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                if (b.timeUs - a.timeUs > MaxGapUs || b.timeUs <= a.timeUs) continue
                val from = ((a.timeUs - startUs + FineStepUs - 1) / FineStepUs).toInt()
                val to = ((b.timeUs - startUs) / FineStepUs).toInt()
                for (k in from..to) {
                    val t = startUs + k * FineStepUs
                    val f = (t - a.timeUs).toDouble() / (b.timeUs - a.timeUs)
                    values[k] = a.speedMps + f * (b.speedMps - a.speedMps)
                }
            }
        }

        fun at(t: Long): Double? {
            if (t < startUs) return null
            val k = ((t - startUs) / FineStepUs).toInt()
            return values.getOrNull(k)?.takeUnless { it.isNaN() }
        }
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private companion object {
        const val CoarseStepUs = 100_000L
        const val FineStepUs = 10_000L
        const val MaxGapUs = 1_000_000L
        const val MinPoints = 50
        const val MicrosPerMilli = 1000L
        const val MatchUncertaintyUs = 50_000L
        const val UtcUncertaintyUs = 1_000_000L
    }
}

/** Makes speed points from session GNSS speed in km/h. */
fun sessionSpeedPoint(tMicros: Long, speedKmh: Double, utcMillis: Long?): SpeedPoint =
    SpeedPoint(tMicros, speedKmh / KmhPerMps, utcMillis)

private const val KmhPerMps = 3.6
