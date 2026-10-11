package com.flappedear.betterdriver.feature.review

import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.timing.replay.LapAlignment
import kotlin.math.abs

/** The chart under the videos at one place of lap A: [deltaMicros] is B minus A, speeds in km/h. */
data class SeriesPoint(val distanceMeters: Double, val deltaMicros: Long, val speedA: Double?, val speedB: Double?)

/**
 * Lap [b] against lap [a] (FBBD-93). Everything runs on lap A's clock: the review cursor is a time
 * in lap A, and lap B is shown where it was at the same place of the track ([LapAlignment]).
 */
class Comparison(val a: ReviewLap, val b: ReviewLap, stepMeters: Double = LapAlignment.DefaultStepMeters) {
    val alignment = LapAlignment(a.trace, b.trace)

    val lengthMicros: Long get() = a.trace.lap.timeMicros

    val series: List<SeriesPoint> = alignment.deltaByDistance(stepMeters).map { p ->
        val tb = alignment.otherMicrosAt(p.referenceLapMicros)
        SeriesPoint(p.distanceMeters, p.deltaMicros, a.trace.at(p.referenceLapMicros).speedKmh, b.trace.at(tb).speedKmh)
    }

    /** Lap B's time at the place lap A was at [aLapMicros]. */
    fun bMicrosAt(aLapMicros: Long): Long = alignment.otherMicrosAt(aLapMicros)

    /**
     * How fast lap B's clock runs against lap A's around [aLapMicros]: above 1 where B was slower
     * there. Lap B's video plays at this rate (times the review speed) to stay at A's place.
     */
    fun bRateAt(aLapMicros: Long): Double {
        val t0 = (aLapMicros - RateWindowMicros / 2).coerceIn(0, lengthMicros)
        val t1 = (t0 + RateWindowMicros).coerceAtMost(lengthMicros)
        if (t1 <= t0) return 1.0
        val rate = (bMicrosAt(t1) - bMicrosAt(t0)).toDouble() / (t1 - t0)
        return rate.coerceIn(MinRate, MaxRate)
    }

    fun distanceAt(aLapMicros: Long): Double = a.trace.at(aLapMicros).distanceMeters

    /** Lap A's time at [meters] along it, for scrubbing on the chart. */
    fun aMicrosAtDistance(meters: Double): Long = a.trace.lapMicrosAtDistance(meters)

    companion object {
        private const val RateWindowMicros = 1_000_000L
        private const val MinRate = 0.5
        private const val MaxRate = 2.0
    }
}

/** Where a lap's video should be: [clip] at [mediaMillis]. */
data class VideoTarget(val clip: VideoClip, val mediaMillis: Long)

/** The video frame of this lap at [lapMicros] since its start; null where the lap has no video. */
fun ReviewLap.videoAt(lapMicros: Long): VideoTarget? {
    val pos = video?.at(trace.lap.startMicros + lapMicros) ?: return null
    return if (pos.mediaMicros < 0) null else VideoTarget(pos.clip, pos.mediaMicros / MicrosPerMilli)
}

/** What a player following its target does now: seek (if [seekMillis] is set) and play at [speed]. */
data class FollowStep(val seekMillis: Long?, val speed: Float)

/**
 * Keeps a video player on its target (FBBD-93). Paused or scrubbing, it seeks to every new frame.
 * Playing, small drift is taken out by playing slightly faster or slower, so the picture never
 * jumps; only drift beyond [MaxDriftMillis] (after a stall or a clip change) seeks.
 * [rate] is how fast the target moves against real time.
 */
fun followStep(positionMillis: Long, targetMillis: Long, rate: Double, playing: Boolean): FollowStep {
    val err = positionMillis - targetMillis
    val base = rate.coerceIn(MinSpeed, MaxSpeed)
    return when {
        !playing -> FollowStep(if (abs(err) > PausedToleranceMillis) targetMillis else null, base.toFloat())
        abs(err) > MaxDriftMillis -> FollowStep(targetMillis, base.toFloat())
        abs(err) <= DeadbandMillis -> FollowStep(null, base.toFloat())
        else -> {
            val k = (1 - err.toDouble() / CorrectionMillis).coerceIn(1 - MaxCorrection, 1 + MaxCorrection)
            FollowStep(null, (base * k).coerceIn(MinSpeed, MaxSpeed).toFloat())
        }
    }
}

/** The review cursor after [elapsedNanos] of real time at [speed]; stops at the end of the lap. */
fun advance(lapMicros: Long, elapsedNanos: Long, speed: Double, lengthMicros: Long): Long =
    (lapMicros + (elapsedNanos / NanosPerMicro * speed).toLong()).coerceIn(0, lengthMicros)

const val PausedToleranceMillis = 20L
const val MaxDriftMillis = 350L
const val DeadbandMillis = 30L

/** Drift of this many milliseconds is taken out over about a second. */
private const val CorrectionMillis = 1_000.0
private const val MaxCorrection = 0.3
private const val MinSpeed = 0.1
private const val MaxSpeed = 4.0
private const val MicrosPerMilli = 1_000L
private const val NanosPerMicro = 1_000.0
