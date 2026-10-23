package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.coaching.FocusVerdict
import com.flappedear.betterdriver.analysis.coaching.ImprovedSeconds
import com.flappedear.betterdriver.analysis.coaching.Persistence
import com.flappedear.betterdriver.analysis.coaching.SessionCoaching
import com.flappedear.betterdriver.analysis.coaching.attemptText
import com.flappedear.betterdriver.analysis.coaching.checkText
import com.flappedear.betterdriver.analysis.line.CornerProof
import com.flappedear.betterdriver.analysis.line.LineProof
import com.flappedear.betterdriver.analysis.line.ProofVerdict
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.NumberText
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** The coaching history card, for tests. */
const val CoachingHistoryTag = "coaching-history"

/** Where a recommendation stands in the coaching loop (FBBD-180). */
enum class CoachingStatus(val label: String) {
    /** The improvement held in a later session. */
    Learned("Learned"),

    /** It improved the corner; a later session tells whether it held. */
    Improved("Improved"),

    /** Not enough to tell yet. */
    Pending("Pending"),

    /** Driven enough, no gain yet. */
    NoGain("No gain yet"),

    /** Some of the improvement held. */
    Partly("Partly held"),

    /** The improvement did not hold. */
    Lost("Lost"),

    /** Measured through the line plans (FBBD-310): its fastest pass got faster, with the coach speaking there. */
    Faster("Faster"),

    /** Faster, and the coach said nothing there: not the coach's doing. */
    FasterNoCue("Faster, no cue"),

    /** Within GPS timing of the session before. */
    Same("Same"),

    /** Slower than the session before. */
    Slower("Slower"),

    /** No pass through it this session. */
    NotDriven("Not driven"),
}

/**
 * One line of the coaching history: [name] the corner, [text] what came of the recommendation in
 * words (numbers only where precise), [status] where it stands. [doubtful] when its corner time
 * was not precise (shown dim, as the focus shows a time below High). [cornerId] is its CornerId.
 */
data class CoachingRow(
    val name: String,
    val status: CoachingStatus,
    val text: String,
    val doubtful: Boolean,
    val cornerId: String,
    /** True for the focus this session worked on; false for an earlier gain checked in it. */
    val attempt: Boolean = true,
    /** Measured through the line plans, fastest pass against fastest pass (FBBD-310). */
    val measured: Boolean = false,
)

/**
 * The coaching history as the summary shows it: what came of the focus worked on, then the gains
 * checked. A focus corner [proof] measured (FBBD-310, UI-191 A) says how much came back, how much is
 * left to the target and whether the coach spoke there; the others say what the history found.
 */
fun coachingRows(coaching: SessionCoaching, zone: ZoneId, proof: LineProof? = null): List<CoachingRow> {
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
 * The time won back over the focus corners [proof] measured that [coaching] worked on (UI-191 A),
 * slower ones counting against it; null when it measured none of them.
 */
fun lastFocusWonBack(coaching: SessionCoaching, proof: LineProof?): Double? {
    val measured = coaching.attempts.mapNotNull { measuredCorner(proof, it.ask.cornerId) }
    return if (measured.isEmpty()) null else measured.mapNotNull { it.wonBackSeconds }.sum()
}

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

private fun proofSeconds(value: Double) = String.format(Locale.ROOT, "%.2f s", value)

/** Under the measured corners: what was compared (UI-191 A). */
val ProofNote = "Fastest pass through each corner this session against the session before, and against the " +
    "target then. Under ${String.format(Locale.ROOT, "%.2f", ImprovedSeconds)} s is GPS timing."

/**
 * What came of the last focus (FBBD-180), beside the next one: for each corner the session worked
 * on, whether the driving changed, the corner improved and on how many passes; then whether gains
 * from earlier sessions held. Nothing when the history has nothing for this session. Where the
 * screen can show every pass through a corner ([LocalCornerLaps]), tapping a line opens its passes,
 * traces and video (FBBD-93).
 */
@Composable
internal fun CoachingHistory(rows: List<CoachingRow>, wonBack: Double?, modifier: Modifier) {
    if (rows.isEmpty()) return
    val attempts = rows.filter { it.attempt }
    val checks = rows.filter { !it.attempt }
    Card(modifier.testTag(CoachingHistoryTag)) {
        if (attempts.isNotEmpty()) {
            TrackLabel("Last focus · what came of it")
            wonBack?.let { WonBack(it) }
            attempts.forEach { Line(it) }
            if (attempts.any { it.measured }) Dim(ProofNote)
        }
        if (checks.isNotEmpty()) {
            if (attempts.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TrackLabel("Earlier gains · did they hold")
            checks.forEach { Line(it) }
        }
    }
}

/** The time won back over the last focus, on top (UI-191 A). */
@Composable
private fun WonBack(seconds: Double) {
    val c = TrackTheme.colors
    Row(Modifier.testTag(WonBackTag), verticalAlignment = Alignment.Bottom) {
        NumberText(
            String.format(Locale.ROOT, "%.2f s", abs(seconds)),
            size = 24.dp,
            color = if (seconds < 0) c.loss else c.gain,
        )
        Spacer(Modifier.width(10.dp))
        Dim(if (seconds < 0) "lost on the corners you worked on" else "won back on the corners you worked on")
    }
}

/** The time won back over the last focus, for tests. */
const val WonBackTag = "last-focus-won-back"

@Composable
private fun Line(row: CoachingRow) {
    val c = TrackTheme.colors
    val everyPass = LocalCornerLaps.current?.takeIf { row.cornerId.isNotEmpty() }
    val tap = if (everyPass == null) {
        Modifier
    } else {
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { everyPass(row.cornerId) }
    }
    Column(Modifier.fillMaxWidth().then(tap), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.name, Modifier.weight(1f), color = c.text, fontWeight = FontWeight.SemiBold)
            val color = when (row.status) {
                CoachingStatus.Learned, CoachingStatus.Improved, CoachingStatus.Faster, CoachingStatus.FasterNoCue ->
                    c.gain
                CoachingStatus.Lost, CoachingStatus.Slower -> c.loss
                else -> c.textDim
            }
            Text(row.status.label, color = color, style = MaterialTheme.typography.labelLarge)
            if (everyPass != null) Dim(" ›")
        }
        if (row.doubtful) Dim(row.text) else Text(row.text, color = c.text, style = MaterialTheme.typography.bodyMedium)
    }
}
