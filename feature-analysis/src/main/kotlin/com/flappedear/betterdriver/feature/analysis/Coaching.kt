package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.flappedear.betterdriver.analysis.coaching.Persistence
import com.flappedear.betterdriver.analysis.coaching.SessionCoaching
import com.flappedear.betterdriver.analysis.coaching.attemptText
import com.flappedear.betterdriver.analysis.coaching.checkText
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import java.time.ZoneId

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
)

/** The coaching history as the summary shows it: what came of the focus worked on, then the gains checked. */
fun coachingRows(coaching: SessionCoaching, zone: ZoneId): List<CoachingRow> {
    val attempts = coaching.attempts.map { a ->
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
 * What came of the last focus (FBBD-180), beside the next one: for each corner the session worked
 * on, whether the driving changed, the corner improved and on how many passes; then whether gains
 * from earlier sessions held. Nothing when the history has nothing for this session. Where the
 * screen can show every pass through a corner ([LocalCornerLaps]), tapping a line opens its passes,
 * traces and video (FBBD-93).
 */
@Composable
internal fun CoachingHistory(rows: List<CoachingRow>, modifier: Modifier) {
    if (rows.isEmpty()) return
    val attempts = rows.filter { it.attempt }
    val checks = rows.filter { !it.attempt }
    Card(modifier.testTag(CoachingHistoryTag)) {
        if (attempts.isNotEmpty()) {
            TrackLabel("Last focus · what came of it")
            attempts.forEach { Line(it) }
        }
        if (checks.isNotEmpty()) {
            if (attempts.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TrackLabel("Earlier gains · did they hold")
            checks.forEach { Line(it) }
        }
    }
}

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
                CoachingStatus.Learned, CoachingStatus.Improved -> c.gain
                CoachingStatus.Lost -> c.loss
                else -> c.textDim
            }
            Text(row.status.label, color = color, style = MaterialTheme.typography.labelLarge)
            if (everyPass != null) Dim(" ›")
        }
        if (row.doubtful) Dim(row.text) else Text(row.text, color = c.text, style = MaterialTheme.typography.bodyMedium)
    }
}
