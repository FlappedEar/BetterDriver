package com.flappedear.betterdriver.feature.review

import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.timing.replay.LapAlignment
import com.flappedear.betterdriver.timing.replay.LapTrace
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

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
 * jumps; only drift beyond [MaxDriftMillis] (after a stall or a clip change) seeks. The correction
 * moves in [SpeedStep]s, so the same drift gives the same speed frame after frame (FBBD-429).
 * [rate] is how fast the target moves against real time.
 *
 * [justStopped]: the player played until now. Its position is where it was while playing, and it
 * plays on until the pause reaches its playback thread, so however close that position is it says
 * nothing about the frame it stops on: it seeks to the target's exact frame (FBBD-214).
 *
 * [deadbandMillis]: drift left alone while playing. The player with the sound takes
 * [SoundDeadbandMillis]: each of its rate changes restarts its audio, a hitch on the Mac (FBBD-429).
 */
fun followStep(
    positionMillis: Long,
    targetMillis: Long,
    rate: Double,
    playing: Boolean,
    justStopped: Boolean = false,
    deadbandMillis: Long = DeadbandMillis,
): FollowStep {
    val err = positionMillis - targetMillis
    val base = rate.coerceIn(MinSpeed, MaxSpeed)
    return when {
        !playing && justStopped -> FollowStep(targetMillis, base.toFloat())
        !playing -> FollowStep(if (abs(err) > PausedToleranceMillis) targetMillis else null, base.toFloat())
        abs(err) > MaxDriftMillis -> FollowStep(targetMillis, base.toFloat())
        abs(err) <= deadbandMillis -> FollowStep(null, base.toFloat())
        else -> {
            val k = (1 - err.toDouble() / CorrectionMillis).coerceIn(1 - MaxCorrection, 1 + MaxCorrection)
            // In whole steps: the player's rate then changes a few times a correction, not on every
            // display frame. Each rate change resyncs the player's clock and audio (FBBD-429).
            val stepped = round(k / SpeedStep) * SpeedStep
            FollowStep(null, (base * stepped).coerceIn(MinSpeed, MaxSpeed).toFloat())
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
 * Whether a playing player should be set to [wantedSpeed] now. [currentSpeed] is the speed it was
 * last set to, null when it has just started playing or the review speed changed (then at once);
 * [sinceLastMillis] is how long ago that was. A drift correction changes the rate at most every
 * [MinRateGapMillis]: on the iPad app on a Mac every AVPlayer rate change restarted its audio
 * pipeline, and with a new rate on nearly every display frame (~14 a second) the players fell
 * behind and stayed pinned at the fastest correction (FBBD-429).
 */
fun rateChangeDue(currentSpeed: Float?, wantedSpeed: Float, sinceLastMillis: Long): Boolean =
    currentSpeed == null || (wantedSpeed != currentSpeed && sinceLastMillis >= MinRateGapMillis)

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

/** The drift the player with the sound is left with while playing (FBBD-429). */
const val SoundDeadbandMillis = 120L

/** The [followStep] deadband of a player with or without the sound. */
fun deadbandFor(sound: Boolean): Long = if (sound) SoundDeadbandMillis else DeadbandMillis

/** The shortest time between two drift-correction rate changes of a playing player (FBBD-429). */
const val MinRateGapMillis = 500L

/** The drift correction's speed steps, as a share of the review speed (FBBD-429). */
const val SpeedStep = 0.02

/** Drift of this many milliseconds is taken out over about a second. */
private const val CorrectionMillis = 1_000.0
private const val MaxCorrection = 0.3
private const val MinSpeed = 0.1
private const val MaxSpeed = 4.0
private const val NanosPerMicro = 1_000.0

/**
 * What one stretch of lap review playback did, for the app log (FBBD-429): display frames and the
 * time the frame loop took, player rate changes, seeks while playing, synced starts and the largest
 * drift of a playing player. A Mac log without these numbers cannot tell a stutter's cause.
 */
class LapReviewStats {
    var frames = 0
        private set
    var rateChanges = 0
        private set
    var playingSeeks = 0
        private set
    var syncedStarts = 0
        private set
    var maxDriftMillis = 0L
        private set
    private var workMicros = 0L
    private var maxWorkMicros = 0L
    /** Frames that took longer than a display frame of 60 Hz. */
    private var slowFrames = 0

    fun frame(workMicros: Long) {
        frames++
        this.workMicros += workMicros
        if (workMicros > maxWorkMicros) maxWorkMicros = workMicros
        if (workMicros > SlowFrameMicros) slowFrames++
    }

    fun rateChanged() { rateChanges++ }

    fun seekedWhilePlaying() { playingSeeks++ }

    fun startedSynced() { syncedStarts++ }

    fun drift(millis: Long) {
        val d = abs(millis)
        if (d > maxDriftMillis) maxDriftMillis = d
    }

    /** One log line for [playedMillis] of playback; null when nothing played. */
    fun line(playedMillis: Long): String? {
        if (frames == 0) return null
        val mean = workMicros / frames
        return "played ${playedMillis / MillisPerSecondL}.${(playedMillis % MillisPerSecondL) / TenthMillis} s: " +
            "$frames frames, work mean ${micros(mean)} max ${micros(maxWorkMicros)} ms, $slowFrames slow, " +
            "$rateChanges rate changes, $playingSeeks seeks while playing, $syncedStarts synced starts, " +
            "max drift $maxDriftMillis ms"
    }

    fun reset() {
        frames = 0
        rateChanges = 0
        playingSeeks = 0
        syncedStarts = 0
        maxDriftMillis = 0
        workMicros = 0
        maxWorkMicros = 0
        slowFrames = 0
    }

    private fun micros(us: Long): String = "${us / MicrosPerMilliL}.${(us % MicrosPerMilliL) / TenthMicros}"

    private companion object {
        const val SlowFrameMicros = 16_667L
        const val MillisPerSecondL = 1_000L
        const val TenthMillis = 100L
        const val MicrosPerMilliL = 1_000L
        const val TenthMicros = 100L
    }
}
