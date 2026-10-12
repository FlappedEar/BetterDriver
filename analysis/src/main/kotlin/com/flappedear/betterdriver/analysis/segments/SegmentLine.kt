package com.flappedear.betterdriver.analysis.segments

import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.unprojectCoordinate
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.nearestAxisIndex
import kotlin.math.ceil

/** Points of a [segmentLine] are this far apart, or further for a very long segment. */
const val SegmentLineStepMeters = 5.0

/** A [segmentLine] has at most this many points. */
const val MaxSegmentLinePoints = 200

/**
 * Where [segment] is (FBBD-148): the axis lap's line through it as positions every [stepMeters] from
 * its start to its end, in driving order, past the gate when it ends before it starts. Empty when the
 * axis is invalid. Unlike progress metres, which only mean something on this session's axis, positions
 * can be matched against any lap at the track, such as the coach's corners.
 */
fun segmentLine(
    axis: ProgressAxis,
    segment: TrackSegment,
    stepMeters: Double = SegmentLineStepMeters,
): List<GeoCoordinate> {
    if (!axis.valid || axis.points.isEmpty() || !(axis.lengthMeters > 0.0)) return emptyList()
    val start = segment.startProgressMeters
    var span = segment.endProgressMeters - start
    if (span < 0.0) span += axis.lengthMeters
    val steps = ceil(span / stepMeters).toInt().coerceIn(1, MaxSegmentLinePoints - 1)
    return (0..steps)
        .map { nearestAxisIndex(axis, start + span * it / steps) }
        .distinct()
        .map { axis.points[it].let { p -> unprojectCoordinate(p.eastMeters, p.northMeters, axis.origin) } }
}
