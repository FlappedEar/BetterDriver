package com.flappedear.betterdriver.analysis.line

import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.optimal.PedalState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// The perfect lap in 3D (FBBD-308, UI review round 44): the line plan's perfect lap driven as a car
// along its line, coloured by what the pedals do (UI-182 A), with the best lap as a see-through
// car driving at its own speed beside it (UI-183 C). Since round 47 (FBBD-316) it is followed from
// behind and above the car (UI-200 A), over a satellite photo (UI-201 B). It reads the plan and
// works out no target of its own, so it shows what the Map tab and the coach say.

/** The road the laps used is this much narrower than the road; it is drawn this much wider each side. */
const val SceneRoadMarginMeters = 2.0

/** One point of a scene line, in metres east and north of the scene origin, at its time on the lap. */
data class ScenePoint(
    /** Along the lap from the start of the scene's first corner window, with the start/finish line unwrapped. */
    val lapMeters: Double,
    val eastMeters: Double,
    val northMeters: Double,
    val speedKmh: Double?,
    val pedal: PedalState?,
    /** From the start of the scene's first corner window. */
    val seconds: Double,
)

/** One corner of the scene: where its piece of each line is, and the road the laps used there. */
data class SceneCorner(
    val name: String,
    /**
     * Where the perfect lap's piece through this corner comes from: the session's label (empty
     * for the analysed session) and lap.
     */
    val sessionLabel: String,
    val lapNumber: Int,
    /** Time to find here, as the Map tab says it. */
    val gainSeconds: Double,
    /** When the perfect lap enters and leaves this corner's window. */
    val startSeconds: Double,
    val endSeconds: Double,
    /** Road edges, left of travel then right, already widened by [SceneRoadMarginMeters]. */
    val left: List<Pair<Double, Double>>,
    val right: List<Pair<Double, Double>>,
)

/** Where the camera is and what it looks at, metres east, north and up. */
data class CameraPose(val eye: Vec3, val target: Vec3) {
    fun lerp(o: CameraPose, f: Double) = CameraPose(eye.lerp(o.eye, f), target.lerp(o.target, f))
}

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun lerp(o: Vec3, f: Double) = Vec3(x + (o.x - x) * f, y + (o.y - y) * f, z + (o.z - z) * f)
    val length: Double get() = sqrt(dot(this))
}

/** Where a car is at one moment: position, heading (radians from east, counter-clockwise) and speed. */
data class CarAt(
    val eastMeters: Double,
    val northMeters: Double,
    val heading: Double,
    val speedKmh: Double?,
    val lapMeters: Double,
)

/**
 * The perfect lap ([perfect]) and the best lap ([best]) as two timed lines over the same lap,
 * with the road and the corners. [perfectSeconds] is the plan's perfect lap time; [bestSeconds]
 * the best lap's through the same windows.
 */
data class PerfectLapScene(
    val perfect: List<ScenePoint>,
    val best: List<ScenePoint>,
    val corners: List<SceneCorner>,
    val perfectSeconds: Double,
    val bestSeconds: Double,
    val bestLapNumber: Int,
    /** Where east and north are measured from, for the map under the road. */
    val origin: GeoCoordinate,
) {
    /** The scene plays this long: the perfect lap. */
    val durationSeconds: Double get() = perfect.last().seconds

    /** The perfect lap's car at [seconds]. */
    fun perfectAt(seconds: Double): CarAt = carAt(perfect, seconds)

    /** His best lap's car at [seconds], driving at its own speed from the same start. */
    fun bestAt(seconds: Double): CarAt = carAt(best, seconds)

    /** How far the best lap is behind the perfect lap's car at [seconds]: positive is behind. */
    fun gapAt(seconds: Double): Double {
        val at = perfectAt(seconds).lapMeters
        return timeAtMeters(best, at) - timeAtMeters(perfect, at)
    }

    /** The corner whose window the perfect lap is in at [seconds]. */
    fun cornerAt(seconds: Double): Int {
        val i = corners.indexOfFirst { seconds < it.endSeconds }
        return if (i < 0) corners.lastIndex else i
    }

    /**
     * Where the camera is at [seconds]: behind and above the perfect lap's car, looking ahead of it
     * (UI-200 A), so the car, its line on the road and the next corner are in one picture. It
     * faces the way the line goes over the next and last few metres, so it turns smoothly.
     */
    fun cameraAt(seconds: Double): CameraPose {
        val car = perfectAt(seconds)
        val back = pointAtMeters(perfect, car.lapMeters - CameraLookBackMeters)
        val ahead = pointAtMeters(perfect, car.lapMeters + CameraLookAheadMeters)
        var dx = ahead.first - back.first
        var dy = ahead.second - back.second
        val l = hypot(dx, dy)
        if (l < 1.0) {
            dx = cos(car.heading)
            dy = sin(car.heading)
        } else {
            dx /= l
            dy /= l
        }
        val eye = Vec3(
            car.eastMeters - dx * CameraBehindMeters,
            car.northMeters - dy * CameraBehindMeters,
            CameraUpMeters,
        )
        val target = Vec3(car.eastMeters + dx * CameraAimMeters, car.northMeters + dy * CameraAimMeters, 0.0)
        return CameraPose(eye, target)
    }

    companion object {
        /** Horizontal field of view of the scene's camera. */
        const val CameraFovDegrees = 62.0

        /** How far behind the car and how high the camera is, and how far ahead of the car it looks, metres. */
        const val CameraBehindMeters = 34.0
        const val CameraUpMeters = 22.0
        const val CameraAimMeters = 16.0

        private const val CameraLookBackMeters = 10.0
        private const val CameraLookAheadMeters = 20.0
    }
}

