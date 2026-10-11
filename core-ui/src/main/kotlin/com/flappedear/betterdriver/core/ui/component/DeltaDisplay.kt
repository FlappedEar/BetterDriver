package com.flappedear.betterdriver.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.format.formatDelta
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.numberStyle
import kotlin.math.abs
import kotlin.math.min

/**
 * Live delta against the best lap (UI-03 option A: number plus bar).
 *
 * [deltaSeconds] negative means ahead. The bar grows left of centre when
 * ahead and right when behind, full at ±[barRangeSeconds]. Null shows "—"
 * (no reference lap yet) in the dim colour.
 */
@Composable
fun DeltaDisplay(
    deltaSeconds: Double?,
    modifier: Modifier = Modifier,
    numberSize: Dp = 150.dp,
    barRangeSeconds: Double = 1.0,
) {
    val c = TrackTheme.colors
    val ahead = deltaSeconds != null && deltaSeconds < 0
    val color = when {
        deltaSeconds == null -> c.textDim
        ahead -> c.gain
        else -> c.loss
    }
    Column(
        modifier = modifier
            .background(c.surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        TrackLabel("Delta vs best")
        Text(
            text = deltaSeconds?.let(::formatDelta) ?: "—",
            style = numberStyle(numberSize),
            color = color,
            maxLines = 1,
            softWrap = false,
        )
        val fraction = if (deltaSeconds == null) 0f else min(abs(deltaSeconds) / barRangeSeconds, 1.0).toFloat()
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(c.line, cornerRadius = r)
            val half = size.width / 2
            val w = half * fraction
            if (w > 0f) {
                val left = if (ahead) half - w else half
                drawRect(color, topLeft = Offset(left, 0f), size = Size(w, size.height))
            }
            drawRect(
                c.text.copy(alpha = 0.6f),
                topLeft = Offset(half - 1.dp.toPx(), 0f),
                size = Size(2.dp.toPx(), size.height),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val r = formatDelta(barRangeSeconds).drop(1)
            TrackLabel("−$r")
            TrackLabel("0")
            TrackLabel("+$r")
        }
    }
}
