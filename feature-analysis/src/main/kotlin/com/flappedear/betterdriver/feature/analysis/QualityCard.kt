package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
 * Data quality (FBBD-183): how many of the session's checks passed, failed or could not be measured,
 * with each failure, its value and its FBBD keys. "≈" marks a value worked out by a heuristic or an
 * estimate rather than read from the data.
 */
@Composable
internal fun QualityCard(ui: QualityUi, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    Card(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackLabel("Data quality", Modifier.weight(1f))
            Dim(
                "${ui.passed} pass · ${ui.failed} fail · ${ui.notMeasured} not measured",
                color = if (ui.failed > 0) c.loss else c.textDim,
            )
        }
        for (row in ui.failures) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, color = c.text, modifier = Modifier.weight(1f))
                Text(
                    (if (row.derived) "≈" else "") + row.value,
                    color = c.loss,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }
            if (row.keys.isNotEmpty()) Dim(row.keys)
        }
        Dim("Every check, with how its value was found, is in the shared log (Settings › Diagnostics).")
    }
}

internal val PreviewQuality = QualityUi(
    passed = 23,
    failed = 2,
    notMeasured = 6,
    failures = listOf(
        QualityRow("GNSS", "Dropped samples", "4.1 %", "FBBD-8, FBBD-46", derived = false),
        QualityRow("Video", "Drift at 30 min (phone)", "112 ms", "FBBD-21, FBBD-82", derived = true),
    ),
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
