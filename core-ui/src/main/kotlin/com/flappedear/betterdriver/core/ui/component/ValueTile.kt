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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.numberStyle

/**
 * Fixed-layout value tile (UI-06 option A), e.g. speed, g, lap, and later
 * car temperatures. Width comes from the parent so digits never move it.
 */
@Composable
fun ValueTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    state: ValueState = ValueState.Normal,
    valueSize: Dp = 34.dp,
) {
    val c = TrackTheme.colors
    val stripe = when (state) {
        ValueState.Normal -> null
        ValueState.Cold -> c.cold
        ValueState.Warning -> c.warning
        ValueState.Critical -> c.onLoss
    }
    val bg = if (state == ValueState.Critical) c.loss else c.surface
    val valueColor = when (state) {
        ValueState.Normal -> c.text
        ValueState.Cold -> c.cold
        ValueState.Warning -> c.warning
        ValueState.Critical -> c.onLoss
    }
    val labelColor = if (state == ValueState.Critical) c.onLoss else c.textDim
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(bg)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(stripe ?: bg))
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            TrackLabel(label, color = labelColor)
            Row(verticalAlignment = Alignment.Bottom) {
                NumberText(value, size = valueSize, color = valueColor, weight = FontWeight.SemiBold)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        unit,
                        style = numberStyle(valueSize * 0.5f, FontWeight.Medium),
                        color = labelColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
