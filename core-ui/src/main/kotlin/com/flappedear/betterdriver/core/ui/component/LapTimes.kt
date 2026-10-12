package com.flappedear.betterdriver.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import com.flappedear.betterdriver.core.ui.numberStyle

/** A lap time not known yet, as wide as a real one (a placeholder per digit). */
private val NoTime = "$DigitPlaceholder:$DigitPlaceholder$DigitPlaceholder.$DigitPlaceholder$DigitPlaceholder"

/**
 * Lap times block (UI-04 option A): predicted lap large, then last and best.
 * Best is always in the best-lap colour. Null values show dashes.
 *
 * In a short window (the system bars shown by a swipe) the predicted time gives way to fit;
 * last and best keep their size, so they stay readable at a glance (FBBD-65) and never get
 * squeezed out of the block.
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
    val small = heroSize * SmallFraction
    val heroStyle = numberStyle(heroSize, FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // How tall a row of numbers is for its font size, from the number font itself.
    val lineFactor = remember(heroStyle, density) {
        with(density) { measurer.measure("0", heroStyle).size.height.toDp() } / heroSize
    }
    BoxWithConstraints(
        modifier = modifier
            .background(c.surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        val hero = if (constraints.hasBoundedHeight) {
            min(heroSize, (maxHeight - RowGap * 2) / lineFactor - small * 2).coerceAtLeast(small)
        } else {
            heroSize
        }
        Column(verticalArrangement = Arrangement.spacedBy(RowGap, Alignment.CenterVertically)) {
            LapRow("Predicted", predictedMillis, hero, FontWeight.Bold, c.text)
            LapRow("Last", lastMillis, small, FontWeight.SemiBold, c.text)
            LapRow("Best", bestMillis, small, FontWeight.SemiBold, c.best)
        }
    }
}

/** Last and best, against the predicted time's size. */
private const val SmallFraction = 0.55f

private val RowGap = 4.dp

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
