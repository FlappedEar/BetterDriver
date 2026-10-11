package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.DeltaReference
import kotlin.math.roundToLong

/** A pass is measured from this far before the reference braking point. */
const val ApproachMeters = 120.0

/** ... to this far after the reference apex. */
const val ExitMeters = 60.0

/** Neighbouring windows never start before the previous apex plus this. */
private const val AfterPreviousApexMeters = 10.0

/** Two positions further apart than this along the path are a gap (GNSS loss, off the path): no pass. */
private const val MaxGapMeters = 30.0

/** Speeds are averaged over this many samples either side before the braking point is taken. */
private const val SmoothSamples = 2

private const val PeakToleranceKmh = 0.5

/**
 * The reference lap's path with its corners (FBBD-96): what every pass is measured against. Positions
 * are placed on the path by distance along it, so passes on different lines still compare.
 */
class CornerMap(val path: LapTrace, val corners: List<Corner>) {
    private val delta = DeltaReference(path.points, path.timesMicros)

    /** Measuring window of each corner, in metres along the path. */
    val windows: List<ClosedFloatingPointRange<Double>> = corners.mapIndexed { i, c ->
        val previousApex = corners.getOrNull(i - 1)?.apexAt
        val start = maxOf(0.0, c.brakeAt - ApproachMeters, previousApex?.plus(AfterPreviousApexMeters) ?: 0.0)
        val end = minOf(path.lengthMeters, c.apexAt + ExitMeters)
        start..end
    }

    /** Places positions of one lap on the path. Use a new one for each lap. */
    fun cursor(): PathCursor = PathCursor(delta.Cursor())

    fun meters(lap: Int): List<PassMeter> = corners.mapIndexed { i, c -> PassMeter(c, windows[i], lap) }

    /** Every pass of [trace], a lap driven on this map's track. */
    fun passes(trace: LapTrace, lap: Int): List<CornerPass> {
        val cursor = cursor()
        val meters = meters(lap)
        val passes = mutableListOf<CornerPass>()
        for (i in trace.points.indices) {
            val along = cursor.along(trace.points[i]) ?: continue
            for (m in meters) m.add(along, trace.timesMicros[i], trace.speedsKmh[i])?.let { passes += it }
        }
        return passes
    }
}

/** Distance along a [CornerMap] path for each position of one lap. */
class PathCursor internal constructor(private val cursor: DeltaReference.Cursor) {
    /** Metres along the path nearest [p]; null when [p] is off the path (pit lane, off track). */
    fun along(p: Xy): Double? = cursor.referenceMicros(p)?.let { cursor.alongMeters }
}

/**
 * Measures one corner on one lap (FBBD-96). Feed it every position of the lap in order; it returns
 * the pass once the car leaves the corner's window, or never if the car was not tracked across the
 * whole window (pits, GNSS gap).
 */
class PassMeter(
    private val corner: Corner,
    private val window: ClosedFloatingPointRange<Double>,
    private val lap: Int,
) {
    private class Sample(val along: Double, val tMicros: Long, val kmh: Double)

    private var previous: Sample? = null
    private val inside = mutableListOf<Sample>()
    private var entry: Sample? = null
    private var finished = false

    fun add(along: Double, tMicros: Long, kmh: Double): CornerPass? {
        if (finished) return null
        val s = Sample(along, tMicros, kmh)
        val p = previous
        previous = s
        if (p != null && (s.along - p.along > MaxGapMeters || s.along < p.along - MaxGapMeters)) {
            // A jump: whatever was measured so far is not one continuous drive.
            inside.clear()
            entry = null
        }
        return when {
            s.along < window.start -> {
                inside.clear()
                entry = null
                null
            }
            s.along <= window.endInclusive -> {
                if (inside.isEmpty()) entry = p?.takeIf { it.along < window.start }
                inside += s
                null
            }
            else -> {
                finished = true
                val e = entry
                if (e == null || inside.isEmpty()) null else measure(e, s)
            }
        }
    }

    private fun measure(before: Sample, after: Sample): CornerPass {
        val t0 = timeAt(before, inside.first(), window.start)
        val t1 = timeAt(inside.last(), after, window.endInclusive)
        val v = DoubleArray(inside.size) { i ->
            val lo = maxOf(0, i - SmoothSamples)
            val hi = minOf(inside.size - 1, i + SmoothSamples)
            (lo..hi).sumOf { inside[it].kmh } / (hi - lo + 1)
        }
        var min = 0
        for (i in v.indices) if (v[i] < v[min]) min = i
        // Smoothing rounds off the bottom; the apex is the slowest raw sample near the smoothed one.
        var apex = min
        for (i in maxOf(0, min - SmoothSamples)..minOf(v.size - 1, min + SmoothSamples)) {
            if (inside[i].kmh < inside[apex].kmh) apex = i
        }
        var peak = 0
        for (i in 0..min) if (v[i] > v[peak]) peak = i
        var brake = peak
        for (i in peak..min) if (v[i] >= v[peak] - PeakToleranceKmh) brake = i
        return CornerPass(
            corner = corner.number,
            lap = lap,
            brakeAt = inside[brake].along,
            apexAt = inside[apex].along,
            entryKmh = inside[brake].kmh,
            minKmh = inside[apex].kmh,
            timeMicros = (t1 - t0).roundToLong(),
        )
    }

    private fun timeAt(a: Sample, b: Sample, along: Double): Double {
        val span = b.along - a.along
        val u = if (span <= 0.0) 0.0 else ((along - a.along) / span).coerceIn(0.0, 1.0)
        return a.tMicros + u * (b.tMicros - a.tMicros)
    }
}
