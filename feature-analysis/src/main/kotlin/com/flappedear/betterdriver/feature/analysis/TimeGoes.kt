package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.core.ui.format.formatDelta

/**
 * Where time goes (FBBD-103, UI-67): the corners and straights where the fastest lap is slowest
 * against the best time through them, with the lap that set it, then the other laps' largest
 * single losses against the fastest. Tapping a loss compares that lap. Each corner listed can be
 * picked for the spoken coach's next session, on the first row it appears in (FBBD-148).
 */
@Composable
internal fun TimeGoes(ui: AnalysisUi, onCompare: (Int) -> Unit, onCoachPick: (String) -> Unit, modifier: Modifier) {
    val c = TrackTheme.colors
    val firstRows = firstCornerRows(ui)
    Card(modifier) {
        TrackLabel("Where time goes")
        if (ui.gains.isNotEmpty()) {
            Dim("Best lap ${ui.bestLapNumber} against the best of each corner")
            ui.gains.forEachIndexed { i, gain ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(gain.name, Modifier.weight(1f))
                    Dim(gain.bestLapNumber?.let { "best lap $it" } ?: "", Modifier.width(80.dp))
                    Loss(gain.lossSeconds)
                    CoachFlag(ui, gain.cornerId.takeIf { i in firstRows.gains }, gain.name, onCoachPick, firstRows.any)
                }
            }
        }
        if (ui.losses.isNotEmpty()) {
            Dim("Largest losses on other laps · tap to compare")
            ui.losses.forEachIndexed { i, loss ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCompare(loss.lapIndex) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dim("Lap ${loss.lapNumber}", Modifier.width(56.dp))
                    Text(loss.name, Modifier.weight(1f), color = c.text)
                    Loss(loss.lossSeconds)
                    CoachFlag(ui, loss.cornerId.takeIf { i in firstRows.losses }, loss.name, onCoachPick, firstRows.any)
                }
            }
        }
        if (firstRows.any) Dim(CoachFlagNote)
    }
}

@Composable
private fun Loss(seconds: Double) {
    Text(
        formatDelta(seconds),
        color = TrackTheme.colors.loss,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.width(64.dp),
    )
}
