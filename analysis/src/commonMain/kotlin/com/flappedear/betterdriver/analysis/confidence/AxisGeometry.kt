package com.flappedear.betterdriver.analysis.confidence

import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import kotlin.math.hypot

// What the detectors share (FBBD-177): stretches of the axis around a segment, its direction,
// distances and medians.

private const val Half = 0.5

/** The median of [values]; 0 for none. */
internal fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) * Half
}

internal fun distance(a: MetricPoint, b: MetricPoint) =
    hypot(a.eastMeters - b.eastMeters, a.northMeters - b.northMeters)

internal fun dot(a: Pair<Double, Double>, b: Pair<Double, Double>) = a.first * b.first + a.second * b.second

/** Unit direction of [axis] at point [i], towards the next point; zero for a degenerate one. */
internal fun tangent(axis: ProgressAxis, i: Int): Pair<Double, Double> {
    val n = axis.points.size
    val from = if (axis.closed || i < n - 1) i else n - 2
    val a = axis.points[from]
    val b = axis.points[(from + 1) % n]
    val ex = b.eastMeters - a.eastMeters
    val ey = b.northMeters - a.northMeters
    val length = hypot(ex, ey)
    return if (length > 0.0) (ex / length) to (ey / length) else 0.0 to 0.0
}

/**
 * The axis from [start] - [margin] to [end] + [margin], across the line on a loop (a segment whose
 * [end] is before its [start] wraps there). Distances along the axis inside it are [offset]s from
 * where it starts.
 */
internal class AxisWindow(private val axis: ProgressAxis, start: Double, end: Double, margin: Double) {
    private val length = axis.lengthMeters
    private val from = start - margin
    private val span = (if (axis.closed && end < start) end + length else end) - start + 2 * margin

    /** Metres from the window's start to [progress], around the loop on a closed axis. */
    fun offset(progress: Double): Double =
        if (axis.closed && length > 0.0) (progress - from).mod(length) else progress - from

    fun contains(progress: Double): Boolean {
        val o = offset(progress)
        return o in 0.0..span || (axis.closed && span >= length)
    }

    val indices: List<Int> = axis.points.indices.filter { contains(axis.cumulative[it]) }

    /** The window's axis point nearest [p], or null for an empty window. */
    fun nearest(p: MetricPoint): Int? = indices.minByOrNull { distance(axis.points[it], p) }

    /** [a] minus [b] along the axis, the short way round a loop. */
    fun difference(a: Double, b: Double): Double {
        val d = a - b
        return if (axis.closed && length > 0.0) (d + length / 2).mod(length) - length / 2 else d
    }
}
