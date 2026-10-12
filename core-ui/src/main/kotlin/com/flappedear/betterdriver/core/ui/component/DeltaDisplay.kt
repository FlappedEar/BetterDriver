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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.format.formatDelta
import com.flappedear.betterdriver.core.ui.format.isDeltaGain

/**
 * Live delta against the best lap (UI-03 option B, approved: coloured tile).
 *
 * [deltaSeconds] negative means ahead. The whole tile tints with the gain or
 * loss colour and the number stays white. Null shows "—" on a plain tile
 * (no reference lap yet). The number shrinks below [numberSize] when the tile
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
    val tint = when {
        deltaSeconds == null -> c.surface
        isDeltaGain(deltaSeconds) -> lerp(c.surface, c.gain, TintFraction)
        else -> lerp(c.surface, c.loss, TintFraction)
    }
    val lineFactor = numberLineFactor()
    BoxWithConstraints(
        modifier = modifier
            .background(tint, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The number's line is taller than its size; a dp to spare for rounding to whole pixels.
        val room = (maxHeight - LabelHeight - Spacing - 1.dp) / lineFactor
        val fitted = min(numberSize, room.coerceAtLeast(MinNumberSize))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing)) {
            TrackLabel("Delta vs best", color = if (deltaSeconds == null) c.textDim else c.text)
            NumberText(
                text = deltaSeconds?.let(::formatDelta) ?: "\u2014",
                size = fitted,
                color = if (deltaSeconds == null) c.textDim else c.text,
            )
        }
    }
}

/** Share of the gain or loss colour mixed into the tile, as in the approved mockup. */
internal const val TintFraction = 0.3f

private val Spacing = 8.dp

/** Height reserved for the 11 dp caption line. */
private val LabelHeight = 16.dp

private val MinNumberSize = 24.dp
