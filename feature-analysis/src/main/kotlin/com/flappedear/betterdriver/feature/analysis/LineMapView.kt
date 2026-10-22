@file:Suppress("TooManyFunctions") // The map's small drawing steps, kept together with it.

package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flappedear.betterdriver.analysis.optimal.PedalState
import com.flappedear.betterdriver.core.ui.TrackColors
import com.flappedear.betterdriver.core.ui.TrackTheme
import kotlin.math.hypot

/** Space kept round the drawing inside the map, so the corner badges fit. */
private val LinePadding = 36.dp

/** A tap this close to a corner's badge opens that corner. */
private val BadgeTouch = 40.dp

/** How far a badge sits outside its corner, away from the middle of the track. */
private val BadgeOffset = 26.dp

/** A zoomed corner shows this much round its road. */
private const val CornerMarginMeters = 10.0

private val BadgeText = 12.sp

/**
 * The map of "Where to find time" (FBBD-306): the whole lap with a badge per corner with time to
 * find (its tag and the seconds), or [corner] zoomed in with the road the laps used. His lap is
 * coloured by what the pedals do and the target is a thin green line (UI-165 C). Tapping a badge
 * opens its corner through [pick].
 */
@Composable
internal fun LineMapView(ui: LineUi, corner: LineCornerUi?, pick: (String?) -> Unit, modifier: Modifier) {
    val c = TrackTheme.colors
    val badge = MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    val text = rememberTextMeasurer()
    val all = remember(ui) { ui.corners.flatMap { it.reference } }
    val extent = remember(ui, corner) { if (corner == null) lineExtent(all) else cornerExtent(corner) }
    val middle = remember(all) {
        LineDot(all.map { it.eastMeters }.average(), all.map { it.northMeters }.average(), null)
    }
    val choose by rememberUpdatedState(pick)
    Canvas(
        modifier.pointerInput(ui, corner) {
            if (corner != null) return@pointerInput
            detectTapGestures { tap ->
                val frame = MapFrame(
                    extent.first, extent.second, size.width.toFloat(), size.height.toFloat(), LinePadding.toPx(),
                )
                val offset = BadgeOffset.toPx()
                ui.corners
                    .filter { it.gainSeconds > 0.0 && it.anchor != null }
                    .map { it to badgeAt(frame, it.anchor!!, middle, offset) }
                    .minByOrNull { (_, at) -> hypot(at.x - tap.x, at.y - tap.y) }
                    ?.takeIf { (_, at) -> hypot(at.x - tap.x, at.y - tap.y) <= BadgeTouch.toPx() }
                    ?.let { choose(it.first.tag) }
            }
        },
    ) {
        val frame = MapFrame(extent.first, extent.second, size.width, size.height, LinePadding.toPx())
        if (corner == null) drawOverview(ui, all, middle, frame, c, text, badge) else drawCorner(corner, frame, c)
    }
}

/** What a zoomed corner shows: its road, both lines and a margin round them. */
private fun cornerExtent(
    corner: LineCornerUi,
): Pair<ClosedFloatingPointRange<Double>, ClosedFloatingPointRange<Double>> {
    val (east, north) = lineExtent(corner.roadLeft + corner.roadRight + corner.reference + corner.target)
    return (east.start - CornerMarginMeters)..(east.endInclusive + CornerMarginMeters) to
        (north.start - CornerMarginMeters)..(north.endInclusive + CornerMarginMeters)
}

private fun at(frame: MapFrame, d: LineDot) = Offset(frame.x(d.eastMeters), frame.y(d.northMeters))

private fun linePath(frame: MapFrame, dots: List<LineDot>) = Path().apply {
    dots.forEachIndexed { i, d -> at(frame, d).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
}

/** The whole lap: the track from his lap, wide and dim, his lap by pedals, the targets and the badges. */
@Suppress("LongParameterList") // What the overview draws with.
private fun DrawScope.drawOverview(
    ui: LineUi,
    all: List<LineDot>,
    middle: LineDot,
    frame: MapFrame,
    c: TrackColors,
    text: TextMeasurer,
    badge: Pair<Color, Color>,
) {
    drawPath(
        linePath(frame, all), c.line,
        style = Stroke(12.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    ui.corners.forEach { drawByPedal(it.reference, frame, 4.dp.toPx(), c) }
    ui.corners.forEach {
        drawPath(linePath(frame, it.target), c.gain, style = Stroke(1.5f.dp.toPx(), join = StrokeJoin.Round))
    }
    ui.corners.filter { it.gainSeconds > 0.0 && it.anchor != null }.forEach {
        val center = badgeAt(frame, it.anchor!!, middle, BadgeOffset.toPx())
        drawBadge(text, "${it.tag} ${seconds(it.gainSeconds)}", center, badge.first, badge.second)
    }
}

/** One corner: the road the laps used (between its edges), his lap by pedals and the target. */
private fun DrawScope.drawCorner(corner: LineCornerUi, frame: MapFrame, c: TrackColors) {
    if (corner.roadLeft.size > 1) drawPath(roadPath(frame, corner), c.line)
    drawByPedal(corner.reference, frame, 5.dp.toPx(), c)
    drawPath(linePath(frame, corner.target), c.gain, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
}

/** The road the laps used through [corner]: along its left edge and back along its right. */
private fun roadPath(frame: MapFrame, corner: LineCornerUi) = linePath(frame, corner.roadLeft).apply {
    for (d in corner.roadRight.asReversed()) {
        val o = at(frame, d)
        lineTo(o.x, o.y)
    }
    close()
}

/** The colour of a stretch of his lap: what the pedals do there (UI-165 C). */
private fun pedalColor(pedal: PedalState?, c: TrackColors): Color = when (pedal) {
    PedalState.Braking -> c.loss
    PedalState.Accelerating -> c.gain
    PedalState.Coasting, null -> c.textDim
}

/** His lap through [dots], each stretch in the colour of what the pedals do there. */
private fun DrawScope.drawByPedal(dots: List<LineDot>, frame: MapFrame, width: Float, c: TrackColors) {
    for ((a, b) in dots.zipWithNext()) {
        drawLine(pedalColor(a.pedal, c), at(frame, a), at(frame, b), width, StrokeCap.Round)
    }
}

/** Where a corner's badge sits: [offset] pixels outside [anchor], away from the track's [middle]. */
private fun badgeAt(frame: MapFrame, anchor: LineDot, middle: LineDot, offset: Float): Offset {
    val x = frame.x(anchor.eastMeters)
    val y = frame.y(anchor.northMeters)
    val dx = x - frame.x(middle.eastMeters)
    val dy = y - frame.y(middle.northMeters)
    val n = hypot(dx, dy).takeIf { it > 0f } ?: 1f
    return Offset(x + dx / n * offset, y + dy / n * offset)
}

/** A rounded badge with [label], centred on [center] and kept inside the map. */
private fun DrawScope.drawBadge(text: TextMeasurer, label: String, center: Offset, fill: Color, ink: Color) {
    val layout = text.measure(label, TextStyle(color = ink, fontSize = BadgeText, fontWeight = FontWeight.Bold))
    val padX = 6.dp.toPx()
    val padY = 3.dp.toPx()
    val w = layout.size.width + 2 * padX
    val h = layout.size.height + 2 * padY
    val left = (center.x - w / 2).coerceIn(0f, maxOf(0f, size.width - w))
    val top = (center.y - h / 2).coerceIn(0f, maxOf(0f, size.height - h))
    drawRoundRect(fill, Offset(left, top), Size(w, h), CornerRadius(h / 2))
    drawText(layout, topLeft = Offset(left + padX, top + padY))
}
