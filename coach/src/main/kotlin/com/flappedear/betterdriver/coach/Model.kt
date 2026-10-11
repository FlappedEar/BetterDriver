package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.live.Fix
import kotlinx.serialization.Serializable

/** One GNSS fix with the receiver's speed, on the session clock. [speedKmh] is null if the receiver gave none. */
data class CoachFix(val tMicros: Long, val lat: Double, val lon: Double, val speedKmh: Double? = null) {
    fun toFix() = Fix(tMicros, lat, lon)
}

/** Which way a corner turns, from the heading before braking to the heading after the apex. */
@Serializable
enum class Turn { Left, Right, Straight }

/**
 * A braking corner of the reference lap (FBBD-96). Distances are metres along the reference path from
 * the start/finish line. Corners are numbered from 1 in lap order; only corners the driver brakes for
 * are listed, so the numbers are the coach's own, not the circuit's.
 */
@Serializable
data class Corner(
    val number: Int,
    /** Where the reference lap starts braking: its last speed peak before the corner. */
    val brakeAt: Double,
    /** Where the reference lap is slowest. */
    val apexAt: Double,
    val entryKmh: Double,
    val minKmh: Double,
    val turn: Turn,
)

/**
 * One drive through one corner, measured against the reference path (FBBD-96, FBBD-97). Distances are
 * metres along the reference path; [timeMicros] is the time across the corner's measuring window, so
 * passes of one corner compare directly.
 */
@Serializable
data class CornerPass(
    val corner: Int,
    val lap: Int,
    val brakeAt: Double,
    val apexAt: Double,
    val entryKmh: Double,
    val minKmh: Double,
    val timeMicros: Long,
)

/** How much the coach says (FBBD-27). */
enum class Verbosity {
    /** Nothing. */
    Off,

    /** Each timed lap's time, nothing about corners. */
    LapTimes,

    /** Cues only for the three corners losing the most time, plus lap times. */
    Focus,

    /** Cues for any corner, plus lap times. */
    Full,
}

/** Coach settings, set in the app (FBBD-98). */
data class CoachSettings(
    val verbosity: Verbosity = Verbosity.Focus,
    /** Delay between asking for speech and hearing it, such as Bluetooth audio lag; 0 to 2 s. */
    val audioDelayMillis: Int = 0,
    /** Timed laps driven in this session before the first corner cue. */
    val learningLaps: Int = DefaultLearningLaps,
) {
    init {
        require(audioDelayMillis in 0..MaxAudioDelayMillis) { "audio delay must be 0 to $MaxAudioDelayMillis ms" }
        require(learningLaps in 0..MaxLearningLaps) { "learning laps must be 0 to $MaxLearningLaps" }
    }

    companion object {
        const val DefaultLearningLaps = 3
        const val MaxLearningLaps = 10
        const val MaxAudioDelayMillis = 2_000
    }
}

/** What the coach asks for at one corner. */
sealed interface Advice {
    /** Braked earlier than the reference: brake [meters] later (a multiple of 5). */
    data class BrakeLater(val meters: Int) : Advice

    /** Braked later than the reference and lost speed at the apex: brake earlier. */
    data object BrakeEarlier : Advice

    /** Slower at the apex than the reference: carry [kmh] more. */
    data class CarrySpeed(val kmh: Int) : Advice
}

/** Why a cue was spoken. */
enum class CueKind { Corner, LapTime }

/**
 * One spoken cue (FBBD-97). [seq] counts the session's cues from 1. For a corner cue, [before] is the
 * last pass it was based on and [target] the best pass it aims at.
 */
data class Cue(
    val seq: Int,
    val tMicros: Long,
    val lap: Int,
    val kind: CueKind,
    val text: String,
    val corner: Int? = null,
    val advice: Advice? = null,
    val before: CornerPass? = null,
    val target: CornerPass? = null,
)

/**
 * The pass right after a corner cue (FBBD-28): [improved] says whether the driver moved the way the
 * cue asked; [gainMicros] is the time gained across the corner window against [Cue.before]
 * (positive is faster).
 */
data class CueResult(
    val tMicros: Long,
    val cue: Cue,
    val after: CornerPass,
    val improved: Boolean,
    val gainMicros: Long,
)

/** What the track screen's cue indicator shows (FBBD-98). */
sealed interface CoachPhase {
    /** No saved track with a start/finish line, so no laps and no cues. */
    data object NoTrack : CoachPhase

    /** Laps are being learned: [done] of [needed] timed laps. */
    data class Learning(val done: Int, val needed: Int) : CoachPhase

    data object Coaching : CoachPhase

    /** Verbosity is off or lap times only. */
    data object Quiet : CoachPhase
}
