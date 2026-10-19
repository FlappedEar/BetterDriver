package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.timing.live.Fix
import kotlinx.serialization.Serializable

/**
 * One GNSS fix with the receiver's speed, on the session clock. [speedKmh] is null if the receiver gave none.
 * [ageMicros]: how long before [tMicros] the fix was taken, when known (FBBD-229: a RaceBox fix arrives
 * about 0.8 s after it was taken); by then the car is that much further on.
 */
data class CoachFix(
    val tMicros: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double? = null,
    val ageMicros: Long = 0,
) {
    fun toFix() = Fix(tMicros, lat, lon)
}

/** Which way a corner turns, from the heading before braking to the heading after the apex. */
@Serializable
enum class Turn { Left, Right, Straight }

/**
 * A braking corner of the reference lap (FBBD-96). Distances are metres along the reference path from
 * the start/finish line. Corners are numbered from 1 in lap order; only corners the driver brakes for
 * are listed, so [number] is the coach's own key for it. What the driver hears and reads is [turn]:
 * the number of the track's corner it is in (FBBD-172, [cornerId] is that corner's id), the same
 * number the lap analysis shows. A track without a corner identity yet has neither, and the coach's
 * own number is said.
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
    /** The track's CornerId of the corner this braking corner is in (FBBD-172). */
    val cornerId: String? = null,
    /** The corner number the driver hears: the track corner's (FBBD-172). */
    val turnNumber: Int? = null,
    /**
     * Where the corner starts on the reference path: the start of its track corner (FBBD-172), the
     * point lap analysis measures braking to (FBBD-242). Null without a corner identity, for the
     * second braking corner of a chain, or for an apex on a straight.
     */
    val entryAt: Double? = null,
) {
    /** The number said and shown for it: the track's corner number, else the coach's own. */
    val label: Int get() = turnNumber ?: number
}

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
    /**
     * The longest time without a new position across the window, entering and leaving it included
     * (FBBD-177); 0 when not known (a pass stored before it was measured).
     */
    val gapMicros: Long = 0,
    /** The usual (median) time between positions in the window; 0 when not known. */
    val intervalMicros: Long = 0,
    /** Every speed in the window came from the receiver, none worked out from positions. */
    val speedMeasured: Boolean = true,
)

/** How much the coach says (FBBD-27). */
enum class Verbosity {
    /** Nothing. */
    Off,

    /** Each timed lap's time, nothing about corners. */
    LapTimes,

    /** Cues only for three corners, those picked in the lap analysis first, then those losing most; lap times. */
    Focus,

    /** Cues for any corner, plus lap times. */
    Full,
}

/** How the coach voices its phrases (FBBD-48). */
enum class VoiceMode {
    /** Text-to-speech says each phrase as it is asked for. */
    Live,

    /** Every phrase's words and numbers are rendered to clips at coach start and joined when asked for. */
    PreRendered,
}

/** Coach settings, set in the app (FBBD-98). */
data class CoachSettings(
    val verbosity: Verbosity = Verbosity.Focus,
    /** Delay between asking for speech and hearing it, such as Bluetooth audio lag; 0 to 2 s. */
    val audioDelayMillis: Int = 0,
    /** Timed laps driven in this session before the first corner cue. */
    val learningLaps: Int = DefaultLearningLaps,
    /** How cues are voiced (FBBD-48): live text-to-speech, or clips rendered at coach start. */
    val voice: VoiceMode = VoiceMode.Live,
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

/**
 * What the coach asks for at one corner. Below High confidence (FBBD-177) it is said without its
 * numbers ([precise] false): "brake a little later", not "brake at 100, 10 later".
 *
 * Each carries the target as well as the change (FBBD-242, UI-87 C, UI-88 C): [BrakeLater.at] and
 * [BrakeEarlier.at] are where to brake, in metres before the corner ([brakeAnchor]), and
 * [CarrySpeed.goalKmh] the apex speed to reach. Advice logged before FBBD-242 has no target (null).
 */
sealed interface Advice {
    /** Said with its numbers (High confidence). */
    val precise: Boolean

    /** Braked earlier than the reference: brake [meters] later (a multiple of 5), at [at] m before the corner. */
    data class BrakeLater(val meters: Int, override val precise: Boolean = true, val at: Int? = null) : Advice

    /**
     * Braked later than the reference and lost speed at the apex: brake [meters] earlier, at [at] m
     * before the corner; both null in advice logged before FBBD-242.
     */
    data class BrakeEarlier(val meters: Int? = null, override val precise: Boolean = true, val at: Int? = null) : Advice

    /** Slower at the apex than the reference: carry [kmh] more, [goalKmh] at the apex. */
    data class CarrySpeed(val kmh: Int, override val precise: Boolean = true, val goalKmh: Int? = null) : Advice

    /** The same advice, said with its numbers only when [precise]. */
    fun withPrecision(precise: Boolean): Advice = when (this) {
        is BrakeLater -> copy(precise = precise)
        is BrakeEarlier -> copy(precise = precise)
        is CarrySpeed -> copy(precise = precise)
    }
}

/** Why a corner cue is not High confidence (FBBD-177): a short [code] for the log and its [text]. */
data class CueDoubt(val code: String, val cap: Confidence, val text: String)

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
    /**
     * Metres from the car to where braking for the corner may start, when the cue was asked for
     * (FBBD-183): with the GNSS distance driven until its sound started, the margin it was heard with.
     */
    val zoneMeters: Double? = null,
    /** For a corner cue, the corner number said ([Corner.label]); null in cues logged before FBBD-172. */
    val turn: Int? = null,
    /** For a corner cue, the track's CornerId of the corner (FBBD-172), when the track has one. */
    val cornerId: String? = null,
    /**
     * For a corner cue, how far the evidence behind it can be trusted (FBBD-177): High says it with
     * numbers, Medium without; a Low one is never said. [doubts] say why it is not High.
     */
    val confidence: Confidence = Confidence.HIGH,
    val doubts: List<CueDoubt> = emptyList(),
) {
    /** The corner number the driver heard, for a corner cue. */
    val heardTurn: Int? get() = turn ?: corner
}

/**
 * The pass right after a corner cue (FBBD-28): [improved] says whether the driver moved the way the
 * cue asked; [gainMicros] is the time gained across the corner window against [Cue.before]
 * (positive is faster). [confidence] is how far [after] itself can be trusted (FBBD-177): the
 * review gives the change in numbers only when it and the cue are both High.
 */
data class CueResult(
    val tMicros: Long,
    val cue: Cue,
    val after: CornerPass,
    val improved: Boolean,
    val gainMicros: Long,
    val confidence: Confidence,
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
