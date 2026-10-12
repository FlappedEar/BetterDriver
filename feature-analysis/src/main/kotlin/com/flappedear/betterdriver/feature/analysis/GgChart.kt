package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flappedear.betterdriver.analysis.map.GgPoint
import com.flappedear.betterdriver.analysis.map.GgTrace
import com.flappedear.betterdriver.core.ui.TrackTheme
import java.util.Locale
import kotlin.math.min

private val AxisLabelSize = 10.sp

/** cos 45°: ring labels sit on the diagonal. */
private const val Diagonal = 0.70710677f

/**
 * The G-G (traction circle) diagram (FBBD-149): every reading of [trace] as a dot, acceleration up
 * and braking down, left turns to the left, over [reference] (the best lap) in its own colour, with a
 * ring every 0.5 g. [cursor] is the reading at the map cursor, ringed.
 */
@Composable
internal fun GgChart(
    trace: GgTrace?,
    reference: GgTrace?,
    traceColor: Color,
    referenceColor: Color,
    cursor: GgPoint?,
    modifier: Modifier = Modifier,
) {
    val c = TrackTheme.colors
    val text = rememberTextMeasurer()
    val rings = remember(trace, reference) { ggRings(maxG(trace, reference)) }
    val grid = c.line
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width, size.height) / 2 - 14.dp.toPx()
        val perG = radius / rings.last().toFloat()
        // Left turns (positive lateral) to the left, speeding up toward the top.
        fun at(p: GgPoint) = Offset(
            center.x - (p.lateralG * perG).toFloat(),
            center.y - (p.longitudinalG * perG).toFloat(),
        )
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        for (ring in rings) {
            val solid = ring % 1.0 == 0.0
            drawCircle(
                if (solid) c.textDim.copy(alpha = 0.5f) else grid, (ring * perG).toFloat(), center,
                style = Stroke(1.dp.toPx(), pathEffect = if (solid) null else dash),
            )
            // Labelled up and to the right, clear of the axes.
            val along = (ring * perG).toFloat() * Diagonal
            label(text, String.format(Locale.ROOT, "%.1f g", ring), center + Offset(along, -along), c.textDim)
        }
        drawLine(grid, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
        drawLine(grid, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())
        label(text, "Accel", Offset(center.x, center.y - radius - 6.dp.toPx()), c.textDim)
        label(text, "Brake", Offset(center.x, center.y + radius + 6.dp.toPx()), c.textDim)
        // The best lap first, so the shown lap reads on top.
        val layers = listOf(reference to referenceColor.copy(alpha = 0.55f), trace to traceColor.copy(alpha = 0.8f))
        for ((g, color) in layers) {
            val points = g?.points ?: continue
            drawPoints(points.map(::at), PointMode.Points, color, 3.dp.toPx(), StrokeCap.Round)
        }
        cursor?.let {
            drawCircle(c.text, 6.dp.toPx(), at(it), style = Stroke(2.dp.toPx()))
        }
    }
}

/** [value] centred on [center], small and dim. */
private fun DrawScope.label(text: TextMeasurer, value: String, center: Offset, color: Color) {
    val layout = text.measure(value, TextStyle(color = color, fontSize = AxisLabelSize))
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}
