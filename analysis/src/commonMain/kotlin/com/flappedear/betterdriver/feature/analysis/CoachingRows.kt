@file:Suppress("TooManyFunctions") // The card's rows, figure and words, one function each.

package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.coaching.FocusVerdict
import com.flappedear.betterdriver.analysis.coaching.ImprovedSeconds
import com.flappedear.betterdriver.analysis.coaching.Persistence
import com.flappedear.betterdriver.analysis.coaching.SessionCoaching
import com.flappedear.betterdriver.analysis.coaching.attemptText
import com.flappedear.betterdriver.analysis.coaching.checkText
import com.flappedear.betterdriver.analysis.line.CornerProof
import com.flappedear.betterdriver.analysis.line.LineProof
import com.flappedear.betterdriver.analysis.line.ProofVerdict
import com.flappedear.betterdriver.analysis.progression.DayZone
import com.flappedear.betterdriver.analysis.progression.systemDayZone
import com.flappedear.betterdriver.format.fixed
import kotlin.math.abs

// The coaching history card of the session summary (FBBD-180, FBBD-310) in words. Common Kotlin, so
// the Android and iOS summaries show the same rows (FBBD-364); each app only draws them.

/** The card's heading over what came of the focus this session worked on. */
const val LastFocusLabel = "Last focus · what came of it"

/** The card's heading over the earlier gains checked in this session. */
const val EarlierGainsLabel = "Earlier gains · did they hold"

/**
 * The coaching history as the summary shows it: what came of the focus worked on, then the gains
 * checked. A focus corner [proof] measured (FBBD-310, UI-191 A) says how much came back, how much is
 * left to the target and whether the coach spoke there; the others say what the history found.
 * [zone] dates the sessions the checked gains came from.
 */
fun coachingRows(
    coaching: SessionCoaching,
    zone: DayZone = systemDayZone(),
    proof: LineProof? = null,
): List<CoachingRow> {
    val attempts = coaching.attempts.map { a ->
        measuredCorner(proof, a.ask.cornerId)?.let { p ->
            return@map CoachingRow(a.ask.name, proofStatus(p), proofText(p), false, a.ask.cornerId, measured = true)
        }
        val status = when (a.verdict) {
            FocusVerdict.Improved -> CoachingStatus.Improved
            FocusVerdict.NoGain -> CoachingStatus.NoGain
            FocusVerdict.TooFewPasses, FocusVerdict.NotDriven, FocusVerdict.NotComparable -> CoachingStatus.Pending
        }
        CoachingRow(a.ask.name, status, attemptText(a), !a.timePrecise, a.ask.cornerId)
    }
    val checks = coaching.checks.map { c ->
        val status = when (c.persistence) {
            Persistence.Persisted -> CoachingStatus.Learned
            Persistence.Partial -> CoachingStatus.Partly
            Persistence.Reverted -> CoachingStatus.Lost
        }
        CoachingRow(c.attempt.ask.name, status, checkText(c, zone), !c.timePrecise, c.attempt.ask.cornerId, false)
    }
    return attempts + checks
}

/**
 * The coaching history card as both apps draw it (FBBD-364): the focus this session worked on
 * ([attempts]) under [LastFocusLabel], with the time won back on top ([wonBack], [wonBackText],
 * [wonBackLost]) and [note] under measured corners; then the earlier gains checked ([checks]) under
 * [EarlierGainsLabel]. [empty]: no card.
 */
data class CoachingCardUi(
    val attempts: List<CoachingRow>,
    val checks: List<CoachingRow>,
    val wonBack: String?,
    val wonBackText: String?,
    val wonBackLost: Boolean,
    val note: String?,
) {
    val empty: Boolean get() = attempts.isEmpty() && checks.isEmpty()

    companion object {
        val None = CoachingCardUi(emptyList(), emptyList(), null, null, false, null)
    }
}

