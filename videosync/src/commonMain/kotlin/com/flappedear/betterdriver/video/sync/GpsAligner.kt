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
 *    offset within [searchWindowUs] or the guess's own uncertainty, whichever is larger, first
 *    in coarse steps and then in 10 ms steps, and the offset with the smallest RMS difference wins.
 *    Speed changes all the time on track, so the match is sharp; it needs the car to move during
 *    the overlap.
 *
 * A match is accepted only with at least [minOverlapUs] of overlap, enough speed variation and a
 * small RMS error; otherwise the UTC guess (or the prior) is returned as it is.
 *
 * Times come from files, so they are bounded before any work depends on them (audit BD-02): a point
 * further than [MaxAbsTimeUs] from zero is dropped, the session speed grid covers only the part the
 * camera can overlap and never more than [MaxGridPoints] steps, and a search of more than
 * [MaxSearchSteps] offsets is not made. Past a bound there is no match and the guess is returned.
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
        val cam = camera.filter { inRange(it.timeUs) }
        val ses = session.filter { inRange(it.timeUs) }
        if (cam.isEmpty() || ses.isEmpty()) return prior
        val guess = utcGuess(cam, ses) ?: prior
        val match = match(cam.sortedBy { it.timeUs }, ses.sortedBy { it.timeUs }, guess)
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
    @Suppress("ReturnCount") // One reason per bound.
    private fun match(cam: List<SpeedPoint>, session: List<SpeedPoint>, guess: AlignmentResult?): Candidate? {
        val window = guess?.let { maxOf(searchWindowUs, it.alignment.uncertaintyUs) } ?: wholeRange(cam, session)
        val center = guess?.sessionOffsetUs ?: 0L
        // A guess from a file or a caller can be anything; within these bounds no sum below overflows.
        if (window !in 0..MaxSearchSpanUs || !inRange(center)) return null
        if (window / CoarseStepUs > MaxSearchSteps) return null
        val from = center - window - CoarseStepUs
        val to = center + window + CoarseStepUs
        // Only the session time the camera can overlap at some searched offset.
        val gridFrom = maxOf(session.first().timeUs, cam.first().timeUs + from)
        val gridTo = minOf(session.last().timeUs, cam.last().timeUs + to)
        if (gridTo < gridFrom || (gridTo - gridFrom) / FineStepUs >= MaxGridPoints) return null
        val grid = SpeedGrid(session, gridFrom, gridTo)
        val coarse = search(cam, grid, center - window, center + window, CoarseStepUs) ?: return null
        return search(cam, grid, coarse.offsetUs - CoarseStepUs, coarse.offsetUs + CoarseStepUs, FineStepUs)
    }

    /** Offset from GPS UTC: each side's median of (time − UTC), differenced. */
    private fun utcGuess(camera: List<SpeedPoint>, session: List<SpeedPoint>): AlignmentResult? {
        val clockMinusUtc = { p: SpeedPoint ->
            p.utcMillis?.takeIf { it in 0..MaxUtcMillis }?.let { p.timeUs - it * MicrosPerMilli }
        }
        val cam = camera.mapNotNull(clockMinusUtc)
        val ses = session.mapNotNull(clockMinusUtc)
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

    /**
     * Session speed from [startUs] to [endUs] resampled every 10 ms by linear interpolation; gaps over
     * 1 s have no value. The caller keeps the span under [MaxGridPoints] steps.
     */
    private class SpeedGrid(points: List<SpeedPoint>, val startUs: Long, val endUs: Long) {
        private val values: DoubleArray

        init {
            val n = ((endUs - startUs) / FineStepUs + 1).toInt()
            values = DoubleArray(n) { Double.NaN }
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                val usable = b.timeUs > a.timeUs && b.timeUs - a.timeUs <= MaxGapUs
                if (!usable || b.timeUs < startUs || a.timeUs > endUs) continue
                val from = ((maxOf(a.timeUs, startUs) - startUs + FineStepUs - 1) / FineStepUs).toInt()
                val to = ((minOf(b.timeUs, endUs) - startUs) / FineStepUs).toInt()
                for (k in from..to) {
                    val t = startUs + k * FineStepUs
                    val f = (t - a.timeUs).toDouble() / (b.timeUs - a.timeUs)
                    values[k] = a.speedMps + f * (b.speedMps - a.speedMps)
                }
            }
        }

        fun at(t: Long): Double? {
            if (t < startUs || t > endUs) return null
            val k = ((t - startUs) / FineStepUs).toInt()
            return values.getOrNull(k)?.takeUnless { it.isNaN() }
        }
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun inRange(t: Long) = t in -MaxAbsTimeUs..MaxAbsTimeUs

    companion object {
        /** About three years: no session or video is longer, and sums of such times cannot overflow. */
        const val MaxAbsTimeUs = 100_000_000_000_000L

        /** 6 h of 10 ms steps: 17 MB of session speed at most. */
        const val MaxGridPoints = 2_160_000L

        /** Coarse offsets tried at most: ±12 h at 100 ms. */
        const val MaxSearchSteps = 432_000L
        private const val MaxSearchSpanUs = 4 * MaxAbsTimeUs

        /** Year 2286: a later GPS time is damage, and its microseconds would overflow sums. */
        private const val MaxUtcMillis = 10_000_000_000_000L

        private const val CoarseStepUs = 100_000L
        private const val FineStepUs = 10_000L
        private const val MaxGapUs = 1_000_000L
        private const val MinPoints = 50
        private const val MicrosPerMilli = 1000L
        private const val MatchUncertaintyUs = 50_000L
        private const val UtcUncertaintyUs = 1_000_000L
    }
}

/** Makes speed points from session GNSS speed in km/h. */
fun sessionSpeedPoint(tMicros: Long, speedKmh: Double, utcMillis: Long?): SpeedPoint =
    SpeedPoint(tMicros, speedKmh / KmhPerMps, utcMillis)

private const val KmhPerMps = 3.6
