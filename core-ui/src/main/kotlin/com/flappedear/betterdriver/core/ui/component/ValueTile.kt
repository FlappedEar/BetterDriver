package com.flappedear.betterdriver.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.labelStyle

/**
 * Fixed-layout value tile (UI-06 option A), e.g. speed, g, lap, and later
 * car temperatures. Width comes from the parent so digits never move it.
 * The unit sits on the label's line, so three digits of speed fit a phone's
 * tile at full size (FBBD-62).
 */
@Composable
fun ValueTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    state: ValueState = ValueState.Normal,
    valueSize: Dp = 34.dp,
    /** No value now ("–"): the value is drawn dim, as the delta's dash is. */
    dim: Boolean = false,
    verticalPadding: Dp = TilePadding,
) {
    val c = TrackTheme.colors
    val stripe = when (state) {
        ValueState.Normal -> null
        ValueState.Cold -> c.cold
        ValueState.Warning -> c.warning
        ValueState.Critical -> c.onLoss
    }
    val bg = if (state == ValueState.Critical) c.loss else c.surface
    val valueColor = when {
        state == ValueState.Normal && dim -> c.textDim
        else -> stripe ?: c.text
    }
    val labelColor = if (state == ValueState.Critical) c.onLoss else c.textDim
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(bg)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(stripe ?: bg))
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = verticalPadding).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                TrackLabel(label, color = labelColor)
                // "G" needs no "g" beside it.
                if (unit.isNotEmpty() && !unit.equals(label, ignoreCase = true)) {
                    Spacer(Modifier.width(6.dp))
                    Text(unit, style = labelStyle(UnitSize), color = labelColor, maxLines = 1)
                }
            }
            NumberText(value, size = valueSize, color = valueColor, weight = FontWeight.SemiBold)
        }
    }
}

/** The unit beside the label, as large as the label (TrackLabel's 11 dp). */
private val UnitSize = 11.dp

private val TilePadding = 6.dp

/** Height reserved for the label line: the label font's line at 11 dp is 13.2 dp. */
private val TileLabelHeight = 14.dp

/**
 * The largest value size up to [max] whose line fits a [ValueTile] [height] tall with
 * [verticalPadding] above and below (FBBD-262: the track screen's tile rows). The track screen
 * works it out once per panel, so the tiles themselves measure nothing.
 */
@Composable
fun tileValueSize(height: Dp, max: Dp, verticalPadding: Dp = TilePadding): Dp {
    // A dp to spare for rounding to whole pixels.
    val room = (height - verticalPadding * 2 - TileLabelHeight - 1.dp) / numberLineFactor(FontWeight.SemiBold)
    return min(max, room.coerceAtLeast(MinTileValueSize))
}

private val MinTileValueSize = 14.dp

/**
 * An empty tile slot (UI-125 B): a dashed outline the size of a tile, so the driver sees there is
 * room for a value. It draws no text.
 */
@Composable
fun EmptyTile(modifier: Modifier = Modifier) {
    val line = TrackTheme.colors.line
    Box(
        modifier.drawBehind {
            val stroke = DashWidth.toPx()
            val dash = DashLength.toPx()
            drawRoundRect(
                color = line,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(TileCorner.toPx()),
                style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
            )
        },
    )
}

private val DashWidth = 1.5.dp
private val DashLength = 6.dp
private val TileCorner = 8.dp
