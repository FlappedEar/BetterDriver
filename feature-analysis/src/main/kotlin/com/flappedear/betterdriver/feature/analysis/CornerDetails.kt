package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel

/**
 * Corner details (FBBD-104, UI-68): per corner, where the best lap and the compared lap brake, how
 * hard, their minimum and exit speeds and where the throttle comes back, with the spread over the
 * counted laps (interquartile range). Inferred values (no pedal channel) are marked ≈.
 */
@Composable
internal fun CornerDetails(ui: AnalysisUi, modifier: Modifier) {
    val c = TrackTheme.colors
    // With a single counted lap there is no compared lap, and no column for it.
    val other = ui.compare?.otherNumber
    for (corner in ui.corners) {
        Card(modifier) {
            TrackLabel(corner.name)
            Row {
                Dim("", Modifier.weight(1f))
                Dim("Lap ${ui.bestLapNumber}", Modifier.width(ValueWidth))
                if (other != null) Dim("Lap $other", Modifier.width(ValueWidth))
                Dim("Spread", Modifier.width(ValueWidth))
            }
            for (row in corner.rows) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, Modifier.weight(1f), color = c.text)
                    val mark = if (row.inferred && row.best != null) "≈" else ""
                    Text(
                        mark + formatMeasure(row.best, row.measure, corner.speedUnit),
                        Modifier.width(ValueWidth),
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (other != null) {
                        Text(formatMeasure(row.lap, row.measure, corner.speedUnit), Modifier.width(ValueWidth))
                    }
                    Dim(formatMeasure(row.spread, row.measure, corner.speedUnit), Modifier.width(ValueWidth))
                }
            }
            Dim("Brakes: metres before the corner. Throttle: metres after its start.")
            corner.lineSpreadMeters?.let {
                val line = formatMeasure(it, CornerMeasure.Meters, "")
                Dim(if (corner.lineWithinGps) "Line spread $line, within GPS accuracy" else "Line spread $line")
            }
        }
    }
}

private val ValueWidth = 68.dp
