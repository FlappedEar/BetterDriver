package com.flappedear.betterdriver.core.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.labelStyle

/** Small uppercase caption used above or beside a value. */
@Composable
fun TrackLabel(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 11.dp,
    color: Color = TrackTheme.colors.textDim,
) {
    Text(text = text.uppercase(), modifier = modifier, style = labelStyle(size), color = color, maxLines = 1)
}
