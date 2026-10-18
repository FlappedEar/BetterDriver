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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.format.formatLapTime

/** A lap time not known yet, as wide as a real one (a placeholder per digit). */
private val NoTime = "$DigitPlaceholder:$DigitPlaceholder$DigitPlaceholder.$DigitPlaceholder$DigitPlaceholder"

/**
 * Lap times block (UI-82): the lap number and the running time of the current lap large, then
 * last and best. Arek asked for the number on its own, without a "Lap" label, and for the current
 * lap time in place of the predicted one. Best is always in the best-lap colour. Null values show
 * dashes; before the first fix the number is left empty. On a circuit the out lap is lap 1,
 * timed from the first fix, as the lap list counts it.
 *
 * In a short window (the system bars shown by a swipe) the top row gives way to fit;
 * last and best keep their size, so they stay readable at a glance (FBBD-65) and never get
 * squeezed out of the block.
 *
 * The rows have no padding or gap above and below: a line of the number font is about 1.6 times
 * its size, and the room above and below the digits already sets them apart (FBBD-40).
 */
@Composable
fun LapTimes(
    lapNumber: Int?,
    lapMillis: Long?,
    lastMillis: Long?,
    bestMillis: Long?,
    modifier: Modifier = Modifier,
    heroSize: Dp = 56.dp,
) {
    val c = TrackTheme.colors
    val small = heroSize * SmallFraction
    val lineFactor = numberLineFactor()
    BoxWithConstraints(
        modifier = modifier
            .background(c.surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val hero = if (constraints.hasBoundedHeight) {
            // Some room to spare, for the rounding of each row to whole pixels.
            min(heroSize, (maxHeight - RoundingSpare) / lineFactor - small * 2).coerceAtLeast(small)
        } else {
            heroSize
        }
        Column(verticalArrangement = Arrangement.Center) {
            CurrentLapRow(lapNumber, lapMillis, hero)
            LapRow("Last", lastMillis, small, FontWeight.SemiBold, c.text)
            LapRow("Best", bestMillis, small, FontWeight.SemiBold, c.best)
        }
    }
}

/** Last and best, against the current lap time's size. */
private const val SmallFraction = 0.55f

/** The lap number, against the current lap time's size: large, but plainly not a time. */
private const val NumberFraction = 0.8f

@Composable
private fun CurrentLapRow(lapNumber: Int?, lapMillis: Long?, size: Dp) {
    val c = TrackTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberText(
            text = lapNumber?.toString() ?: "",
            size = size * NumberFraction,
            color = c.text,
            weight = FontWeight.Bold,
        )
        NumberText(
            text = lapMillis?.let(::formatLapTime) ?: NoTime,
            size = size,
            color = if (lapMillis == null) c.textDim else c.text,
            weight = FontWeight.Bold,
        )
    }
}

private val RoundingSpare = 2.dp

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
