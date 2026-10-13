package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.corners.CornerTraces
import com.flappedear.betterdriver.analysis.corners.PassTrace
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel

/** The traces card of the Corner laps screen, for tests. */
const val CornerTracesTag = "corner-traces"

/** Colours of the two passes the traces draw strongly: as lap A and lap B in the lap review. */
internal object PassColors {
    val a: Color @Composable get() = TrackTheme.colors.text
    val b: Color @Composable get() = TrackTheme.colors.cold
}

/**
 * Every pass through the corner over each other (FBBD-93): speed, then brake and throttle where the
 * session has them, over the corner and its approach, the corner shaded. Every pass is drawn thin
 * and dim; the [highlight]ed ones strong, in lap A's and lap B's colours. The speed scale carries
 * numbers only for a measured speed in a known unit; the pedals are shapes only (FBBD-174), and a
 * channel that is not measured is marked ≈.
 */
@Composable
internal fun CornerTracesCard(
    ui: CornerLapsUi,
    traces: CornerTraces,
    highlight: Highlight,
    modifier: Modifier = Modifier,
) {
    val c = TrackTheme.colors
    val band = listOf(CornerBand(ui.name, traces.entryMeters, traces.exitMeters))
    val style = TraceStyle(PassColors.a, PassColors.b, c.textDim.copy(alpha = DimAlpha), c.line.copy(alpha = BandAlpha))
    Card(modifier.testTag(CornerTracesTag)) {
        TraceChart(speedLabel(traces), traces, highlight, band, style, SpeedHeight, PassTrace::speed)
        if (traces.hasBrake) {
            val label = marked("Brake", traces.brakeProvenance)
            TraceChart(label, traces, highlight, band, style, PedalHeight, PassTrace::brake)
        }
        if (traces.hasThrottle) {
            val label = marked("Throttle", traces.throttleProvenance)
            TraceChart(label, traces, highlight, band, style, PedalHeight, PassTrace::throttle)
        }
        Legend(ui, highlight)
        Dim("Shaded: the corner, with ${approachText(traces)} of approach before it.")
        if (traces.speedProvenance.notMeasured || traces.brakeProvenance.notMeasured ||
            traces.throttleProvenance.notMeasured
        ) {
            ProvenanceLegend()
        }
    }
}

/** The colours of the traces: lap A, lap B, every other lap, and the corner's shading. */
private class TraceStyle(val a: Color, val b: Color, val other: Color, val corner: Color)

/** One channel of every pass, the highlighted ones last so they draw on top. */
@Suppress("LongParameterList") // One chart of the card and everything it draws.
@Composable
private fun TraceChart(
    label: String,
    traces: CornerTraces,
    highlight: Highlight,
    band: List<CornerBand>,
    style: TraceStyle,
    height: Int,
    channel: (PassTrace) -> List<List<ChartPoint>>,
) {
    val c = TrackTheme.colors
    val others = traces.passes.filter { it.lapIndex != highlight.a && it.lapIndex != highlight.b }
        .map { ChartLine(channel(it), style.other, width = 1f) }
    val strong = listOfNotNull(
        highlight.b?.let(traces::of)?.let { ChartLine(channel(it), style.b, width = StrongWidth) },
        highlight.a?.let(traces::of)?.let { ChartLine(channel(it), style.a, width = StrongWidth) },
    )
    TrackLabel(label)
    LapChart(
        others + strong,
        traces.lengthMeters,
        band,
        style.corner,
        c.line,
        c.textDim,
        cursorMeters = null,
        onCursor = {},
        modifier = Modifier.fillMaxWidth().height(height.dp),
    )
}

/** Which lines are which: lap A and lap B by colour, the rest dim. On a narrow screen it wraps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(ui: CornerLapsUi, highlight: Highlight) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        highlight.a?.let { ui.row(it) }?.let { Key(passName(it), PassColors.a) }
        highlight.b?.let { ui.row(it) }?.let { Key(passName(it), PassColors.b) }
        if (ui.rows.size > listOfNotNull(highlight.a, highlight.b).size) {
            Key("Other laps", TrackTheme.colors.textDim.copy(alpha = DimAlpha))
        }
    }
}

@Composable
private fun Key(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Dim(text)
    }
}

private const val DimAlpha = 0.35f
private const val StrongWidth = 2.5f
private const val BandAlpha = 0.6f
private const val SpeedHeight = 120
private const val PedalHeight = 56
