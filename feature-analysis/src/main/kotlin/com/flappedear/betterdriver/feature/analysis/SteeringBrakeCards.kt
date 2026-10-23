package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// The FR-S's steering wheel and brake pressure on the Car tab (FBBD-322, UI review round 53).

/** UI-251 A: the hardest press, the typical stop and the time at half braking or more, in bar. */
@Composable
internal fun BrakePressureFigures(b: BrakePressureUi) {
    Figures(
        Modifier.testTag(BrakePressureTag),
        listOfNotNull(
            Figure("Hardest brake", "${b.hardest}", " ${b.unit}"),
            b.typicalStop?.let { Figure("Typical brake", "$it", " ${b.unit}") },
            Figure("Brake 50 % +", seconds(b.hardSeconds), if (b.perLap) " s/lap" else " s"),
        ),
    )
}

/**
 * UI-250 A: the most lock each way, the time over 90°, and the best lap's wheel against seconds
 * into the lap (up = left), on the FR-S.
 */
@Composable
internal fun SteeringCard(s: SteeringUi, modifier: Modifier) {
    Card(modifier.testTag(SteeringCardTag)) {
        TrackLabel("Steering · FR-S")
        Figures(
            Modifier,
            listOf(
                Figure("Most left", "${s.mostLeft}", "°"),
                Figure("Most right", "${s.mostRight}", "°"),
                Figure("Over 90°", seconds(s.largeLockSeconds), if (s.perLap) " s/lap" else " s"),
            ),
        )
        if (s.trace.isNotEmpty()) {
            SteeringChart(s, Modifier.fillMaxWidth().height(ChartHeight))
            val lap = s.traceLapMillis?.let { "Best lap, ${formatLapTime(it)}." } ?: "Best lap."
            Dim("$lap Up = left, down = right.")
        }
    }
}

@Composable
private fun SteeringChart(s: SteeringUi, modifier: Modifier) {
    val c = TrackTheme.colors
    val points = s.trace.flatten()
    val most = points.maxOf { abs(it.value) }
    // At least ±200° so a quiet lap does not look busy; a lap with more lock gets room for it.
    val limit = max(SteeringChartMinDegrees, ceil(most / SteeringGridDegrees) * SteeringGridDegrees)
    val span = points.maxOf { it.time }.coerceAtLeast(1.0)
    val grid = (1..(limit / SteeringGridDegrees).toInt()).flatMap { i ->
        listOf(i * SteeringGridDegrees, -i * SteeringGridDegrees)
    }
    Box(
        modifier.clearAndSetSemantics {
            contentDescription = "Steering wheel over the best lap, ${s.mostLeft}° left to ${s.mostRight}° right"
        },
    ) {
        Canvas(Modifier.fillMaxWidth().height(ChartHeight)) {
            fun x(t: Double) = (t / span).toFloat() * size.width
            fun y(v: Double) = ((limit - v) / (2 * limit)).toFloat() * size.height
            for (v in grid) drawLine(c.line, Offset(0f, y(v)), Offset(size.width, y(v)), 1.dp.toPx())
            drawLine(c.textDim, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx())
            for (segment in s.trace) {
                val path = Path()
                segment.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(x(p.time), y(p.value)) else path.lineTo(x(p.time), y(p.value))
                }
                drawPath(path, c.cold, style = Stroke(width = LineWidth.toPx()))
            }
        }
        Text(
            "${limit.roundToInt()}° L",
            color = c.textDim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Text(
            "${limit.roundToInt()}° R",
            color = c.textDim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

private class Figure(val label: String, val value: String, val unit: String)

/** Figures in a row, as the heart rate's: the label dim, the value bold, the unit dim. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Figures(modifier: Modifier, figures: List<Figure>) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (f in figures) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = TrackTheme.colors.textDim)) { append("${f.label} ") }
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(f.value) }
                    withStyle(SpanStyle(color = TrackTheme.colors.textDim)) { append(f.unit) }
                },
                color = TrackTheme.colors.text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Whole seconds from 10 up, one decimal under. */
private fun seconds(s: Double): String =
    if (s >= TenSeconds) "${s.roundToInt()}" else String.format(Locale.ROOT, "%.1f", s)

internal const val SteeringCardTag = "car-steering"
internal const val BrakePressureTag = "car-brake-pressure"

private const val SteeringChartMinDegrees = 200.0
private const val SteeringGridDegrees = 90.0
private const val TenSeconds = 10.0