/**
 * The scene of [plan]'s perfect lap, or null when a corner has no line or road to draw. Each
 * corner's piece takes the time the plan gives it (the best lap's time there less the time to
 * find), spread along the piece by its own speeds, so the lap time is the plan's perfect lap.
 */
@Suppress("ReturnCount")
fun perfectLapScene(plan: LinePlan): PerfectLapScene? {
    val drivable = plan.corners.all {
        min(it.target.line.size, it.reference.line.size) >= MinimumPoints &&
            min(it.roadLeft.size, it.roadRight.size) >= MinimumPoints
    }
    if (plan.corners.isEmpty() || !drivable) return null
    val first = plan.corners.first().target.line.first()
    val origin = GeoCoordinate(first.latitude, first.longitude)
    fun xy(lat: Double, lon: Double) = projectCoordinate(GeoCoordinate(lat, lon), origin)
    val perfect = timedLine(plan.corners.map { it.target.line to targetSeconds(it) }, ::xy)
    val best = timedLine(plan.corners.map { it.reference.line to it.reference.seconds }, ::xy)
    var start = 0.0
    val corners = plan.corners.map { c ->
        val end = start + targetSeconds(c)
        val n = min(c.roadLeft.size, c.roadRight.size)
        val l = c.roadLeft.take(n).map { xy(it.latitude, it.longitude) }
        val r = c.roadRight.take(n).map { xy(it.latitude, it.longitude) }
        fun widen(a: MetricPoint, b: MetricPoint): Pair<Double, Double> {
            val d = hypot(a.eastMeters - b.eastMeters, a.northMeters - b.northMeters).takeIf { it > 0.0 } ?: 1.0
            return a.eastMeters + (a.eastMeters - b.eastMeters) / d * SceneRoadMarginMeters to
                a.northMeters + (a.northMeters - b.northMeters) / d * SceneRoadMarginMeters
        }
        SceneCorner(
            name = c.name,
            sessionLabel = c.target.sessionLabel,
            lapNumber = c.target.lapNumber,
            gainSeconds = c.gainSeconds,
            startSeconds = start,
            endSeconds = end,
            left = l.indices.map { widen(l[it], r[it]) },
            right = r.indices.map { widen(r[it], l[it]) },
        ).also { start = end }
    }
    return PerfectLapScene(
        perfect = perfect,
        best = best,
        corners = corners,
        perfectSeconds = plan.perfectLap().seconds,
        bestSeconds = best.last().seconds,
        bestLapNumber = plan.referenceLapNumber,
        origin = origin,
    )
}

/** The perfect lap's time through [c]'s window: the best lap's there less the time to find. */
private fun targetSeconds(c: PlanCorner) = c.reference.seconds - c.gainSeconds

