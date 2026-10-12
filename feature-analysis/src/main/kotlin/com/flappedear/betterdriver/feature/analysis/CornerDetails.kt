package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel

/**
 * Corner details (FBBD-104, UI-68): per corner, where the best lap and the compared lap brake, how
 * hard, their minimum and exit speeds and where the throttle comes back, with the spread over the
 * counted laps (interquartile range). Every value that is not measured (inferred with no pedal
 * channel, or read from a calculated channel) is marked ≈, and the card says what ≈ means
 * (FBBD-174). The braking point and the minimum speed carry their confidence (FBBD-177); a value
 * of Low confidence is not shown. Where the screen can show every lap through a corner
 * ([LocalCornerLaps], FBBD-153), tapping a corner opens it.
 */
@Composable
internal fun CornerDetails(ui: AnalysisUi, modifier: Modifier) {
    val c = TrackTheme.colors
    // With a single counted lap there is no compared lap, and no column for it.
    val other = ui.compare?.otherNumber
    val everyLap = LocalCornerLaps.current
    for (corner in ui.corners) {
        val open: (() -> Unit)? = if (everyLap != null && corner.id.isNotEmpty()) {
            { everyLap(corner.id) }
        } else {
            null
        }
        val tap = if (open == null) Modifier else Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = open)
        Card(modifier.then(tap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrackLabel(corner.name, Modifier.weight(1f))
                if (open != null) TextButton(onClick = open) { Text("Every lap ›") }
            }
            Row {
                Dim("", Modifier.weight(1f))
                Dim("Lap ${ui.bestLapNumber}", Modifier.width(ValueWidth))
                if (other != null) Dim("Lap $other", Modifier.width(ValueWidth))
                Dim("Spread", Modifier.width(ValueWidth))
            }
            for (row in corner.rows) {
                fun value(v: Double?) = marked(formatMeasure(v, row.measure, corner.speedUnit), row.provenance)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, Modifier.weight(1f), color = c.text)
                    // A Low value is not shown (FBBD-177).
                    Text(
                        valueText(value(row.best), row.confidence),
                        Modifier.width(ValueWidth),
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (other != null) Text(valueText(value(row.lap), row.lapConfidence), Modifier.width(ValueWidth))
                    Dim(value(row.spread), Modifier.width(ValueWidth))
                }
            }
            RowConfidence(corner)
            Dim("Brakes: metres before the corner. Throttle: metres after its start.")
            if (corner.rows.any { it.marked }) ProvenanceLegend()
            corner.lineSpreadMeters?.let {
                val line = formatMeasure(it, CornerMeasure.Meters, "")
                Dim(if (corner.lineWithinGps) "Line spread $line, within GPS accuracy" else "Line spread $line")
            }
        }
    }
}

/** The confidence of each graded row of [corner] (FBBD-177), as "Brakes: Medium confidence · …". */
@Composable
private fun RowConfidence(corner: CornerDetail) {
    for (row in corner.rows) row.shownConfidence?.let { ConfidenceNote(it, about = row.label) }
}

private val ValueWidth = 68.dp
