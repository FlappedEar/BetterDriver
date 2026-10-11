package com.flappedear.betterdriver.feature.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackColors
import com.flappedear.betterdriver.core.ui.TrackTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Colours of lap A and lap B, the same on the chart, the map and the labels. */
internal object LapColors {
    val a: Color @Composable get() = TrackTheme.colors.text
    val b: Color @Composable get() = TrackTheme.colors.cold
}

/** Where each car is along the chart, as a fraction of its own lap. */
internal class CursorFractions(val a: Float, val b: Float)

/** Scrubbing on the chart: a drag or tap at a fraction of the lap's distance. */
internal class Scrub(val onMove: (fraction: Float) -> Unit, val onStart: () -> Unit, val onEnd: () -> Unit)

/**
 * Delta (top) and both laps' speeds (bottom) over lap A's distance, with the cursor (FBBD-93).
 * Delta below the zero line is time lap B gained. Each car has a cursor in its colour; [cursor] is
 * read while drawing, so the cursors move every frame without recomposing the screen.
 */
@Composable
internal fun DeltaChart(c: Comparison, cursor: () -> CursorFractions, scrub: Scrub, modifier: Modifier = Modifier) {
    val colors = TrackTheme.colors
    val a = LapColors.a
    val b = LapColors.b
    val range = remember(c) { ChartRange.of(c.series) }
    Canvas(
        modifier
            .pointerInput(c) {
                detectTapGestures(onTap = {
                    scrub.onStart()
                    scrub.onMove((it.x / size.width).coerceIn(0f, 1f))
                    scrub.onEnd()
                })
            }
            .pointerInput(c) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        scrub.onStart()
                        scrub.onMove((o.x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = scrub.onEnd,
                    onDragCancel = scrub.onEnd,
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        scrub.onMove((change.position.x / size.width).coerceIn(0f, 1f))
                    },
                )
            },
    ) {
        val split = size.height * DeltaShare
        val gap = 8.dp.toPx()
        drawDelta(c, range, split - gap / 2, colors)
        drawSpeeds(c, range, split + gap / 2, a, b)
        val f = cursor()
        val xb = f.b.coerceIn(0f, 1f) * size.width
        val xa = f.a.coerceIn(0f, 1f) * size.width
        drawLine(b, Offset(xb, 0f), Offset(xb, size.height), strokeWidth = 2.dp.toPx())
        drawLine(a, Offset(xa, 0f), Offset(xa, size.height), strokeWidth = 2.dp.toPx())
    }
}

/** Extremes of the series, for scaling; the delta axis is symmetric so zero stays in the middle. */
private class ChartRange(val length: Double, val maxDelta: Double, val minSpeed: Double, val maxSpeed: Double) {
    companion object {
        fun of(series: List<SeriesPoint>): ChartRange {
            val length = max(series.lastOrNull()?.distanceMeters ?: 1.0, 1.0)
            val delta = max(series.maxOfOrNull { abs(it.deltaMicros.toDouble()) } ?: 0.0, MinDeltaRangeMicros)
            val speeds = series.flatMap { listOfNotNull(it.speedA, it.speedB) }
            val lo = speeds.minOrNull() ?: 0.0
            val hi = speeds.maxOrNull() ?: 1.0
            return ChartRange(length, delta, lo, if (hi > lo) hi else lo + 1)
        }
    }
}

private fun DrawScope.drawDelta(c: Comparison, r: ChartRange, bottom: Float, colors: TrackColors) {
    val mid = bottom / 2
    drawLine(colors.line, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
    val stroke = 2.dp.toPx()
    var prev: Offset? = null
    for (p in c.series) {
        val x = (p.distanceMeters / r.length * size.width).toFloat()
        val o = Offset(x, (mid - p.deltaMicros / r.maxDelta * mid).toFloat())
        prev?.let { drawLine(if (p.deltaMicros < 0) colors.gain else colors.loss, it, o, strokeWidth = stroke) }
        prev = o
    }
}

private fun DrawScope.drawSpeeds(c: Comparison, r: ChartRange, top: Float, a: Color, b: Color) {
    val h = size.height - top
    fun y(v: Double) = (top + h - (v - r.minSpeed) / (r.maxSpeed - r.minSpeed) * h).toFloat()
    fun x(d: Double) = (d / r.length * size.width).toFloat()
    val pa = Path()
    val pb = Path()
    var startedA = false
    var startedB = false
    for (p in c.series) {
        p.speedA?.let { if (startedA) pa.lineTo(x(p.distanceMeters), y(it)) else pa.moveTo(x(p.distanceMeters), y(it)) }
        startedA = startedA || p.speedA != null
        p.speedB?.let { if (startedB) pb.lineTo(x(p.distanceMeters), y(it)) else pb.moveTo(x(p.distanceMeters), y(it)) }
        startedB = startedB || p.speedB != null
    }
    drawPath(pb, b, style = Stroke(width = 2.dp.toPx()))
    drawPath(pa, a, style = Stroke(width = 2.dp.toPx()))
}

/** Lap A's line with both cars on it, north up (FBBD-93). Positions are read while drawing. */
@Composable
internal fun LapMap(c: Comparison, cursorMicros: () -> Long, modifier: Modifier = Modifier) {
    val a = LapColors.a
    val b = LapColors.b
    val line = TrackTheme.colors.textDim.copy(alpha = 0.5f)
    val pts = remember(c) { c.a.trace.points.map { it.xy } }
    val bounds = remember(c) {
        floatArrayOf(
            pts.minOf { it.x }.toFloat(), pts.maxOf { it.x }.toFloat(),
            pts.minOf { it.y }.toFloat(), pts.maxOf { it.y }.toFloat(),
        )
    }
    Canvas(modifier) {
        val pad = 10.dp.toPx()
        val w = max(bounds[1] - bounds[0], 1f)
        val h = max(bounds[3] - bounds[2], 1f)
        val k = min((size.width - 2 * pad) / w, (size.height - 2 * pad) / h)
        val ox = (size.width - w * k) / 2
        val oy = (size.height - h * k) / 2
        fun at(x: Double, y: Double) =
            Offset(ox + (x.toFloat() - bounds[0]) * k, size.height - oy - (y.toFloat() - bounds[2]) * k)
        val path = Path()
        pts.forEachIndexed { i, p ->
            val o = at(p.x, p.y)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, line, style = Stroke(width = 4.dp.toPx()))
        val t = cursorMicros()
        val pb = c.b.trace.at(c.bMicros(t)).xy
        val pa = c.a.trace.at(c.aMicros(t)).xy
        drawCircle(b, radius = 6.dp.toPx(), center = at(pb.x, pb.y))
        drawCircle(a, radius = 6.dp.toPx(), center = at(pa.x, pa.y))
    }
}

/** Share of the chart's height for the delta; the rest is speed. */
private const val DeltaShare = 0.55f

/** The delta axis spans at least ±0.5 s, so a near-equal pair does not look dramatic. */
private const val MinDeltaRangeMicros = 500_000.0
