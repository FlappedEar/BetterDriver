package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.DeltaReference
import com.flappedear.betterdriver.timing.steering.SteerSample
import com.flappedear.betterdriver.timing.steering.SteeringPass
import com.flappedear.betterdriver.timing.steering.bends
import com.flappedear.betterdriver.timing.steering.steeringPass
import kotlin.math.abs
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

/** A position this close along the path to the one before is the receiver holding it, not a new one. */
private const val HeldMeters = 0.01

/** The steering of a pass is read from this far before the reference brake point (FBBD-321). */
const val SteerBeforeBrakeMeters = 30.0

/** The pedal is back on when it rises to this, in percent, as the lap analysis reads a pickup (FBBD-307). */
const val PickupOnPct = 20.0

/** ... after it was at or below this; a fall below it within [PickupHoldMicros] is a blip, not a pickup. */
const val PickupOffPct = 10.0

/**
 * In a corner braked for, a pickup is looked for from this far before the apex (not before braking
 * starts): a blip of the pedal while braking (heel and toe) is no pickup.
 */
const val PickupSearchBeforeApexMeters = 30.0

/** A pickup holds the pedal at or above [PickupOffPct] this long (or to the end of the window). */
const val PickupHoldMicros = 200_000L

/**
 * What else is known of the car at one position (FBBD-307): [offsetMeters] beside the reference
 * path, positive left ([CornerMap.offset]), the pedal in percent, and (FBBD-321) the steering wheel
 * in degrees, positive left, with the position itself to read the path's bend from; null where not known.
 */
