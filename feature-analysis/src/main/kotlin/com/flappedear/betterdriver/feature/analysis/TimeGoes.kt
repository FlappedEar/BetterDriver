package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import com.flappedear.betterdriver.format.formatDelta

/**
 * Where time goes (FBBD-103, UI-67): the corners and straights where the fastest lap is slowest
 * against the best time through them, with the lap that set it, then the other laps' largest
 * single losses against the fastest. Tapping a loss compares that lap. Each corner listed can be
 * picked for the spoken coach's next session, on the first row it appears in (FBBD-148). Each time
 * carries its confidence under it (FBBD-177): below High it is said without its number, and why
 * is shown.
 */
@Composable
internal fun TimeGoes(ui: AnalysisUi, onCompare: (Int) -> Unit, onCoachPick: (String) -> Unit, modifier: Modifier) {
    val c = TrackTheme.colors
    val firstRows = firstCornerRows(ui)
    Card(modifier) {
        TrackLabel("Where time goes")
        if (ui.gains.isNotEmpty()) {
            Dim(timeGoesHeading(ui))
            ui.gains.forEachIndexed { i, gain ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(gain.name, Modifier.weight(1f))
                        Dim(setOnText(gain), Modifier.width(80.dp))
                        Loss(gain.lossText(::formatDelta), gain.confidence)
                        val flag = gain.cornerId.takeIf { i in firstRows.gains }
                        CoachFlag(ui, flag, gain.name, onCoachPick, firstRows.any)
                    }
                    Why(gain.confidence)
                }
            }
        }
        if (ui.losses.isNotEmpty()) {
            Dim("Largest losses on other laps · tap to compare")
            ui.losses.forEachIndexed { i, loss ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCompare(loss.lapIndex) }
                        .padding(vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dim("Lap ${loss.lapNumber}", Modifier.width(56.dp))
                        Text(loss.name, Modifier.weight(1f), color = c.text)
                        Loss(loss.lossText(::formatDelta), loss.confidence)
                        val flag = loss.cornerId.takeIf { i in firstRows.losses }
                        CoachFlag(ui, flag, loss.name, onCoachPick, firstRows.any)
                    }
                    Why(loss.confidence)
                }
            }
        }
        if (firstRows.any) Dim(CoachFlagNote)
    }
}

/** A time lost as [text]: a number at High confidence, in the loss colour; a word below it, dimmed. */
@Composable
private fun Loss(text: String, confidence: ConfidenceUi) {
    val c = TrackTheme.colors
    Text(
        text,
        color = if (confidence.doubtful) c.textDim else c.loss,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.width(64.dp),
    )
}

/** The row's confidence, under it (FBBD-177): below High, why too. */
@Composable
private fun Why(confidence: ConfidenceUi) {
    ConfidenceNote(confidence)
}

/**
 * Over the gains: the best actual lap (FBBD-178's name) against the best of each corner, each row
 * then naming the lap that set that best, as the raw theoretical best by corner does.
 */
internal fun timeGoesHeading(ui: AnalysisUi): String =
    "$BestActualLapLabel ${ui.bestLapNumber} against the best of each corner"

/**
 * The lap that set the best through [gain]'s corner: "lap 5", as under each time of the raw
 * theoretical best by corner. Never "best lap 5", which reads as the best actual lap.
 */
internal fun setOnText(gain: GainRow): String = gain.bestLapNumber?.let { "lap $it" }.orEmpty()
