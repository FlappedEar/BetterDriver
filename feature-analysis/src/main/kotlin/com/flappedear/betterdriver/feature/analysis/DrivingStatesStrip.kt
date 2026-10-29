package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.driving.DrivingState
import com.flappedear.betterdriver.analysis.driving.DrivingStateRun
import com.flappedear.betterdriver.core.ui.TrackColors
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel

/** Test tag of the driving-state strips on the Compare tab (FBBD-175). */
const val DrivingStatesTag = "driving-states"

/** The colour of each state: the pedals in the screen's gain and loss colours, coasting in its warning one. */
private fun TrackColors.of(state: DrivingState): Color = when (state) {
    DrivingState.Braking -> loss
    DrivingState.Coasting -> warning
    DrivingState.Cornering -> cold
    DrivingState.Accelerating -> gain.copy(alpha = PartThrottleAlpha)
    DrivingState.FullThrottle -> gain
}

private const val PartThrottleAlpha = 0.5f

/**
 * What each compared lap's car was doing along the lap (FBBD-175), one thin strip per lap under the
 * speed chart on the same distance scale, a key, and how long each lap coasted. Values not read from
 * both pedals carry [NotMeasuredMark].
 */
@Composable
internal fun DrivingStatesStrip(ui: CompareUi) {
    val c = TrackTheme.colors
    TrackLabel(drivingStatesLabel(ui), Modifier.testTag(DrivingStatesTag))
    StateStrip("Lap ${ui.referenceNumber}", ui.referenceStates, ui.lengthMeters, c.best)
    StateStrip("Lap ${ui.otherNumber}", ui.otherStates, ui.lengthMeters, c.cold)
    Text(
        buildAnnotatedString {
            DrivingStateNames.entries.forEachIndexed { i, (state, name) ->
                if (i > 0) append("  ")
                withStyle(SpanStyle(color = c.of(state))) { append("■ ") }
                append(name)
            }
        },
        color = c.textDim,
        style = MaterialTheme.typography.bodySmall,
    )
    coastingText(ui)?.let { Dim(it) }
}

@Composable
private fun StateStrip(label: String, runs: List<DrivingStateRun>, length: Double, labelColor: Color) {
    val c = TrackTheme.colors
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(StripLabelWidth), color = labelColor, style = MaterialTheme.typography.bodySmall)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(StripHeight)
                .semantics { contentDescription = "$label: what the car is doing along the lap" },
        ) {
            drawRect(track)
            if (length <= 0.0) return@Canvas
            for (run in runs) {
                val from = (run.startMeters / length).toFloat().coerceIn(0f, 1f) * size.width
                val to = (run.endMeters / length).toFloat().coerceIn(0f, 1f) * size.width
                if (to > from) drawRect(c.of(run.state), Offset(from, 0f), Size(to - from, size.height))
            }
        }
    }
}

private val StripLabelWidth = 48.dp
private val StripHeight = 10.dp
