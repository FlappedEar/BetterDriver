package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme

/**
 * "Coach this next time" (FBBD-148) as a flag at the end of a row: picks [cornerId] for the spoken
 * coach's next session at the track, or unpicks it; filled when [cornerId] is picked. [slot] keeps
 * the column when the row has no corner to pick, so the rows line up.
 */
@Composable
internal fun CoachFlag(ui: AnalysisUi, cornerId: String?, name: String, onCoachPick: (String) -> Unit, slot: Boolean) {
    if (cornerId == null || cornerId !in ui.coachPickable) {
        if (slot) Spacer(Modifier.size(FlagSize))
        return
    }
    val picked = cornerId in ui.coachPicked
    IconToggleButton(
        checked = picked,
        onCheckedChange = { onCoachPick(cornerId) },
        modifier = Modifier.size(FlagSize),
    ) {
        Icon(
            if (picked) Flag else FlagOutline,
            contentDescription = if (picked) "Coaching $name next time" else "Coach $name next time",
            tint = if (picked) TrackTheme.colors.cold else TrackTheme.colors.textDim,
        )
    }
}

private val FlagSize = 40.dp

/** Material Icons flag and outlined_flag (Apache 2.0). */
private val Flag = icon("Flag", "M14.4 6L14 4H5v17h2v-7h5.6l.4 2h7V6z")
private val FlagOutline = icon("FlagOutline", "M14 6l-1-2H5v17h2v-7h5l1 2h7V6h-6zm4 8h-4l-1-2H7V6h5l1 2h5v6z")

private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(path).toNodes(),
    fill = SolidColor(Color.Black),
).build()
