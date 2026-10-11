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
import com.flappedear.betterdriver.core.ui.TrackTheme

/**
 * Sector strip (UI-05 option A: colour plus time). Finished sectors are
 * coloured against the best for that sector; the current one is outlined;
 * sectors not reached yet are dim. Set [showTimes] false for option B.
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
            val (bg, fg) = when {
                d == null -> c.background to c.textDim
                d < 0 -> c.gain to c.onGain
                else -> c.loss to c.onLoss
            }
            var m = Modifier.weight(1f).fillMaxHeight().background(bg, shape)
            if (d == null) m = m.border(2.dp, if (s.current) c.textDim else c.line, shape)
            Column(m, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                TrackLabel(s.name, color = fg)
                if (showTimes) {
                    NumberText(text = d?.let(::formatDelta) ?: "···", size = numberSize, color = fg)
                }
            }
        }
    }
}
