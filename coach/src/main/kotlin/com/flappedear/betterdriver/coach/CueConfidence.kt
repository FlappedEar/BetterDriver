package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.Confidence
import java.util.Locale

// How far a corner cue can be trusted (FBBD-177): no precise advice from weak evidence. A pass
// whose positions stop for a while, or that nearly stops, is not something to coach from at all
// (Low: no cue, and never a target). Positions far apart, a speed worked out from positions, a
// difference no bigger than the usual pass-to-pass spread, too few passes to tell, or a corner not
// matched to the track's corners leave the cue Medium: said without its number.

/** A pass with no position for longer than this (or [CueGapIntervals] of its usual interval) is Low. */
const val CueGapMicros = 500_000L

/** ... or this many of its usual intervals, when that is longer. */
const val CueGapIntervals = 3

/** A pass whose positions are usually further apart than this is Medium. */
const val CueWeakIntervalMicros = 200_000L

/** A pass slower than this at the apex (a stop, a spin, the pit lane) is Low. */
const val CueVeryLowKmh = 15.0

/** Fewer recent passes of a corner than this leave its cue Medium: too few to know the spread. */
const val CueConsistentPasses = 2

/** Codes of [CueDoubt]s, as logged with the cue. */
object CueDoubts {
    const val GNSS_GAP = "gnss_gap"
    const val VERY_LOW_SPEED = "very_low_speed"
    const val WEAK_INTERPOLATION = "weak_interpolation"
    const val SPEED_NOT_MEASURED = "not_measured"
    const val SPREAD = "within_spread"
    const val FEW_PASSES = "few_passes"
    const val IDENTITY = "identity"

    /** A short text for a logged [code]. */
    fun text(code: String): String = when (code) {
        GNSS_GAP -> "positions stopped in the corner"
        VERY_LOW_SPEED -> "nearly stopped in the corner"
        WEAK_INTERPOLATION -> "positions far apart"
        SPEED_NOT_MEASURED -> "speed worked out from positions, not measured"
        SPREAD -> "within the usual pass-to-pass spread"
        FEW_PASSES -> "too few passes to tell"
        IDENTITY -> "corner not matched to the track's corners"
        else -> code
    }

    /** The cap a logged [code] puts on a cue. */
    fun cap(code: String): Confidence =
        if (code == GNSS_GAP || code == VERY_LOW_SPEED) Confidence.LOW else Confidence.MEDIUM
}

private fun doubt(code: String, text: String = CueDoubts.text(code)) = CueDoubt(code, CueDoubts.cap(code), text)

private const val MicrosPerSecond = 1_000_000.0

/** What weakens [pass] itself: a gap in its positions, a near stop, positions far apart, speed not measured. */
fun passDoubts(pass: CornerPass): List<CueDoubt> = buildList {
    val gapLimit = maxOf(CueGapMicros, CueGapIntervals * pass.intervalMicros)
    if (pass.gapMicros > gapLimit) {
        val seconds = pass.gapMicros / MicrosPerSecond
        add(doubt(CueDoubts.GNSS_GAP, String.format(Locale.ROOT, "no position for %.1f s", seconds)))
    }
    if (pass.minKmh < CueVeryLowKmh) add(doubt(CueDoubts.VERY_LOW_SPEED))
    if (pass.intervalMicros > CueWeakIntervalMicros) add(doubt(CueDoubts.WEAK_INTERPOLATION))
    if (!pass.speedMeasured) add(doubt(CueDoubts.SPEED_NOT_MEASURED))
}

/** Low: the pass is no evidence to coach from or towards. */
fun CornerPass.unusable(): Boolean = passDoubts(this).any { it.cap == Confidence.LOW }

/**
 * Why a cue from [last] towards [target] is not High: both passes' own doubts, a time to gain no
 * bigger than half the spread of the corner's recent [losses] (or too few of them to tell), and a
 * corner the track's corners do not name although it has them ([unidentified]).
 */
fun cueDoubts(last: CornerPass, target: CornerPass, losses: List<Long>, unidentified: Boolean): List<CueDoubt> {
    val doubts = (passDoubts(last) + passDoubts(target)).distinctBy { it.code }.toMutableList()
    val gain = last.timeMicros - target.timeMicros
    if (losses.size < CueConsistentPasses) {
        doubts += doubt(CueDoubts.FEW_PASSES)
    } else if (gain <= (losses.max() - losses.min()) / 2) {
        doubts += doubt(CueDoubts.SPREAD)
    }
    if (unidentified) doubts += doubt(CueDoubts.IDENTITY)
    return doubts.sortedBy { it.cap }
}

/** The confidence [doubts] leave: the lowest of their caps, High without any. */
fun confidenceOf(doubts: List<CueDoubt>): Confidence = Confidence.lowest(doubts.map { it.cap })
