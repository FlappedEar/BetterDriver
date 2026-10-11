package com.flappedear.betterdriver.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import com.flappedear.betterdriver.core.ui.TrackTheme

private const val NoTime = "—:——.——"

/**
 * Lap times block (UI-04 option A): predicted lap large, then last and best.
 * Best is always in the best-lap colour. Null values show dashes.
 */
@Composable
fun LapTimes(
    predictedMillis: Long?,
    lastMillis: Long?,
    bestMillis: Long?,
    modifier: Modifier = Modifier,
    heroSize: Dp = 56.dp,
) {
    val c = TrackTheme.colors
    Column(
        modifier = modifier
            .background(c.surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        LapRow("Predicted", predictedMillis, heroSize, FontWeight.Bold, c.text)
        LapRow("Last", lastMillis, heroSize * 0.55f, FontWeight.SemiBold, c.text)
        LapRow("Best", bestMillis, heroSize * 0.55f, FontWeight.SemiBold, c.best)
    }
}

@Composable
private fun LapRow(label: String, millis: Long?, size: Dp, weight: FontWeight, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackLabel(label)
        NumberText(
            text = millis?.let(::formatLapTime) ?: NoTime,
            size = size,
            color = if (millis == null) TrackTheme.colors.textDim else color,
            weight = weight,
        )
    }
}