data class PassReading(
    val offsetMeters: Double? = null,
    val throttlePct: Double? = null,
    val steerDeg: Double? = null,
    val point: Xy? = null,
    /** The engine speed (FBBD-326). */
    val rpm: Double? = null,
)

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

    /**
     * How far [p] is beside the path at [along] metres (FBBD-307), positive left of the direction of
     * travel, negative right: where the car is across the road against the reference lap's line.
     */
    fun offset(p: Xy, along: Double): Double? {
        val a = path.along
        val m = along.coerceIn(0.0, path.lengthMeters)
        var lo = 0
        var hi = a.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (a[mid] <= m) lo = mid else hi = mid
        }
        val p0 = path.points[lo]
        val d = path.points[hi] - p0
        val length = d.length()
        if (length <= 0.0) return null
        val u = (m - a[lo]) / length
        val ex = p.x - (p0.x + d.x * u)
        val ey = p.y - (p0.y + d.y * u)
        return (d.x * ey - d.y * ex) / length
    }

    /** Places positions of one lap on the path. Use a new one for each lap. */
    fun cursor(): PathCursor = PathCursor(delta.Cursor())

    fun meters(lap: Int): List<PassMeter> =
        corners.mapIndexed { i, c -> PassMeter(c, windows[i], lap, path.lengthMeters) }

    /** Every pass of [trace], a lap driven on this map's track. */
    fun passes(trace: LapTrace, lap: Int): List<CornerPass> {
        val cursor = cursor()
        val meters = meters(lap)
        val passes = mutableListOf<CornerPass>()
        for (i in trace.points.indices) {
            val along = cursor.along(trace.points[i])
            if (along == null) {
                meters.forEach(PassMeter::lost)
                continue
            }
            // A pass keeps whether its speeds were measured (FBBD-177), so a reference built from
            // worked-out speeds never gives a precise speed cue.
            val measured = trace.speedsMeasured?.get(i) ?: true
            val reading = PassReading(
                offset(trace.points[i], along), trace.throttlesPct?.get(i), trace.steersDeg?.get(i), trace.points[i],
                trace.rpms?.get(i),
            )
            for (m in meters) {
                m.add(along, trace.timesMicros[i], trace.speedsKmh[i], measured, reading)?.let { passes += it }
            }
        }
        meters.mapNotNullTo(passes) { it.finish() }
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
 * whole window (pits, GNSS gap, off the path). A window that starts at the line starts with the lap;
 * one that ends at the line is closed by [finish] when the lap ends.
 */
@Suppress("TooManyFunctions") // One measure of a pass each.
class PassMeter(
    private val corner: Corner,
    private val window: ClosedFloatingPointRange<Double>,
    private val lap: Int,
    /** Where the finish line is along the path. */
    private val lineAt: Double = window.endInclusive,
) {
    /**
     * [fresh]: a new position, not the receiver holding its last one through a dropout (FBBD-177).
     * [reading]: where it was across the road and the pedal, when known.
     */
    private class Sample(
        val along: Double,
        val tMicros: Long,
        val kmh: Double,
        val measured: Boolean = true,
        val fresh: Boolean = true,
        val reading: PassReading = PassReading(),
    )

    private var previous: Sample? = null
    private val inside = mutableListOf<Sample>()
    private var entry: Sample? = null
    private var finished = false
    private var started = false
    private var line: Sample? = null

    /**
     * Live, the lap began at the line at [lineMicros], before the first position fed in; a window that
     * starts at the line is timed from there. Offline the trace's first position is on the line already.
     */
    fun start(lineMicros: Long, lineKmh: Double) {
        line = Sample(0.0, lineMicros, lineKmh)
    }

    /**
     * The car is [along] the path at [tMicros] at [kmh], which the receiver [measured] or was worked
     * out, with what else is known of the car there ([reading]).
     */
    fun add(
        along: Double,
        tMicros: Long,
        kmh: Double,
        measured: Boolean = true,
        reading: PassReading = PassReading(),
    ): CornerPass? {
        if (finished) return null
        val p = previous
        val s = Sample(along, tMicros, kmh, measured, fresh = p == null || abs(along - p.along) >= HeldMeters, reading)
        val first = !started
        previous = s
        started = true
        val jumped = p != null && (s.along - p.along > MaxGapMeters || s.along < p.along - MaxGapMeters)
        if (jumped) {
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
                if (inside.isEmpty()) entry = entryBefore(p.takeUnless { jumped }, s, first)
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

    /**
     * Where the window was entered, for [s] the first position inside it: the position before it, or
     * for the lap's [first] position in a window that starts at the line, the line itself.
     */
    private fun entryBefore(p: Sample?, s: Sample, first: Boolean): Sample? {
        val atLine = first && window.start <= 0.0 && s.along <= MaxGapMeters
        return p?.takeIf { it.along < window.start } ?: (line ?: s).takeIf { atLine }
    }

    /** The car left the path: what was measured so far is not one continuous drive through the window. */
    fun lost() {
        previous = null
        inside.clear()
        entry = null
    }

    /**
     * The lap ended: a window that ends at the line is measured if the car got there, since no later
     * position of this lap can be beyond it. Live, the line was crossed at [lineMicros], after the
     * last position fed in, at [lineKmh]; offline the trace's last position is on the line already.
     * A window that ends short of the line is timed by interpolating towards the line, not to it.
     */
    fun finish(lineMicros: Long? = null, lineKmh: Double = 0.0): CornerPass? {
        val e = entry.takeUnless { finished }
        finished = true
        val last = inside.lastOrNull()?.takeIf { window.endInclusive - it.along <= MaxGapMeters }
        val line = lineMicros?.takeIf { last != null && it >= last.tMicros }
            ?.let { Sample(lineAt, it, lineKmh) }
        return if (e == null || last == null) null else measure(e, line ?: last)
    }

    /**
     * The pass from [before] the window to [after] it, or null if the car stopped in it (waiting
     * past the line, a pit stop): that says nothing about how the corner was driven (FBBD-202).
     */
    private fun measure(before: Sample, after: Sample): CornerPass? {
        if (inside.any { it.kmh < StopKmh }) return null
        val t0 = timeAt(before, inside.first(), window.start)
        val t1 = timeAt(inside.last(), after, window.endInclusive)
        val v = DoubleArray(inside.size) { i ->
            val lo = maxOf(0, i - SmoothSamples)
            val hi = minOf(inside.size - 1, i + SmoothSamples)
            (lo..hi).sumOf { inside[it].kmh } / (hi - lo + 1)
        }
        val min = slowestBraked(v)
        // Smoothing rounds off the bottom; the apex is the slowest raw sample near the smoothed one.
        var apex = min
        for (i in maxOf(0, min - SmoothSamples)..minOf(v.size - 1, min + SmoothSamples)) {
            if (inside[i].kmh < inside[apex].kmh) apex = i
        }
        var peak = 0
        for (i in 0..min) if (v[i] > v[peak]) peak = i
        var brake = peak
        for (i in peak..min) if (v[i] >= v[peak] - PeakToleranceKmh) brake = i
        // How well the positions cover the window (FBBD-177), entering and leaving it included.
        val times = listOf(before.tMicros) + inside.filter { it.fresh }.map { it.tMicros } + after.tMicros
        val steps = times.zipWithNext { a, b -> b - a }.sorted()
        return CornerPass(
            corner = corner.number,
            lap = lap,
            brakeAt = inside[brake].along,
            apexAt = inside[apex].along,
            entryKmh = inside[brake].kmh,
            minKmh = inside[apex].kmh,
            timeMicros = (t1 - t0).roundToLong(),
            gapMicros = steps.last(),
            intervalMicros = steps[steps.size / 2],
            speedMeasured = inside.all { it.measured },
            apexOffsetMeters = inside[apex].reading.offsetMeters,
            pickupAt = pickup(pickupFrom(brake, apex)),
            apexMicros = timeTo(before, after, corner.apexAt)?.let { (it - t0).roundToLong() },
            steering = steering(),
            apexRpm = inside[apex].reading.rpm,
        )
    }

    /**
     * What the hands did through the window (FBBD-321): the wheel at each position that has it, the
     * bend of the car's own path there; null at a corner without a turn or without the wheel.
     */
    @Suppress("ReturnCount")
    private fun steering(): SteeringPass? {
        if (corner.turn == Turn.Straight) return null
        // From a little before braking: earlier in the window the wheel may still be leaving the corner before.
        val from = corner.brakeAt - SteerBeforeBrakeMeters
        val read = inside.filter {
            it.fresh && it.along >= from && it.reading.steerDeg != null && it.reading.point != null
        }
        if (read.size < 2) return null
        val points = read.map { it.reading.point!! }
        val along = DoubleArray(points.size)
        for (i in 1 until points.size) along[i] = along[i - 1] + (points[i] - points[i - 1]).length()
        val bends = bends(points, along)
        val samples = read.indices.map { SteerSample(read[it].along, read[it].reading.steerDeg!!, bends[it]) }
        return steeringPass(samples, corner.turn == Turn.Left)
    }

    /**
     * When the car passed [along] metres on the path, from [before] the window through the positions
     * inside it to [after] it; null if [along] is not between them.
     */
    private fun timeTo(before: Sample, after: Sample, along: Double): Double? =
        (listOf(before) + inside + after).zipWithNext().firstOrNull { (a, b) -> along >= a.along && along <= b.along }
            ?.let { (a, b) -> timeAt(a, b, along) }

    /**
     * The slowest point the car slowed into, at least [MinCornerDropKmh] below the fastest point
     * before it in the window (FBBD-202). A window entered slowly (getting going after the line)
     * is slowest at its start, which is not the corner. Without such a point, the slowest point.
     */
    private fun slowestBraked(v: DoubleArray): Int {
        var min = 0
        var braked = -1
        var fastest = v[0]
        for (i in v.indices) {
            fastest = maxOf(fastest, v[i])
            if (v[i] < v[min]) min = i
            if (fastest - v[i] >= MinCornerDropKmh && (braked < 0 || v[i] < v[braked])) braked = i
        }
        return if (braked >= 0) braked else min
    }

    /**
     * Where the pedal first rose, from index [from] on, to [PickupOnPct] after
     * being at or below [PickupOffPct], and stayed at or above [PickupOffPct] for [PickupHoldMicros]
     * (or to the end of the window), as the lap analysis reads a pickup; the crossing is interpolated
     * between the two positions around it.
     */
    /** Where to look for the pickup from: in a corner taken on a lift there is no braking to blip in. */
    private fun pickupFrom(brake: Int, apex: Int): Int = if (!corner.braked) {
        brake
    } else {
        (brake..apex).firstOrNull { inside[it].along >= inside[apex].along - PickupSearchBeforeApexMeters } ?: apex
    }

    @Suppress("ReturnCount")
    private fun pickup(from: Int): Double? {
        var lifted = false
        var start: Int? = null
        var at = 0.0
        for (i in from until inside.size) {
            val v = inside[i].reading.throttlePct
            if (v == null) {
                // No pedal here: nothing is bridged.
                start = null
                lifted = false
                continue
            }
            val s = start
            when {
                s != null && v < PickupOffPct -> {
                    if (inside[i].tMicros - inside[s].tMicros >= PickupHoldMicros) return at
                    start = null
                }
                s != null -> if (inside[i].tMicros - inside[s].tMicros >= PickupHoldMicros) return at
                !lifted -> lifted = v <= PickupOffPct
                v >= PickupOnPct -> {
                    start = i
                    val before = inside.getOrNull(i - 1)
                    at = crossing(before?.along, before?.reading?.throttlePct, inside[i].along, v)
                }
            }
        }
        return if (start != null) at else null
    }

    private fun timeAt(a: Sample, b: Sample, along: Double): Double {
        val span = b.along - a.along
        val u = if (span <= 0.0) 0.0 else ((along - a.along) / span).coerceIn(0.0, 1.0)
        return a.tMicros + u * (b.tMicros - a.tMicros)
    }
}

/**
 * Where the pedal crossed [PickupOnPct] between the position [fromAlong] (pedal [fromPct]) and the
 * one at [along] (pedal [pct]); [along] itself without the one before or when it was on already.
 */
private fun crossing(fromAlong: Double?, fromPct: Double?, along: Double, pct: Double): Double =
    if (fromAlong != null && fromPct != null && fromPct < PickupOnPct) {
        fromAlong + (along - fromAlong) * (PickupOnPct - fromPct) / (pct - fromPct)
    } else {
        along
    }