/** The pieces joined into one line, each taking its time, spread along it by its own speeds. */
@Suppress("NestedBlockDepth") // One pass over each piece's points, in two steps.
private fun timedLine(
    pieces: List<Pair<List<PlanPoint>, Double>>,
    xy: (Double, Double) -> MetricPoint,
): List<ScenePoint> {
    val out = mutableListOf<ScenePoint>()
    var lapOffset = 0.0
    var lastProgress: Double? = null
    var t0 = 0.0
    for ((line, seconds) in pieces) {
        val pts = line.dropWhile { p -> lastProgress?.let { abs(it - p.progressMeters) < SameMeters } == true }
        if (pts.isEmpty()) continue
        // Each step's share of the piece's time, from its length and the speeds at its two ends.
        val prev = out.lastOrNull()
        val metric = pts.map { xy(it.latitude, it.longitude) }
        val steps = DoubleArray(pts.size)
        for (i in pts.indices) {
            val (ax, ay, av) = if (i == 0) {
                if (prev == null) Triple(metric[0].eastMeters, metric[0].northMeters, pts[0].speedKmh)
                else Triple(prev.eastMeters, prev.northMeters, prev.speedKmh)
            } else {
                Triple(metric[i - 1].eastMeters, metric[i - 1].northMeters, pts[i - 1].speedKmh)
            }
            val len = hypot(metric[i].eastMeters - ax, metric[i].northMeters - ay)
            val v = listOfNotNull(av, pts[i].speedKmh).average().takeIf { !it.isNaN() && it > MinimumKmh } ?: MinimumKmh
            steps[i] = len / (v / KmhPerMs)
        }
        val total = steps.sum()
        val scale = if (total > 0) seconds / total else 0.0
        var t = t0
        for (i in pts.indices) {
            val p = pts[i]
            lastProgress?.let { if (p.progressMeters < it - WrapMeters) lapOffset += it }
            lastProgress = p.progressMeters
            t += steps[i] * scale
            val m = metric[i]
            out += ScenePoint(lapOffset + p.progressMeters, m.eastMeters, m.northMeters, p.speedKmh, p.pedal, t)
        }
        t0 += seconds
        // Keep the piece's end exactly where the plan's time says, whatever rounding did.
        out[out.lastIndex] = out.last().copy(seconds = t0)
    }
    // The line starts at time 0 at its first point.
    val s0 = out.first().seconds
    return out.map { it.copy(seconds = it.seconds - s0) }
}

private fun carAt(line: List<ScenePoint>, seconds: Double): CarAt {
    val i = line.indexOfFirst { it.seconds >= seconds }.let { if (it < 0) line.lastIndex else max(it, 1) }
    val a = line[i - 1]
    val b = line[i]
    val f = if (b.seconds > a.seconds) ((seconds - a.seconds) / (b.seconds - a.seconds)).coerceIn(0.0, 1.0) else 1.0
    val heading = atan2(b.northMeters - a.northMeters, b.eastMeters - a.eastMeters)
    val v = if (a.speedKmh != null && b.speedKmh != null) a.speedKmh + (b.speedKmh - a.speedKmh) * f else b.speedKmh
    return CarAt(
        a.eastMeters + (b.eastMeters - a.eastMeters) * f,
        a.northMeters + (b.northMeters - a.northMeters) * f,
        heading,
        v,
        a.lapMeters + (b.lapMeters - a.lapMeters) * f,
    )
}

private fun timeAtMeters(line: List<ScenePoint>, meters: Double): Double {
    val i = line.indexOfFirst { it.lapMeters >= meters }.let { if (it < 0) line.lastIndex else max(it, 1) }
    val a = line[i - 1]
    val b = line[i]
    val span = b.lapMeters - a.lapMeters
    val f = if (span > 0) ((meters - a.lapMeters) / span).coerceIn(0.0, 1.0) else 1.0
    return a.seconds + (b.seconds - a.seconds) * f
}

/** A line or road edge needs this many points to be drawn. */
private const val MinimumPoints = 2

/** Points this close along the lap are the same point, where two corner windows meet. */
private const val SameMeters = 0.01

/** A drop in progress this big is the start/finish line, not noise. */
private const val WrapMeters = 100.0

/** Speeds below this are taken as this, so a step never takes forever. */
private const val MinimumKmh = 5.0

private const val KmhPerMs = 3.6

/** East and north of the point [meters] along [line], held at its ends. */
private fun pointAtMeters(line: List<ScenePoint>, meters: Double): Pair<Double, Double> {
    val i = line.indexOfFirst { it.lapMeters >= meters }.let { if (it < 0) line.lastIndex else max(it, 1) }
    val a = line[i - 1]
    val b = line[i]
    val span = b.lapMeters - a.lapMeters
    val f = if (span > 0) ((meters - a.lapMeters) / span).coerceIn(0.0, 1.0) else 1.0
    return a.eastMeters + (b.eastMeters - a.eastMeters) * f to a.northMeters + (b.northMeters - a.northMeters) * f
}
