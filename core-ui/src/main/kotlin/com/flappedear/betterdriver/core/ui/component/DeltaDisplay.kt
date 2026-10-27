package com.flappedear.betterdriver.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.format.formatDelta
import com.flappedear.betterdriver.format.isDeltaGain

/**
 * Live delta against the best lap (UI-86 option B, approved: coloured number on black).
 *
 * [deltaSeconds] negative means ahead. The tile is black like the sector strip, the label
 * is dim and the number takes the gain or loss colour; a black tile also lights no pixels
 * on the OLED screen. Null shows a dim "—" (no reference lap yet). The number shrinks below [numberSize] when the tile
 * is too short for it, so it never overflows on a short window: what has to
 * fit is its line, which the number font makes taller than its size.
 */
@Composable
fun DeltaDisplay(
    deltaSeconds: Double?,
    modifier: Modifier = Modifier,
    numberSize: Dp = 150.dp,
) {
    val c = TrackTheme.colors
    val numberColor = when {
        deltaSeconds == null -> c.textDim
        isDeltaGain(deltaSeconds) -> c.gain
        else -> c.loss
    }
    val lineFactor = numberLineFactor()
    BoxWithConstraints(
        modifier = modifier
            .background(c.background, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The number's line is taller than its size; a dp to spare for rounding to whole pixels.
        val room = (maxHeight - LabelHeight - Spacing - 1.dp) / lineFactor
        val fitted = min(numberSize, room.coerceAtLeast(MinNumberSize))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing)) {
            TrackLabel("Delta vs best", color = c.textDim)
            NumberText(
                text = deltaSeconds?.let(::formatDelta) ?: "\u2014",
                size = fitted,
                color = numberColor,
            )
        }
    }
}

private val Spacing = 8.dp

/** Height reserved for the 11 dp caption line. */
private val LabelHeight = 16.dp

private val MinNumberSize = 24.dp
