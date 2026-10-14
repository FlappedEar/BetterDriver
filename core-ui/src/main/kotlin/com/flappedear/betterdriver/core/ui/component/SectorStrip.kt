package com.flappedear.betterdriver.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.format.formatDelta
import com.flappedear.betterdriver.core.ui.format.isDeltaGain
import com.flappedear.betterdriver.core.ui.TrackTheme

/**
 * Sector strip (UI-05 option A: colour plus time). Finished sectors are
 * coloured against the best for that sector; the current one is outlined;
 * sectors not reached yet are dim. Set [showTimes] false for option B.
 *
 * A finished sector is a coloured outline with its name and time in the same colour on black,
 * not a filled block: on the OLED phone screen a lit area draws power and heats the phone.
 */
@Composable
fun SectorStrip(
    sectors: List<SectorState>,
    modifier: Modifier = Modifier,
    numberSize: Dp = 30.dp,
    showTimes: Boolean = true,
) {
    val c = TrackTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        sectors.forEach { s ->
            val d = s.deltaSeconds
            val (edge, fg) = when {
                d == null -> (if (s.current) c.textDim else c.line) to c.textDim
                isDeltaGain(d) -> c.gain to c.gain
                else -> c.loss to c.loss
            }
            val m = Modifier.weight(1f).fillMaxHeight().background(c.background, shape).border(2.dp, edge, shape)
            Column(m, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                TrackLabel(s.name, color = fg)
                if (showTimes) {
                    NumberText(text = d?.let(::formatDelta) ?: "···", size = numberSize, color = fg)
                }
            }
        }
    }
}
