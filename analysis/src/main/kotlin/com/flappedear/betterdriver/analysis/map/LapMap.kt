package com.flappedear.betterdriver.analysis.map

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.compare.LapComparison
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.Interpolation
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.TimingGateType
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.geo.hypot
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.nearestAxisIndex
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import kotlin.math.max
import kotlin.math.min

/** Most points kept of one lap's line on the map. */
const val MaxMapPoints = 1500

/** Most points kept of the track outline drawn under the lap. */
const val MaxOutlinePoints = 800

/** Time gained or lost per distance is measured over this much track, centred on each point. */
const val DeltaRateWindowMeters = 30.0

/** Time gained or lost is given per this many metres. */
const val DeltaRateMeters = 100.0

/** A corner's label points away from the middle of the chord this far either side of its middle. */
private const val CornerChordMeters = 15.0

/** A chord bulge shorter than this is a straight: the label goes to the left of travel. */
private const val MinimumBulgeMeters = 0.5

/**
 * One point of a lap's line on the map, in metres east and north of the session's axis origin
 * (a local equirectangular projection, so distances on the map are true metres).
 */
data class MapPoint(
    val eastMeters: Double,
    val northMeters: Double,
    /** Distance from the start/finish line on the session's axis. */
    val distanceMeters: Double,
    /** The speed channel as recorded (km/h for a BetterDriver recording); null without one. */
    val speed: Double?,
    /** The lap's time minus the reference lap's here (positive: behind); null without a reference. */
    val deltaSeconds: Double?,
    /**
     * Time lost here against the reference lap, seconds per [DeltaRateMeters] (negative: gained),
     * over [DeltaRateWindowMeters]; null without a reference.
     */
    val deltaRate: Double?,
)

/** A corner on the map: its name, where its label goes and which way is outside the turn. */
data class MapCorner(
    val name: String,
    val distanceMeters: Double,
    val anchor: MetricPoint,
    /** Unit vector from the corner toward the outside of the turn, where the label goes. */
    val outward: MetricPoint,
)

/**
 * One lap of a session on the map (FBBD-149): its GPS line in runs (a GPS gap starts a new run),
 * the track outline (the session's axis, from its fastest lap) and its corners. [referenceIndex] is
 * the lap the time deltas are against, or null when the points have none. [closed]: the outline is
 * a loop; on a point-to-point track (FBBD-155) it runs from the start to the finish.
 */
data class LapMap(
    val lapIndex: Int,
    val referenceIndex: Int?,
    val lengthMeters: Double,
    val runs: List<List<MapPoint>>,
    val outline: List<MetricPoint>,
    val corners: List<MapCorner>,
    val closed: Boolean = true,
)

/**
 * Timed lap [lapIndex] of [analysis] on the map, read from [session]. With [comparison] of that lap
 * against another, each point carries the time delta and the time gained or lost per distance.
 * Null when the session has no axis or no positions.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
fun lapMap(
    analysis: SessionAnalysis,
    session: TelemetrySession,
    lapIndex: Int,
    comparison: LapComparison? = null,
    maxPoints: Int = MaxMapPoints,
): LapMap? {
    val axis = analysis.axis ?: return null
    val trace = analysis.traces.getOrNull(lapIndex) ?: return null
    if (session.channel(Aliases.LATITUDE) == null || session.channel(Aliases.LONGITUDE) == null) return null
    val compared = comparison?.takeIf { it.otherIndex == lapIndex }
    val rates = compared?.let { deltaRates(it.delta) }.orEmpty()
    val total = trace.sumOf { it.samples.size }
    val stride = maxOf(1, (total + maxPoints - 1) / maxPoints)
    val runs = mutableListOf<List<MapPoint>>()
    var ordinal = 0
    for (segment in trace) {
        var run = mutableListOf<MapPoint>()
        for (sample in segment.samples) {
            if (ordinal++ % stride != 0 && sample !== segment.samples.last()) continue
            val position = if (sample.valid) positionAt(session, sample.telemetryTime, axis.origin) else null
            if (position == null) {
                if (run.isNotEmpty()) runs += run
                run = mutableListOf()
                continue
            }
            val d = sample.progressMeters
            run += MapPoint(
                position.eastMeters,
                position.northMeters,
                d,
                session.valueAt(Aliases.SPEED, sample.telemetryTime),
                compared?.let { interpolate(it.delta, d) },
                if (compared != null) interpolate(rates, d) else null,
            )
        }
        if (run.isNotEmpty()) runs += run
    }
    if (runs.isEmpty()) return null
    val pointToPoint = session.timingGates.any { it.type == TimingGateType.Finish }
    return LapMap(
        lapIndex,
        compared?.referenceIndex,
        axis.lengthMeters,
        runs,
        thin(if (pointToPoint) runOutline(analysis, axis) else axis.points, MaxOutlinePoints),
        mapCorners(analysis, axis),
        closed = !pointToPoint,
    )
}

/**
 * The part of [axis] its run covers, on a point-to-point track: the axis is a loop, so past the
 * finish it goes straight back across the map to the start, where no car drove.
 */
