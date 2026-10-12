package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flappedear.betterdriver.analysis.map.LapMap
import com.flappedear.betterdriver.analysis.map.MapPoint
import com.flappedear.betterdriver.analysis.map.nearestMapPoint
import com.flappedear.betterdriver.core.ui.TrackTheme
import java.util.Locale

/** Space kept round the track inside the map, so corner labels fit. */
private val MapPadding = 32.dp

/** A touch this close to the line reads the point under it. */
private val TouchRadius = 32.dp

/** How far outside its corner a corner label sits. */
private val LabelOffset = 16.dp

private val TagSize = 11.sp
private const val TagHaloBlur = 6f

/** One coloured stretch of the lap line, in metres; colours are worked out once per colouring. */
private class MapStroke(val from: MapPoint, val to: MapPoint, val color: Color)

/** The colour scale the line is drawn with, for the legend and the strokes. */
internal class MapScale(val coloring: MapColoring, val speed: ScaleRange?, val gainLossLimit: Double)

/** The scale of [map] in [coloring]: speeds widened to tens, time vs best to most of its rates. */
internal fun mapScale(map: LapMap, coloring: MapColoring): MapScale {
    val points = map.runs.flatten()
    return MapScale(
        coloring,
        speedRange(points.mapNotNull { it.speed }),
        gainLossLimit(points.mapNotNull { it.deltaRate }),
    )
}

/** The colour of [p] on [scale]; [neutral] when the point has no value to colour. */
private fun pointColor(p: MapPoint, scale: MapScale, gain: Int, neutral: Int, loss: Int): Color {
    val argb = when (scale.coloring) {
        MapColoring.Speed -> {
            val range = scale.speed
            val speed = p.speed
            if (range == null || speed == null) null else scaleColor(range.fraction(speed))
        }
        MapColoring.Delta -> p.deltaRate?.let { gainLossColor(it, scale.gainLossLimit, gain, neutral, loss) }
    }
    return Color(argb ?: neutral)
}

/**
 * The lap on the map (FBBD-149): the track outline, the lap's GPS line coloured by [scale], its
 * corners tagged T1, T2 … on the outside of each turn and the start/finish line marked. Touching or
 * dragging along the line moves [cursor] (a distance on the lap) through [onCursor].
 */
@Suppress("LongMethod")
@Composable
internal fun LapMapView(
    map: LapMap,
    scale: MapScale,
    cursor: MapPoint?,
    onCursor: (MapPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TrackTheme.colors
    val text = rememberTextMeasurer()
    val outlineColor = c.line
    val strokes = remember(map, scale, c) {
        val gain = c.gain.toArgb()
        val neutral = c.textDim.toArgb()
        val loss = c.loss.toArgb()
        map.runs.flatMap { run ->
            run.zipWithNext { a, b ->
                // A stretch takes the colour of its middle.
                val middle = MapPoint(
                    a.eastMeters, a.northMeters, a.distanceMeters,
                    average(a.speed, b.speed), null, average(a.deltaRate, b.deltaRate),
                )
                MapStroke(a, b, pointColor(middle, scale, gain, neutral, loss))
            }
        }
    }
    val extent = remember(map) { mapExtent(map) }
    val move by rememberUpdatedState(onCursor)
    fun pick(position: Offset, width: Float, height: Float, padding: Float, radius: Float) {
        val frame = MapFrame(extent.first, extent.second, width, height, padding)
        nearestMapPoint(map.runs, frame.east(position.x), frame.north(position.y), radius / frame.scale)
            ?.let(move)
    }
    Canvas(
        modifier
            .pointerInput(map) {
                detectTapGestures {
                    pick(it, size.width.toFloat(), size.height.toFloat(), MapPadding.toPx(), TouchRadius.toPx())
                }
            }
            .pointerInput(map) {
                detectDragGestures { change, _ ->
                    change.consume()
                    pick(
                        change.position, size.width.toFloat(), size.height.toFloat(),
                        MapPadding.toPx(), TouchRadius.toPx(),
                    )
                }
            },
    ) {
        val frame = MapFrame(extent.first, extent.second, size.width, size.height, MapPadding.toPx())
        fun at(east: Double, north: Double) = Offset(frame.x(east), frame.y(north))
        // The track, from the fastest lap, wide and dim under the lap.
        if (map.outline.size > 1) {
            val path = Path()
            map.outline.forEachIndexed { i, p ->
                val o = at(p.eastMeters, p.northMeters)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            path.close()
            drawPath(
                path, outlineColor,
                style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            // Start/finish: where the axis starts.
            val start = map.outline.first()
            drawCircle(c.text, 4.dp.toPx(), at(start.eastMeters, start.northMeters))
            drawCircle(c.background, 2.dp.toPx(), at(start.eastMeters, start.northMeters))
        }
        val width = 4.dp.toPx()
        for (s in strokes) {
            drawLine(
                s.color, at(s.from.eastMeters, s.from.northMeters), at(s.to.eastMeters, s.to.northMeters),
                width, StrokeCap.Round,
            )
        }
        for (corner in map.corners) {
            val anchor = at(corner.anchor.eastMeters, corner.anchor.northMeters)
            // North up on screen: a northward vector points up.
            val out = Offset(corner.outward.eastMeters.toFloat(), -corner.outward.northMeters.toFloat())
            drawTag(text, cornerTag(corner.name), anchor + out * LabelOffset.toPx(), c.text, c.background)
        }
        cursor?.let {
            val o = at(it.eastMeters, it.northMeters)
            drawCircle(c.background, 8.dp.toPx(), o)
            drawCircle(c.text, 8.dp.toPx(), o, style = Stroke(2.5f.dp.toPx()))
        }
    }
}

private fun average(a: Double?, b: Double?) = if (a != null && b != null) (a + b) / 2 else a ?: b

/** [label] centred on [center], with a halo so it reads over the line. */
private fun DrawScope.drawTag(text: TextMeasurer, label: String, center: Offset, color: Color, halo: Color) {
    val layout = text.measure(
        label,
        TextStyle(
            color = color,
            fontSize = TagSize,
            fontWeight = FontWeight.SemiBold,
            shadow = Shadow(halo, blurRadius = TagHaloBlur),
        ),
    )
    // Kept inside the map, so a corner at its edge is still labelled in full.
    val left = (center.x - layout.size.width / 2f).coerceIn(0f, maxOf(0f, size.width - layout.size.width))
    val top = (center.y - layout.size.height / 2f).coerceIn(0f, maxOf(0f, size.height - layout.size.height))
    drawText(layout, topLeft = Offset(left, top))
}

/** The legend under the map: the colour bar and what its ends mean. */
@Composable
internal fun MapLegend(scale: MapScale, speedUnit: String, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    val bar = when (scale.coloring) {
        MapColoring.Speed -> SpeedScaleColors.map(::Color)
        MapColoring.Delta -> listOf(c.gain, c.textDim, c.loss)
    }
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(Brush.horizontalGradient(bar), RoundedCornerShape(4.dp)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            when (scale.coloring) {
                MapColoring.Speed -> {
                    val range = scale.speed
                    Dim(range?.let { "${it.low.toInt()} $speedUnit" } ?: "–")
                    Dim("slow to fast")
                    Dim(range?.let { "${it.high.toInt()} $speedUnit" } ?: "")
                }
                MapColoring.Delta -> {
                    val limit = String.format(Locale.ROOT, "%.2f", scale.gainLossLimit)
                    Dim("Gaining $limit s", color = c.gain)
                    Dim("per 100 m")
                    Dim("Losing $limit s", color = c.loss)
                }
            }
        }
    }
}
