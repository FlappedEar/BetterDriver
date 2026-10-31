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
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.NumberText
import com.flappedear.betterdriver.core.ui.component.TrackLabel

/** The coaching history card, for tests. */
const val CoachingHistoryTag = "coaching-history"

/**
 * What came of the last focus (FBBD-180), beside the next one: for each corner the session worked
 * on, whether the driving changed, the corner improved and on how many passes; then whether gains
 * from earlier sessions held. Nothing when the history has nothing for this session. Where the
 * screen can show every pass through a corner ([LocalCornerLaps]), tapping a line opens its passes,
 * traces and video (FBBD-93).
 */
@Composable
internal fun CoachingHistory(rows: List<CoachingRow>, wonBack: Double?, modifier: Modifier) {
    // The card as the shared core lays it out, the same on iOS (FBBD-364).
    val card = coachingCard(rows, wonBack)
    if (card.empty) return
    Card(modifier.testTag(CoachingHistoryTag)) {
        if (card.attempts.isNotEmpty()) {
            TrackLabel(LastFocusLabel)
            card.wonBack?.let { WonBack(it, card.wonBackText.orEmpty(), card.wonBackLost) }
            card.attempts.forEach { Line(it) }
            card.note?.let { Dim(it) }
        }
        if (card.checks.isNotEmpty()) {
            if (card.attempts.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TrackLabel(EarlierGainsLabel)
            card.checks.forEach { Line(it) }
        }
    }
}

/** The time won back over the last focus, on top (UI-191 A). */
@Composable
private fun WonBack(figure: String, text: String, lost: Boolean) {
    val c = TrackTheme.colors
    Row(Modifier.testTag(WonBackTag), verticalAlignment = Alignment.Bottom) {
        NumberText(figure, size = 24.dp, color = if (lost) c.loss else c.gain)
        Spacer(Modifier.width(10.dp))
        Dim(text)
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
            val color = when {
                row.status.gain -> c.gain
                row.status.loss -> c.loss
                else -> c.textDim
            }
            Text(row.status.label, color = color, style = MaterialTheme.typography.labelLarge)
            if (everyPass != null) Dim(" ›")
        }
        if (row.doubtful) Dim(row.text) else Text(row.text, color = c.text, style = MaterialTheme.typography.bodyMedium)
    }
}
