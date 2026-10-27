package com.flappedear.betterdriver.feature.review

import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.timing.replay.LapAlignment
import com.flappedear.betterdriver.timing.replay.LapTrace
import kotlin.math.abs
import kotlin.math.max

// The lap review's timing (FBBD-93), common Kotlin so the iPad review plays two laps as Android
// does (FBBD-331). Android's Comparison wraps TraceComparison with the laps' sessions and video.

/** The chart under the videos at one place of lap A: [deltaMicros] is B minus A, speeds in km/h. */
data class SeriesPoint(val distanceMeters: Double, val deltaMicros: Long, val speedA: Double?, val speedB: Double?)

/**
 * Lap [b] against lap [a] (FBBD-93). The chart runs over lap A's distance, with the delta of B at
 * the same place ([LapAlignment]). Playback runs on time since the start line, the same for both
 * laps (UI-60 B): both cars cross the line together and run free, so the faster one pulls ahead
 * and the gap shows as distance.
 */
class TraceComparison(val a: LapTrace, val b: LapTrace, stepMeters: Double = LapAlignment.DefaultStepMeters) {
    val alignment = LapAlignment(a, b)

    /** Until the slower lap reaches the line. */
    val lengthMicros: Long get() = max(a.lap.timeMicros, b.lap.timeMicros)

    val series: List<SeriesPoint> = alignment.deltaByDistance(stepMeters).map { p ->
        val tb = alignment.otherMicrosAt(p.referenceLapMicros)
        SeriesPoint(p.distanceMeters, p.deltaMicros, a.at(p.referenceLapMicros).speedKmh, b.at(tb).speedKmh)
    }

    /** Lap A's own time at [micros] since the line; it stays at the line once A has finished. */
    fun aMicros(micros: Long): Long = micros.coerceIn(0, a.lap.timeMicros)

    /** Lap B's own time at [micros] since the line. */
    fun bMicros(micros: Long): Long = micros.coerceIn(0, b.lap.timeMicros)

    /**
     * Where each car is at [micros] since the line, as a fraction of lap A's distance. Lap B is
     * placed by track position ([LapAlignment]), not by its own odometer, so a different line through
     * a corner does not show as a gap.
     */
    fun aFraction(micros: Long): Double = aDistance(aMicros(micros)) / aLength
    fun bFraction(micros: Long): Double = aDistance(alignment.referenceMicrosAt(bMicros(micros))) / aLength

    /** Metres lap B is behind lap A at [micros] since the line (negative: ahead), along lap A. */
    fun gapMetersAt(micros: Long): Double = (aFraction(micros) - bFraction(micros)) * aLength

    /**
     * The review time for a scrub to [meters] along lap A: when the later of the two cars is there,
     * so dragging to the end reaches the end of the slower lap.
     */
    fun microsAtDistance(meters: Double): Long {
        val ta = a.lapMicrosAtDistance(meters)
        return max(ta, alignment.otherMicrosAt(ta))
    }

    private val aLength = max(a.lengthMeters, 1.0)

    private fun aDistance(lapMicros: Long): Double = a.at(lapMicros).distanceMeters.coerceIn(0.0, aLength)
}

/** Where a lap's video should be: [clip] at [mediaMillis]. */
data class VideoTarget(val clip: VideoClip, val mediaMillis: Long)

/** What a player following its target does now: seek (if [seekMillis] is set) and play at [speed]. */
data class FollowStep(val seekMillis: Long?, val speed: Float)

/**
 * Keeps a video player on its target (FBBD-93). Paused or scrubbing, it seeks to every new frame.
 * Playing, small drift is taken out by playing slightly faster or slower, so the picture never
 * jumps; only drift beyond [MaxDriftMillis] (after a stall or a clip change) seeks.
 * [rate] is how fast the target moves against real time.
 *
 * [justStopped]: the player played until now. Its position is where it was while playing, and it
 * plays on until the pause reaches its playback thread, so however close that position is it says
 * nothing about the frame it stops on: it seeks to the target's exact frame (FBBD-214).
 */
fun followStep(
    positionMillis: Long,
    targetMillis: Long,
    rate: Double,
    playing: Boolean,
    justStopped: Boolean = false,
): FollowStep {
    val err = positionMillis - targetMillis
    val base = rate.coerceIn(MinSpeed, MaxSpeed)
    return when {
        !playing && justStopped -> FollowStep(targetMillis, base.toFloat())
        !playing -> FollowStep(if (abs(err) > PausedToleranceMillis) targetMillis else null, base.toFloat())
        abs(err) > MaxDriftMillis -> FollowStep(targetMillis, base.toFloat())
        abs(err) <= DeadbandMillis -> FollowStep(null, base.toFloat())
        else -> {
            val k = (1 - err.toDouble() / CorrectionMillis).coerceIn(1 - MaxCorrection, 1 + MaxCorrection)
            FollowStep(null, (base * k).coerceIn(MinSpeed, MaxSpeed).toFloat())
        }
    }
}

/**
 * Whether a player last sent to [lastMillis] (null: not yet, or the exact frame is wanted again)
 * must seek to [targetMillis]. Not `abs(target - Long.MIN_VALUE)`: that overflows, and at media
 * time 0 it is negative, so the first frame of a clip was never sought exactly.
 */
fun seekDue(targetMillis: Long, lastMillis: Long?): Boolean =
    lastMillis == null || abs(targetMillis - lastMillis) > PausedToleranceMillis

/**
 * Whether a player last sent to [lastSeekMillis] still has a seek to make for [step], so it needs
 * another frame even while the review is still (a seek held back to space seeks out, or the exact
 * frame wanted again when the finger lifts).
 */
fun seekPending(step: FollowStep, lastSeekMillis: Long?): Boolean =
    step.seekMillis?.let { seekDue(it, lastSeekMillis) } == true

/**
 * Whether the review needs the next display frame: while it plays, while a finger scrubs, and
 * until both players have made their last seek. Otherwise the screen stays idle until the cursor,
 * play or scrub changes, so a paused review costs no frames.
 */
fun frameLoopNeeded(playing: Boolean, scrubbing: Boolean, seekPending: Boolean): Boolean =
    playing || scrubbing || seekPending

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
private const val NanosPerMicro = 1_000.0
