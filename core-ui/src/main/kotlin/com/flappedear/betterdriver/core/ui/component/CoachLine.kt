package com.flappedear.betterdriver.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.numberStyle
import com.flappedear.betterdriver.core.ui.textStyle

private val Speaker: ImageVector = ImageVector.Builder(
    name = "Speaker",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(
        "M3 9v6h4l5 5V4L7 9H3zm13.5 3a4.5 4.5 0 0 0-2.5-4v8a4.5 4.5 0 0 0 2.5-4z" +
            "M14 3.2v2.1a7 7 0 0 1 0 13.4v2.1a9 9 0 0 0 0-17.6z",
    ).toNodes(),
    fill = SolidColor(Color.Black),
).build()

/**
 * Text copy of the last spoken cue (UI-09 option A). Phase 5 feature; the
 * slot exists now so the layout does not change when the coach arrives.
 * Null [cue] keeps the slot empty.
 */
@Composable
fun CoachLine(corner: String?, cue: String?, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    Row(
        modifier.background(c.surface, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cue != null) {
            Icon(Speaker, contentDescription = null, tint = c.textDim, modifier = Modifier.size(16.dp))
            if (corner != null) Text(corner, style = numberStyle(16.dp, FontWeight.Bold), color = c.textDim)
            Text(
                cue,
                style = textStyle(16.dp, FontWeight.Medium),
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
