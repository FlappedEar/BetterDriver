@file:Suppress("MatchingDeclarationName")

package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import kotlin.math.max
import kotlin.math.min

/** One line on a [LapChart]: runs of points (a GPS gap splits runs) in one colour. */
data class ChartLine(val runs: List<List<ChartPoint>>, val color: Color, val width: Float = 2f)

/** The value range a chart spans, padded so lines do not touch the edges. */
@Suppress("ReturnCount")
internal fun valueRange(lines: List<ChartLine>, includeZero: Boolean): Pair<Double, Double> {
    var low = if (includeZero) 0.0 else Double.POSITIVE_INFINITY
    var high = if (includeZero) 0.0 else Double.NEGATIVE_INFINITY
    for (line in lines) for (run in line.runs) for (p in run) {
        low = min(low, p.value)
        high = max(high, p.value)
    }
    if (!low.isFinite() || !high.isFinite()) return 0.0 to 1.0
    if (high - low < MinimumSpan) {
        val middle = (high + low) / 2
        return middle - MinimumSpan / 2 to middle + MinimumSpan / 2
    }
    val pad = (high - low) * RangePadding
    return low - pad to high + pad
}

private const val MinimumSpan = 0.2
private const val RangePadding = 0.08

/**
 * Values by distance from the start/finish line, 0 to [lengthMeters], with [corners] shaded behind
 * the lines. A [zeroLine] marks 0. [cursorMeters] draws a vertical cursor; dragging or tapping
 * moves it through [onCursor].
 */
@Suppress("CyclomaticComplexMethod")
@Composable
fun LapChart(
    lines: List<ChartLine>,
    lengthMeters: Double,
    corners: List<CornerBand>,
    cornerColor: Color,
    gridColor: Color,
    cursorColor: Color,
    cursorMeters: Double?,
    onCursor: (Double) -> Unit,
    modifier: Modifier = Modifier,
    zeroLine: Boolean = false,
) {
    val (low, high) = valueRange(lines, zeroLine)
    val moveCursor by rememberUpdatedState(onCursor)
    fun meters(x: Float, width: Float) = (x / width * lengthMeters).coerceIn(0.0, lengthMeters)
    Canvas(
        modifier
            .pointerInput(lengthMeters) { detectTapGestures { moveCursor(meters(it.x, size.width.toFloat())) } }
            .pointerInput(lengthMeters) {
                detectHorizontalDragGestures { change, _ -> moveCursor(meters(change.position.x, size.width.toFloat())) }
            },
    ) {
        if (lengthMeters <= 0.0) return@Canvas
        fun x(m: Double) = (m / lengthMeters * size.width).toFloat()
        fun y(v: Double) = ((high - v) / (high - low) * size.height).toFloat()
        for (band in corners) shadeBand(band, lengthMeters, cornerColor, ::x)
        if (zeroLine && low < 0.0 && high > 0.0) {
            drawLine(
                gridColor, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }
        for (line in lines) {
            for (run in line.runs) {
                if (run.size < 2) continue
                val path = Path()
                run.forEachIndexed { i, p ->
                    val px = x(p.distanceMeters)
                    val py = y(p.value)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, line.color, style = Stroke(line.width.dp.toPx()))
            }
        }
        cursorMeters?.let { drawLine(cursorColor, Offset(x(it), 0f), Offset(x(it), size.height), 1.dp.toPx()) }
    }
}

private fun DrawScope.shadeBand(band: CornerBand, length: Double, color: Color, x: (Double) -> Float) {
    fun rect(from: Double, to: Double) =
        drawRect(color, topLeft = Offset(x(from), 0f), size = Size(x(to) - x(from), size.height))
    if (band.endMeters >= band.startMeters) {
        rect(band.startMeters, band.endMeters)
    } else {
        rect(band.startMeters, length)
        rect(0.0, band.endMeters)
    }
}
