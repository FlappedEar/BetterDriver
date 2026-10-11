package com.flappedear.betterdriver.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.format.formatDelta

/**
 * Live delta against the best lap (UI-03 option B, approved: coloured tile).
 *
 * [deltaSeconds] negative means ahead. The whole tile tints with the gain or
 * loss colour and the number stays white. Null shows "—" on a plain tile
 * (no reference lap yet).
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
        deltaSeconds < 0 -> lerp(c.surface, c.gain, TintFraction)
        else -> lerp(c.surface, c.loss, TintFraction)
    }
    Column(
        modifier = modifier
            .background(tint, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        TrackLabel("Delta vs best", color = if (deltaSeconds == null) c.textDim else c.text)
        NumberText(
            text = deltaSeconds?.let(::formatDelta) ?: "\u2014",
            size = numberSize,
            color = if (deltaSeconds == null) c.textDim else c.text,
        )
    }
}

/** Share of the gain or loss colour mixed into the tile, as in the approved mockup. */
private const val TintFraction = 0.3f
