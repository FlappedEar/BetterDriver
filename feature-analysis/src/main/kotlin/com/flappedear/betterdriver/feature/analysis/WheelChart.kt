package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel

/** Test tag of the wheel trace under a corner's to-do. */
const val WheelChartTag = "wheel-chart"

/**
 * The steering wheel through a corner (FBBD-321, UI-273 B): your best lap in red against your
 * target in green, wheel into the turn upward, from the corner's start on the left to its end.
 */
@Composable
internal fun WheelChart(wheel: WheelTrace, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    Column(modifier.testTag(WheelChartTag)) {
        TrackLabel("Wheel · you red, target green")
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val zero = size.height * ZeroShare
            val perDeg = zero / wheel.maxDeg.toFloat()
            val perMeter = size.width / wheel.meters.toFloat()
            drawLine(c.line, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
            trace(wheel.target, c.gain, zero, perDeg, perMeter)
            trace(wheel.reference, c.loss, zero, perDeg, perMeter)
        }
    }
}

private fun DrawScope.trace(dots: List<WheelDot>, color: Color, zero: Float, perDeg: Float, perMeter: Float) {
    val path = Path()
    dots.forEachIndexed { i, d ->
        val x = d.meters.toFloat() * perMeter
        val y = zero - d.deg.toFloat() * perDeg
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
}

/** Where no wheel sits, from the top: room below it for the wheel turned out of the corner. */
private const val ZeroShare = 0.85f
