package com.flappedear.betterdriver.feature.track.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.RevisionSegment
import com.flappedear.betterdriver.timing.track.SegmentKind
import com.flappedear.betterdriver.timing.track.TrackRevision

// The track's corners and straights on the editor's map (FBBD-179).

/**
 * What the map draws of the track's corners and straights (FBBD-179): each one's stretch of the
 * centreline ([paths]), the one being corrected ([selected], drawn from [draggedPath] while an end is
 * dragged) and its draggable [ends].
 */
internal class IdentityLayer(
    val identity: TrackRevision,
    val paths: Map<String, List<GeoPoint>>,
    val selected: RevisionSegment?,
    val draggedPath: List<GeoPoint>?,
    val ends: List<Pair<SegmentEnd, GeoPoint>>,
)

/**
 * The corners layer of [state] while no line is placed, its paths made again only when the corners
 * change; the one being corrected only when corrections can be stored ([correctable]).
 */
@Composable
internal fun rememberIdentityLayer(state: TrackEditorState, correctable: Boolean): IdentityLayer? {
    val identity = state.identity?.takeIf { state.selected == null }
    val paths = remember(identity) { identity?.let(::segmentPaths).orEmpty() }
    val segment = state.selectedSegment?.takeIf { correctable }
    val dragged = state.dragged
    val draggedPath = remember(dragged, segment, identity) {
        dragged?.let { (end, at) ->
            when {
                segment == null || identity == null -> null
                end == SegmentEnd.Start -> identity.path(at, segment.endMeters)
                else -> identity.path(segment.startMeters, at)
            }
        }
    }
    val ends = if (segment != null) state.segmentEnds() else emptyList()
    return identity?.let { IdentityLayer(it, paths, segment, draggedPath, ends) }
}

/** Each segment's stretch of the centreline, to draw. */
private fun segmentPaths(identity: TrackRevision): Map<String, List<GeoPoint>> =
    identity.segments.associate { it.id to identity.path(it.startMeters, it.endMeters) }

private const val PathStepPx = 2f
private const val CornerWidthDp = 3f
private const val StraightWidthDp = 1.5f
private const val SelectedWidthDp = 5f
private const val SelectedHaloDp = 8f
private const val CornerAlpha = 0.9f

/** While one is being corrected, the others step back. */
private const val OtherCornerAlpha = 0.55f

/**
 * The track's corners (amber) and straights (faint) along the centreline, numbered at each corner;
 * the one being corrected bold, with its ends as round handles.
 */
internal fun DrawScope.drawIdentity(layer: IdentityLayer, project: (GeoPoint) -> Offset, text: TextMeasurer) {
    val selected = layer.selected
    for (s in layer.identity.segments.filter { it.id != selected?.id }) {
        val corner = s.kind == SegmentKind.Corner
        val color = if (corner) {
            EditorColors.Corner.copy(alpha = if (selected == null) CornerAlpha else OtherCornerAlpha)
        } else {
            EditorColors.Straight
        }
        val width = (if (corner) CornerWidthDp else StraightWidthDp) * density
        drawGeoPath(layer.paths[s.id].orEmpty(), project, color, width)
    }
    if (selected != null) drawSelected(layer.draggedPath ?: layer.paths[selected.id].orEmpty(), layer.ends, project)
    for (s in layer.identity.corners) {
        val path = layer.paths[s.id].orEmpty()
        if (path.isNotEmpty()) drawNumber(s, project(path[path.size / 2]), s.id == selected?.id, text)
    }
}

/** The corner or straight being corrected, bold, and its [ends] as round handles. */
private fun DrawScope.drawSelected(
    path: List<GeoPoint>,
    ends: List<Pair<SegmentEnd, GeoPoint>>,
    project: (GeoPoint) -> Offset,
) {
    drawGeoPath(path, project, EditorColors.Halo, SelectedHaloDp * density)
    drawGeoPath(path, project, EditorColors.Accent, SelectedWidthDp * density)
    for ((_, end) in ends) {
        val o = project(end)
        drawCircle(EditorColors.Halo, 9.dp.toPx(), o)
        drawCircle(EditorColors.Accent, 7.dp.toPx(), o)
        drawCircle(EditorColors.Halo, 3.dp.toPx(), o)
    }
}

/** A corner's number ("3", or "3–4" for a chain) next to its middle at [at]. */
private fun DrawScope.drawNumber(s: RevisionSegment, at: Offset, selected: Boolean, text: TextMeasurer) {
    val label = if (s.lastNumber > s.number) "${s.number}–${s.lastNumber}" else "${s.number}"
    drawLabel(
        text,
        label,
        topLeft = at + Offset(6.dp.toPx(), -16.dp.toPx()),
        style = TextStyle(
            color = if (selected) EditorColors.Accent else EditorColors.Corner,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            shadow = Shadow(EditorColors.Halo, blurRadius = 4f),
        ),
    )
}

/**
 * [label] at [topLeft], measured on its own: drawing text by a [TextMeasurer] straight away fits it
 * in the space left right of and below [topLeft], which is negative, and throws, for a label past
 * the map's edge. A label wholly outside the map is not drawn.
 */
internal fun DrawScope.drawLabel(text: TextMeasurer, label: String, topLeft: Offset, style: TextStyle) {
    if (topLeft.x >= size.width || topLeft.y >= size.height) return
    val measured = text.measure(label, style, softWrap = false, maxLines = 1)
    val right = topLeft.x + measured.size.width
    val bottom = topLeft.y + measured.size.height
    if (right <= 0f || bottom <= 0f) return
    drawText(measured, topLeft = topLeft)
}

private fun DrawScope.drawGeoPath(points: List<GeoPoint>, project: (GeoPoint) -> Offset, color: Color, width: Float) {
    if (points.size < 2) return
    val path = Path()
    var last: Offset? = null
    for (p in points) {
        val o = project(p)
        val prev = last
        if (prev == null) {
            path.moveTo(o.x, o.y)
            last = o
        } else if ((o - prev).getDistance() >= PathStepPx) {
            path.lineTo(o.x, o.y)
            last = o
        }
    }
    // The end exactly, however close to the last point drawn.
    project(points.last()).let { path.lineTo(it.x, it.y) }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
