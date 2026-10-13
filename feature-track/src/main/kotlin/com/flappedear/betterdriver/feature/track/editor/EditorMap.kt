package com.flappedear.betterdriver.feature.track.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flappedear.betterdriver.timing.edit.LineRef
import com.flappedear.betterdriver.timing.edit.line
import com.flappedear.betterdriver.timing.edit.lineRefs
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.map.TileSource
import com.flappedear.betterdriver.timing.track.SegmentEdit
import com.flappedear.betterdriver.timing.track.TrackLine

/** Path points closer than this on screen are skipped when drawing the lap, in pixels. */
private const val PathStepPx = 2f
private const val ArrowLengthDp = 12f
private const val ArrowHeadDp = 3f
private const val DashOn = 8f
private const val LineWidthDp = 2.4f
private const val SelectedWidthDp = 3.2f
private const val ArrowWidthDp = 1.6f
private const val DashOff = 6f

/**
 * The editor's map: the background, the recorded lap in blue, every line with its direction
 * arrow, and the selected line with its two draggable ends (UI-23, UI-24).
 */
@Composable
internal fun EditorMap(
    state: TrackEditorState,
    camera: MapCamera,
    source: TileSource,
    tiles: TileLoader,
    modifier: Modifier = Modifier,
    onCorrect: ((SegmentEdit) -> Unit)? = null,
) {
    val text = rememberTextMeasurer()
    val selectedLine = state.selectedLine
    // The track's corners and straights (FBBD-179), while no line is being placed.
    val corners = rememberIdentityLayer(state, correctable = onCorrect != null)
    val ends = corners?.ends.orEmpty()
    TileMap(
        camera = camera,
        source = source,
        tiles = tiles,
        modifier = modifier,
        handles = selectedLine?.let { listOf(it.left, it.right) } ?: ends.map { it.second },
        onTap = state::onTap,
        onHandleDrag = { i, to ->
            if (state.selected != null) {
                state.moveEnd(left = i == 0, to = to)
            } else {
                ends.getOrNull(i)?.let { state.dragBoundary(it.first, to) }
            }
        },
        onHandleDragEnd = { if (state.selected == null) state.endDrag()?.let { onCorrect?.invoke(it) } },
    ) { project ->
        state.path?.let { drawLap(it.points, project, highlight = state.selected != null) }
        corners?.let { drawIdentity(it, project, text) }
        val track = state.track
        for (ref in track.lineRefs()) {
            val line = track.line(ref) ?: continue
            drawTimingLine(ref, line, project, ref == state.selected, text, ref.mapLabel(track.pointToPoint))
        }
        state.firstTap?.let { drawCircle(EditorColors.Accent, 5.dp.toPx(), project(it)) }
    }
}

private fun DrawScope.drawLap(points: List<GeoPoint>, project: (GeoPoint) -> Offset, highlight: Boolean) {
    val path = Path()
    var last: Offset? = null
    for (p in points) {
        val o = project(p)
        val prev = last
        if (prev == null) {
            path.moveTo(o.x, o.y)
        } else if ((o - prev).getDistance() < PathStepPx) {
            continue
        } else {
            path.lineTo(o.x, o.y)
        }
        last = o
    }
    drawPath(
        path,
        EditorColors.Accent.copy(alpha = if (highlight) 0.95f else 0.6f),
        style = Stroke(width = (if (highlight) 2.5f else 1.6f) * density),
    )
}

@Suppress("LongParameterList") // The line, where it is drawn, and its label.
private fun DrawScope.drawTimingLine(
    ref: LineRef,
    line: TrackLine,
    project: (GeoPoint) -> Offset,
    selected: Boolean,
    text: TextMeasurer,
    label: String,
) {
    val a = project(line.left)
    val b = project(line.right)
    val pit = ref == LineRef.PitEntry || ref == LineRef.PitExit
    val color = when {
        selected -> EditorColors.Accent
        pit -> EditorColors.Pit
        else -> EditorColors.Line
    }
    val width = (if (selected) SelectedWidthDp else LineWidthDp) * density
    drawLine(EditorColors.Halo, a, b, width + 2 * density, StrokeCap.Round)
    drawLine(
        color, a, b, width, StrokeCap.Round,
        pathEffect = if (pit) PathEffect.dashPathEffect(floatArrayOf(DashOn * density, DashOff * density)) else null,
    )
    // The way the car crosses: square to the line, towards the driver's front (left end on the left).
    val d = b - a
    val n = d.getDistance()
    if (n > 0f) {
        val forward = Offset(d.y / n, -d.x / n)
        val mid = (a + b) / 2f
        val tip = mid + forward * (ArrowLengthDp * density)
        val head = ArrowHeadDp * density
        drawLine(color, mid, tip, ArrowWidthDp * density)
        val arrow = Path().apply {
            moveTo(tip.x + forward.x * head, tip.y + forward.y * head)
            lineTo(tip.x - forward.y * head, tip.y + forward.x * head)
            lineTo(tip.x + forward.y * head, tip.y - forward.x * head)
            close()
        }
        drawPath(arrow, color)
    }
    if (selected) {
        for (end in listOf(a, b)) {
            drawCircle(EditorColors.Halo, 6.dp.toPx(), end)
            drawCircle(EditorColors.Accent, 6.dp.toPx(), end, style = Stroke(2.dp.toPx()))
        }
    }
    drawLabel(
        text,
        label,
        topLeft = a + Offset(8.dp.toPx(), -18.dp.toPx()),
        style = TextStyle(
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            shadow = Shadow(EditorColors.Halo, blurRadius = 4f),
        ),
    )
}