private fun runOutline(analysis: SessionAnalysis, axis: ProgressAxis): List<MetricPoint> {
    val run = analysis.laps.timedLaps.indexOfFirst { it.number == analysis.axisLapNumber }
    val end = analysis.traces.getOrNull(run).orEmpty()
        .flatMap { it.samples }
        .filter { it.valid }
        .maxOfOrNull { it.progressMeters }
        ?: return axis.points
    return axis.points.filterIndexed { i, _ -> i < axis.cumulative.size && axis.cumulative[i] <= end }
}

@Suppress("ReturnCount")
private fun positionAt(session: TelemetrySession, time: Double, origin: GeoCoordinate): MetricPoint? {
    val lat = session.valueAt(Aliases.LATITUDE, time) ?: return null
    val lon = session.valueAt(Aliases.LONGITUDE, time, Interpolation.Longitude) ?: return null
    return projectCoordinate(GeoCoordinate(lat, lon), origin)
}

private fun <T> thin(points: List<T>, maxPoints: Int): List<T> {
    if (points.size <= maxPoints) return points
    val stride = (points.size + maxPoints - 1) / maxPoints
    return points.filterIndexed { i, _ -> i % stride == 0 }
}

/** The corners of [analysis] on its [axis], each at the middle of its span. */
private fun mapCorners(analysis: SessionAnalysis, axis: ProgressAxis): List<MapCorner> {
    val length = axis.lengthMeters
    if (axis.points.isEmpty() || !(length > 0.0)) return emptyList()
    fun at(meters: Double) = axis.points[nearestAxisIndex(axis, meters)]
    return analysis.segments?.segments.orEmpty().filter { it.type == TrackSegmentType.Corner }.map { corner ->
        val span = (corner.endProgressMeters - corner.startProgressMeters).mod(length)
        val middle = (corner.startProgressMeters + span / 2).mod(length)
        val anchor = at(middle)
        val before = at(middle - CornerChordMeters)
        val after = at(middle + CornerChordMeters)
        MapCorner(corner.name, middle, anchor, outward(before, anchor, after))
    }
}

/**
 * Unit vector from the chord [before]–[after] to [apex]: the outside of the turn. On a straight,
 * the left of travel.
 */
internal fun outward(before: MetricPoint, apex: MetricPoint, after: MetricPoint): MetricPoint {
    val bulgeEast = apex.eastMeters - (before.eastMeters + after.eastMeters) / 2
    val bulgeNorth = apex.northMeters - (before.northMeters + after.northMeters) / 2
    val bulge = hypot(bulgeEast, bulgeNorth)
    if (bulge >= MinimumBulgeMeters) return MetricPoint(bulgeEast / bulge, bulgeNorth / bulge)
    val east = after.eastMeters - before.eastMeters
    val north = after.northMeters - before.northMeters
    val travel = hypot(east, north)
    return if (travel > 0.0) MetricPoint(-north / travel, east / travel) else MetricPoint(0.0, 1.0)
}

/**
 * Time lost per [DeltaRateMeters] along [delta] (runs of the delta by distance), over
 * [windowMeters] centred on each point and cut at the ends of its run. Negative where time is gained.
 */
fun deltaRates(delta: List<List<ChartPoint>>, windowMeters: Double = DeltaRateWindowMeters): List<List<ChartPoint>> =
    delta.map { run ->
        if (run.size < 2) return@map emptyList()
        val first = run.first().distanceMeters
        val last = run.last().distanceMeters
        run.mapNotNull { p ->
            val from = max(first, p.distanceMeters - windowMeters / 2)
            val to = min(last, p.distanceMeters + windowMeters / 2)
            val a = interpolate(listOf(run), from)
            val b = interpolate(listOf(run), to)
            if (a == null || b == null || to - from <= 0.0) {
                null
            } else {
                ChartPoint(p.distanceMeters, (b - a) / (to - from) * DeltaRateMeters)
            }
        }
    }.filter { it.isNotEmpty() }

/** The value of [runs] at [meters], linear within a run; null outside every run. */
@Suppress("ReturnCount")
fun interpolate(runs: List<List<ChartPoint>>, meters: Double): Double? {
    for (run in runs) {
        if (run.isEmpty() || meters < run.first().distanceMeters || meters > run.last().distanceMeters) continue
        var low = 0
        var high = run.size - 1
        while (high - low > 1) {
            val middle = (low + high) ushr 1
            if (run[middle].distanceMeters <= meters) low = middle else high = middle
        }
        val a = run[low]
        val b = run[high]
        val span = b.distanceMeters - a.distanceMeters
        return if (span <= 0.0) a.value else a.value + (b.value - a.value) * (meters - a.distanceMeters) / span
    }
    return null
}

/**
 * The point of [runs] nearest to ([east], [north]), or null when none is within [withinMeters].
 * For finding the point under a finger on the map.
 */
fun nearestMapPoint(
    runs: List<List<MapPoint>>,
    east: Double,
    north: Double,
    withinMeters: Double = Double.POSITIVE_INFINITY,
): MapPoint? {
    var best: MapPoint? = null
    var bestSquared = withinMeters * withinMeters
    for (run in runs) for (p in run) {
        val de = p.eastMeters - east
        val dn = p.northMeters - north
        val squared = de * de + dn * dn
        if (squared <= bestSquared) {
            best = p
            bestSquared = squared
        }
    }
    return best
}
