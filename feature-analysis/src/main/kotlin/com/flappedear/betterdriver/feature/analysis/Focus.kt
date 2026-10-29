package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.NumberText
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.core.ui.layout.LayoutSize

/** The next-session card, for tests. */
const val NextSessionTag = "next-session"

/** Its primary focus, for tests. */
const val PrimaryFocusTag = "next-session-primary"

/** The link to Where to find time on the card, for tests. */
const val WhereToFindTimeTag = "next-session-line"

/** Said under the focus only where the spoken coach will work on it first ([AnalysisUi.coachFocusFirst]). */
const val CoachFocusFirstText = "The spoken coach works on these first next session at this track."

/**
 * Next session (FBBD-20): the answer to "what should I work on next?", first on the summary. One
 * primary corner, large, with the time a typical pass leaves there and how the faster passes
 * differed; at most two secondary corners, small, beside it on a wide screen and under it on a
 * phone. Where the spoken coach will work on them first next session, it says so. Each shows its confidence
 * below High (FBBD-177), and the corners left out for weak evidence are named on one line. Tapping a
 * focus compares its lap with the fastest; where the screen can show every pass through a corner
 * ([LocalCornerLaps]), a corner's "Every pass" opens its passes, traces and video (FBBD-93). Once
 * the line analysis is ready, a link at the bottom opens Where to find time in the Map tab
 * ([onWhereToFindTime], UI-190 B): the card keeps its own answer, the link the line analysis's.
 */
@Composable
internal fun Focus(
    ui: AnalysisUi,
    onCompare: (Int) -> Unit,
    layout: LayoutSize,
    modifier: Modifier,
    onWhereToFindTime: () -> Unit = {},
) {
    val primary = ui.focus.firstOrNull { it.primary } ?: ui.focus.firstOrNull()
    val secondary = ui.focus.filter { it !== primary }
    Card(modifier.testTag(NextSessionTag)) {
        TrackLabel("Next session · work on")
        if (primary == null) {
            Dim("Nothing clear enough to work on from this session's laps.")
        } else if (layout.sideBySide && secondary.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Primary(primary, onCompare, Modifier.weight(PrimaryWeight))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TrackLabel("Also")
                    secondary.forEach { Secondary(it, onCompare) }
                }
            }
        } else {
            Primary(primary, onCompare, Modifier.fillMaxWidth())
            if (secondary.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TrackLabel("Also")
                secondary.forEach { Secondary(it, onCompare) }
            }
        }
        if (ui.withheld.isNotEmpty()) {
            Dim("Left out, too little evidence: ${ui.withheld.joinToString { it.name }}")
        }
        if (primary != null && ui.coachFocusFirst) Dim(CoachFocusFirstText)
        (ui.line as? LineState.Ready)?.let { WhereToFindTimeLink(it.ui.totalGainSeconds, onWhereToFindTime) }
    }
}

/** "Where to find time ≈ 0.41 s ›": the line analysis's total, one tap from the Map tab view (UI-190 B). */
@Composable
private fun WhereToFindTimeLink(totalSeconds: Double, onClick: () -> Unit) {
    Text(
        whereToFindTimeLink(totalSeconds),
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(WhereToFindTimeTag)
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.primary,
    )
}

private const val PrimaryWeight = 1.4f

/** The primary focus: its corner and time large, what the faster passes did, the evidence under it. */
@Composable
private fun Primary(row: FocusRow, onCompare: (Int) -> Unit, modifier: Modifier) {
    val c = TrackTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = row.compareIndex != null) { row.compareIndex?.let(onCompare) }
            .testTag(PrimaryFocusTag)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                row.name,
                Modifier.weight(1f),
                color = c.text,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            row.timeText(::focusSeconds)?.let { time ->
                Column(horizontalAlignment = Alignment.End) {
                    NumberText(time, size = 24.dp, color = if (row.timeDoubtful) c.textDim else c.gain)
                    Dim("a lap")
                }
            }
        }
        Text(row.what.ifEmpty { row.observation }, color = c.text, style = MaterialTheme.typography.bodyLarge)
        if (row.what.isNotEmpty()) Dim(row.observation)
        if (row.confidence.doubtful) ConfidenceNote(row.confidence)
        if (row.compareNumber != null || everyPass(row)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dim(row.compareNumber?.let { "Tap to compare lap $it" }.orEmpty(), Modifier.weight(1f), color = c.cold)
                EveryPass(row)
            }
        }
    }
}

/** Whether [row] offers every pass through its corner: a corner, where the screen can show them. */
@Composable
private fun everyPass(row: FocusRow): Boolean = LocalCornerLaps.current != null && !row.cornerId.isNullOrEmpty()

/** "Every pass ›" on a focus corner: its passes, their traces and video (FBBD-93). Nothing for a straight. */
@Composable
private fun EveryPass(row: FocusRow) {
    val open = LocalCornerLaps.current ?: return
    val id = row.cornerId?.takeIf { it.isNotEmpty() } ?: return
    TextButton(onClick = { open(id) }, modifier = Modifier.testTag(EveryPassTag)) { Text(EveryPassText) }
}

/**
 * The way from a focus, coaching or Corners-tab corner to every pass through it (FBBD-93): one
 * name wherever it is offered, and the title of the screen it opens ([EveryPassTitle]).
 */
const val EveryPassText = "Every pass ›"

/** The title of the screen [EveryPassText] opens. */
const val EveryPassTitle = "Every pass"

/** Test tag of [EveryPassText] on a focus. */
const val EveryPassTag = "every-pass"

/** A secondary focus: one line with its time, and what the faster passes did. */
@Composable
private fun Secondary(row: FocusRow, onCompare: (Int) -> Unit) {
    val c = TrackTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = row.compareIndex != null) { row.compareIndex?.let(onCompare) }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.name, Modifier.weight(1f), color = c.text, fontWeight = FontWeight.SemiBold)
            row.timeText(::focusSeconds)?.let { Text(it, color = if (row.timeDoubtful) c.textDim else c.gain) }
        }
        if (everyPass(row)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                EveryPass(row)
            }
        }
        Dim(row.what.ifEmpty { row.observation })
        if (row.confidence.doubtful) ConfidenceNote(row.confidence)
    }
}
