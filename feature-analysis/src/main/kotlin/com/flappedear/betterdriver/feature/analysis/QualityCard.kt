package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel

/**
 * Data quality (FBBD-183): how the session's recording did against the pass criteria, failures
 * first. "≈" marks a value worked out by a heuristic or an estimate rather than read from the data.
 */
@Composable
internal fun QualityCard(ui: QualityUi, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    Card(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackLabel("Data quality", Modifier.weight(1f))
            Dim("${ui.passed} pass · ${ui.failed} fail", color = if (ui.failed > 0) c.loss else c.textDim)
        }
        for (row in ui.rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, color = c.text, modifier = Modifier.weight(1f))
                Text(
                    (if (row.derived) "≈" else "") + row.value,
                    color = if (row.pass) c.text else c.loss,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    if (row.pass) "Pass" else "Fail",
                    color = if (row.pass) c.gain else c.loss,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(VerdictWidth),
                )
            }
        }
        if (ui.notMeasured.isNotEmpty()) Dim("Not measured: ${ui.notMeasured.joinToString(", ")}")
        Dim("≈ worked out, not measured directly. Debug logs hold the full report and how each value was found.")
    }
}

private val VerdictWidth = 44.dp

internal val PreviewQuality = QualityUi(
    passed = 5,
    failed = 1,
    rows = listOf(
        QualityRow("GNSS", "Longest gap", "412 ms", pass = false, derived = false),
        QualityRow("GNSS", "Sample rate", "24.9 Hz", pass = true, derived = false),
        QualityRow("GNSS", "Position noise parked", "0.21 m", pass = true, derived = false),
        QualityRow("Timing", "Suspect laps", "0", pass = true, derived = true),
        QualityRow("Video", "Video drift (phone)", "12.4 ms", pass = true, derived = true),
        QualityRow("Video", "Video coverage (phone)", "100 %", pass = true, derived = false),
    ),
    notMeasured = listOf("Longest reconnect", "Start sync error (phone)", "Battery temperature"),
)

@Preview(widthDp = 400, heightDp = 400)
@Composable
private fun QualityCardPreview() {
    BetterDriverTheme {
        Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            QualityCard(PreviewQuality)
        }
    }
}
