package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.NumberText
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import java.util.Locale

/** The next-session card, for tests. */
const val NextSessionTag = "next-session"

/** Its primary focus, for tests. */
const val PrimaryFocusTag = "next-session-primary"

/**
 * Next session (FBBD-20): the answer to "what should I work on next?", first on the summary. One
 * primary corner, large, with the time a typical pass leaves there and how the faster passes
 * differed; at most two secondary corners, small, beside it on a wide screen and under it on a
 * phone. The spoken coach works on them first next session at the track. Each shows its confidence
 * below High (FBBD-177), and the corners left out for weak evidence are named on one line. Tapping a
 * focus compares its lap with the fastest.
 */
@Composable
internal fun Focus(ui: AnalysisUi, onCompare: (Int) -> Unit, layout: LayoutSize, modifier: Modifier) {
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
        if (primary != null) Dim("The spoken coach works on these first next session at this track.")
    }
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
                    NumberText(time, size = 24.dp, color = if (row.confidence.doubtful) c.textDim else c.gain)
                    Dim("a lap")
                }
            }
        }
        Text(row.what.ifEmpty { row.observation }, color = c.text, style = MaterialTheme.typography.bodyLarge)
        if (row.what.isNotEmpty()) Dim(row.observation)
        if (row.confidence.doubtful) ConfidenceNote(row.confidence)
        row.compareNumber?.let { Dim("Tap to compare lap $it", color = c.cold) }
    }
}

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
            row.timeText(::focusSeconds)?.let { Text(it, color = if (row.confidence.doubtful) c.textDim else c.gain) }
        }
        Dim(row.what.ifEmpty { row.observation })
        if (row.confidence.doubtful) ConfidenceNote(row.confidence)
    }
}

/** "0.18 s". */
private fun focusSeconds(value: Double) = String.format(Locale.ROOT, "%.2f s", value)