/** The card of [rows] and [wonBackSeconds], as [coachingRows] and [lastFocusWonBack] give them. */
fun coachingCard(rows: List<CoachingRow>, wonBackSeconds: Double?): CoachingCardUi {
    val attempts = rows.filter { it.attempt }
    return CoachingCardUi(
        attempts = attempts,
        checks = rows.filter { !it.attempt },
        wonBack = wonBackSeconds?.takeIf { attempts.isNotEmpty() }?.let(::wonBackFigure),
        wonBackText = wonBackSeconds?.takeIf { attempts.isNotEmpty() }?.let(::wonBackText),
        wonBackLost = (wonBackSeconds ?: 0.0) < 0,
        note = ProofNote.takeIf { attempts.any { it.measured } },
    )
}

/** The card of [coaching], its focus corners measured by [proof]; sessions dated in [zone]. */
fun coachingCard(coaching: SessionCoaching, zone: DayZone = systemDayZone(), proof: LineProof? = null): CoachingCardUi =
    coachingCard(coachingRows(coaching, zone, proof), lastFocusWonBack(coaching, proof))

/**
 * The time won back over the focus corners [proof] measured that [coaching] worked on (UI-191 A),
 * slower ones counting against it; null when it compared none of them (none driven, say).
 */
fun lastFocusWonBack(coaching: SessionCoaching, proof: LineProof?): Double? {
    val deltas = coaching.attempts.mapNotNull { measuredCorner(proof, it.ask.cornerId)?.wonBackSeconds }
    return if (deltas.isEmpty()) null else deltas.sum()
}

/** The time won back, as the card's big number shows it: "0.12 s", without its sign. */
fun wonBackFigure(seconds: Double): String = "${fixed(abs(seconds), 2)} s"

/** What the big number is, under it: won back, or lost when the corners got slower. */
fun wonBackText(seconds: Double): String =
    if (seconds < 0) "lost on the corners you worked on" else "won back on the corners you worked on"

private fun measuredCorner(proof: LineProof?, cornerId: String): CornerProof? =
    proof?.corners?.firstOrNull { it.cornerId == cornerId && it.verdict != ProofVerdict.NotComparable }

private fun proofStatus(p: CornerProof) = when (p.verdict) {
    ProofVerdict.Faster -> if (p.cues == 0) CoachingStatus.FasterNoCue else CoachingStatus.Faster
    ProofVerdict.Slower -> CoachingStatus.Slower
    ProofVerdict.NotDriven -> CoachingStatus.NotDriven
    ProofVerdict.Same, ProofVerdict.NotComparable -> CoachingStatus.Same
}

/** What came of one measured focus corner in words (UI-191 A). */
fun proofText(p: CornerProof): String {
    val back = p.wonBackSeconds ?: return "No pass through it this session"
    val cue = cueText(p.cues)
    return when (p.verdict) {
        ProofVerdict.Faster -> if (p.cues == 0) {
            "${proofSeconds(back)} back without a cue from the coach"
        } else {
            val left = p.leftSeconds ?: 0.0
            val target = if (left < HalfHundredth) {
                "on your target now"
            } else {
                "${proofSeconds(left)} still to your target"
            }
            listOfNotNull("${proofSeconds(back)} back", target, cue).joinToString(" · ")
        }
        ProofVerdict.Slower -> listOfNotNull("${proofSeconds(-back)} slower", cue).joinToString(" · ")
        else -> {
            val way = when {
                abs(back) < HalfHundredth -> "The same time"
                back > 0 -> "${proofSeconds(back)} faster"
                else -> "${proofSeconds(-back)} slower"
            }
            listOfNotNull("$way: within GPS timing", cue).joinToString(" · ")
        }
    }
}

private fun cueText(cues: Int?) = when (cues) {
    null -> null
    0 -> "no cue here"
    1 -> "the coach spoke here once"
    else -> "the coach spoke here $cues times"
}

private const val HalfHundredth = 0.005

private fun proofSeconds(value: Double) = "${fixed(value, 2)} s"

/** Under the measured corners: what was compared (UI-191 A). */
val ProofNote = "Fastest pass through each corner this session against the session before, and against the " +
    "target then. Under ${fixed(ImprovedSeconds, 2)} s is GPS timing."
