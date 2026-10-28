package com.flappedear.betterdriver.analysis.map

import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.Interpolation
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.geo.hypot
import com.flappedear.betterdriver.analysis.geo.projectCoordinate

/** Most points kept of a whole drive's line on the map. */
const val MaxDrivePoints = 3000

/** Below this speed the car stands: GPS wander adds no distance and no moving time. */
const val StandingKmh = 3.0

/** A gap between fixes is a GPS gap from this long, however fast the receiver reports. */
private const val MinimumGapSeconds = 2.0

private const val KmhPerMps = 3.6

/** Without a speed channel, a fix this close to where distance was last counted is GPS wander. */
private const val WanderMeters = 5.0

/** The time [WanderMeters] takes at [StandingKmh]: longer, and the car was standing. */
private const val StandingSeconds = WanderMeters / (StandingKmh / KmhPerMps)

private fun metersBetween(a: MetricPoint, b: MetricPoint) =
    hypot(b.eastMeters - a.eastMeters, b.northMeters - a.northMeters)

/**
 * A whole session on the map with no track or laps (FBBD-342), e.g. a road drive: [map] is every GPS
 * fix as a line (a GPS gap starts a new run), its distance along the driven path from the first fix,
 * with no outline or corners; [speed] is the speed (km/h) by that distance, in the same runs.
 * [distanceMeters] is the driven distance (a gap adds the straight line across it), [durationSeconds]
 * the first fix to the last, [movingSeconds] the time above [StandingKmh] without gaps,
 * [averageMovingKmh] the distance driven in that time over it (null when the car never moved),
 * [topSpeedKmh] the highest speed (null without a speed channel).
 */
data class DriveMap(
    val map: LapMap,
    val speed: List<List<ChartPoint>>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val movingSeconds: Double,
    val averageMovingKmh: Double?,
    val topSpeedKmh: Double?,
)

/**
 * [session] as one drive on the map (see [DriveMap]), at most [maxPoints] points drawn; distance and
 * the figures use every fix. Null without at least two positions.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
fun driveMap(session: TelemetrySession, maxPoints: Int = MaxDrivePoints): DriveMap? {
    val lat = session.channel(Aliases.LATITUDE) ?: return null
    if (session.channel(Aliases.LONGITUDE) == null || lat.sampleCount < 2) return null
    val gap = lat.gapThreshold(MinimumGapSeconds)
    class Fix(val time: Double, val position: GeoCoordinate, val speed: Double?)
    val fixes = (0 until lat.sampleCount).mapNotNull { i ->
        val time = lat.timestamps[i]
        val la = lat.values[i].takeIf { it.isFinite() } ?: return@mapNotNull null
        val lo = session.valueAt(Aliases.LONGITUDE, time, Interpolation.Longitude) ?: return@mapNotNull null
        // A receiver without a fix reports 0, 0: never a place anyone drives.
        if (la == 0.0 && lo == 0.0) return@mapNotNull null
        Fix(time, GeoCoordinate(la, lo), session.valueAt(Aliases.SPEED, time)?.takeIf { it.isFinite() })
    }
    if (fixes.size < 2) return null
    val origin = fixes.first().position
    val stride = maxOf(1, (fixes.size + maxPoints - 1) / maxPoints)
    val runs = mutableListOf<List<MapPoint>>()
    var run = mutableListOf<MapPoint>()
    var distance = 0.0
    var moving = 0.0
    var movingDistance = 0.0
    var previous: Fix? = null
    var previousAt = MetricPoint(0.0, 0.0)
    // Without a speed, distance is counted from the last fix it was counted at, once the car is
    // [WanderMeters] from it: a standing fix's wander adds nothing.
    var anchor = MetricPoint(0.0, 0.0)
    var anchorTime = 0.0
    for ((i, fix) in fixes.withIndex()) {
        val at = projectCoordinate(fix.position, origin)
        val before = previous
        val broken = before != null && fix.time - before.time > gap
        if (before == null || broken) {
            // A gap adds the straight line across it, to neither the moving time nor its distance.
            if (before != null) distance += metersBetween(previousAt, at)
            anchor = at
            anchorTime = fix.time
        } else if (fix.speed != null) {
            val step = metersBetween(previousAt, at)
            if (fix.speed >= StandingKmh) {
                distance += step
                moving += fix.time - before.time
                movingDistance += step
            }
            anchor = at
            anchorTime = fix.time
        } else {
            val step = metersBetween(anchor, at)
            if (step >= WanderMeters) {
                distance += step
                moving += fix.time - anchorTime
                movingDistance += step
                anchor = at
                anchorTime = fix.time
            } else if (fix.time - anchorTime > StandingSeconds) {
                // Slower than standing pace since the anchor: the car stands, and its time is no
                // moving time. A drive away counts at most this much standing time.
                anchorTime = before.time
            }
        }
        if (broken && run.isNotEmpty()) {
            runs += run
            run = mutableListOf()
        }
        if (i % stride == 0 || i == fixes.lastIndex || broken) {
            run += MapPoint(at.eastMeters, at.northMeters, distance, fix.speed, null, null)
        }
        previous = fix
        previousAt = at
    }
    if (run.isNotEmpty()) runs += run
    val speed = runs.map { r -> r.mapNotNull { p -> p.speed?.let { ChartPoint(p.distanceMeters, it) } } }
        .filter { it.isNotEmpty() }
    return DriveMap(
        map = LapMap(
            lapIndex = -1,
            referenceIndex = null,
            lengthMeters = distance,
            runs = runs,
            outline = emptyList(),
            corners = emptyList(),
            closed = false,
        ),
        speed = speed,
        distanceMeters = distance,
        durationSeconds = fixes.last().time - fixes.first().time,
        movingSeconds = moving,
        averageMovingKmh = if (moving > 0.0) movingDistance / moving * KmhPerMps else null,
        topSpeedKmh = fixes.mapNotNull { it.speed }.maxOrNull(),
    )
}
