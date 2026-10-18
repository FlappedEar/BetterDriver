@file:Suppress("MagicNumber") // Made-up circuit geometry and driving.

package com.flappedear.betterdriver.timing.demo

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.fake.GnssScript
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// A made-up circuit driven lap after lap, so the track screen, lap timing and the coach can be tried
// without a saved track or a track day (Arek, 2026-10-18: "We need to do mockup to emulate track").
// Nothing here is a real place or a real recording: the layout is invented and placed in open
// country, and the driving comes from a simple grip model.

/** Where the demo circuit's start/finish line is: open country, nowhere anyone drives. */
private val Origin = GeoPoint(52.05, 21.0)

/** Grip of the made-up car: cornering, braking and accelerating, m/s². Top speed in m/s (198 km/h). */
private const val LateralGrip = 9.0
private const val Acceleration = 4.0
private const val TopSpeed = 55.0

/** The path is walked in steps of this many metres. */
private const val Step = 1.0

/** The car stands this far past the line at the start: the first lap is an out lap from standing. */
private const val StartMeters = 5.0

/**
 * One piece of the layout: a straight of [length] metres, or a bend of [radius] metres turning
 * [degrees] (positive to the left). A straight with [solved] 1 or 2 is one of the two lengths sized
 * so the loop closes.
 */
private class Piece(
    val length: Double = 0.0,
    val radius: Double = 0.0,
    val degrees: Double = 0.0,
    val solved: Int = 0,
) {
    val bend: Boolean get() = radius > 0
}

/**
 * The layout from the start/finish line, which sits in the middle of the 500 m main straight:
 * a 30 m hairpin, a right flick, a long left, a 90° left and a fast 100 m sweeper onto the straight.
 * Turns add up to 360°. The two unknown straights (the main straight's halves, and the one after
 * the flick) are solved so the path ends where it started.
 */
private val Layout = listOf(
    Piece(solved = 1),
    Piece(radius = 30.0, degrees = 150.0),
    Piece(length = 250.0),
    Piece(radius = 60.0, degrees = -60.0),
    Piece(solved = 2),
    Piece(radius = 80.0, degrees = 120.0),
    Piece(length = 400.0),
    Piece(radius = 40.0, degrees = 90.0),
    Piece(length = 250.0),
    Piece(radius = 100.0, degrees = 60.0),
    Piece(solved = 1),
)

/** The path every [Step] metres: position, compass heading, and the bend's radius and number (-1 on a straight). */
private class Path(val xy: List<Xy>, val heading: DoubleArray, val radius: DoubleArray, val corner: IntArray) {
    val size: Int get() = xy.size
    val length: Double get() = (size - 1) * Step
}

/** End point of walking [pieces] from the origin heading north, with [a] and [b] the unknown straights. */
private fun walk(a: Double, b: Double, out: MutableList<Pair<Xy, Piece>>? = null): Xy {
    var x = 0.0
    var y = 0.0
    var h = 90.0 // Maths angle: 90 is north.
    out?.add(Xy(x, y) to Layout.first())
    for (p in Layout) {
        if (p.bend) {
            val sign = if (p.degrees > 0) 1.0 else -1.0
            val cx = x - sign * p.radius * sin(Math.toRadians(h))
            val cy = y + sign * p.radius * cos(Math.toRadians(h))
            val n = ceil(abs(Math.toRadians(p.degrees)) * p.radius / Step).toInt()
            for (k in 1..n) {
                val hk = h + p.degrees * k / n
                x = cx + sign * p.radius * sin(Math.toRadians(hk))
                y = cy - sign * p.radius * cos(Math.toRadians(hk))
                out?.add(Xy(x, y) to p)
            }
            h += p.degrees
        } else {
            val length = when (p.solved) {
                1 -> a
                2 -> b
                else -> p.length
            }
            val n = ceil(length / Step).toInt()
            for (k in 1..n) {
                x += length / n * cos(Math.toRadians(h))
                y += length / n * sin(Math.toRadians(h))
                out?.add(Xy(x, y) to p)
            }
        }
    }
    return Xy(x, y)
}

/** The layout's path, with its unknown straights solved so it closes. */
private val TheCircuit: Path by lazy {
    // The end point is linear in the two unknown lengths: solve end = 0 for them.
    val e0 = walk(0.0, 0.0)
    val ea = walk(1.0, 0.0) - e0
    val eb = walk(0.0, 1.0) - e0
    val det = ea.x * eb.y - ea.y * eb.x
    val a = (-e0.x * eb.y + e0.y * eb.x) / det
    val b = (-ea.x * e0.y + ea.y * e0.x) / det
    val points = mutableListOf<Pair<Xy, Piece>>()
    walk(a, b, points)
    // Resample evenly: the solved straights are not whole metres.
    val raw = points.map { it.first }
    val along = DoubleArray(raw.size)
    for (i in 1 until raw.size) along[i] = along[i - 1] + (raw[i] - raw[i - 1]).length()
    val n = (along.last() / Step).toInt()
    val bends = Layout.filter { it.bend }
    val xy = ArrayList<Xy>(n + 1)
    val radius = DoubleArray(n + 1)
    val corner = IntArray(n + 1)
    var j = 0
    for (i in 0..n) {
        val s = minOf(i * Step, along.last())
        while (j < raw.size - 2 && along[j + 1] < s) j++
        val f = ((s - along[j]) / (along[j + 1] - along[j])).coerceIn(0.0, 1.0)
        xy += raw[j] + (raw[j + 1] - raw[j]) * f
        val piece = points[j + 1].second
        radius[i] = if (piece.bend) piece.radius else Double.POSITIVE_INFINITY
        corner[i] = bends.indexOf(piece)
    }
    xy[n] = raw.first() // Closed: the last point is the first.
    val heading = DoubleArray(n + 1) { i ->
        val d = xy[min(i + 1, n)] - xy[if (i == n) n - 1 else i]
        (Math.toDegrees(atan2(d.x, d.y)) + 360.0) % 360.0
    }
    Path(xy, heading, radius, corner)
}

/** One lap's driving: time from the line and speed at each point of the path. */
private class Lap(val startSeconds: Double, val seconds: DoubleArray, val speed: DoubleArray) {
    val endSeconds: Double get() = startSeconds + seconds.last()
}

/**
 * Made-up GNSS of a car driving the demo circuit ([track]) lap after lap, [rateHz] fixes a second
 * from [startUtcMillis]. It starts standing just past the line, so the first lap is an out lap. Each
 * lap is driven a little differently: every corner taken at 90 to 100 % of the grip and braked for
 * at 7 to 9 m/s², from [seed], so the coach finds corners to work on and lap times vary by a few
 * tenths. Fix n is always the same, as [GnssScript] promises.
 */
class DemoCircuit(
    override val startUtcMillis: Long,
    override val rateHz: Int = 25,
    private val seed: Long = 1L,
) : GnssScript {
    private val path = TheCircuit
    private val frame = LocalFrame(Origin)
    private val laps = mutableListOf<Lap>()

    /** Laps driven up to [seconds] after the start, the last one holding it. */
    private fun lapAt(seconds: Double): Lap {
        if (laps.isEmpty()) laps += drive(0, 0.0, 0.0)
        while (laps.last().endSeconds <= seconds) {
            val last = laps.last()
            laps += drive(laps.size, last.endSeconds, last.speed.last())
        }
        // Fixes are asked for in order, so the lap is the last or near it.
        return laps.last { it.startSeconds <= seconds }
    }

    /** Lap [k] from the line, entered at [entry] m/s at [start] s; the first lap starts standing past the line. */
    private fun drive(k: Int, start: Double, entry: Double): Lap {
        val random = Random(seed * 1_000_003L + k)
        val bends = Layout.count { it.bend }
        val grip = DoubleArray(bends) { if (k == 0) 0.8 else 0.9 + random.nextDouble() * 0.1 }
        val braking = if (k == 0) 6.0 else 7.0 + random.nextDouble() * 2.0
        val n = path.size
        val cap = DoubleArray(n) { i ->
            val c = path.corner[i]
            if (c < 0) TopSpeed else min(TopSpeed, sqrt(LateralGrip * grip[c] * path.radius[i]))
        }
        val first = if (k == 0) (StartMeters / Step).toInt() else 0
        val v = DoubleArray(n)
        v[first] = min(entry, cap[first])
        for (i in first + 1 until n) v[i] = min(cap[i], sqrt(v[i - 1] * v[i - 1] + 2 * Acceleration * Step))
        for (i in n - 2 downTo first + 1) v[i] = min(v[i], sqrt(v[i + 1] * v[i + 1] + 2 * braking * Step))
        val t = DoubleArray(n)
        for (i in first + 1 until n) t[i] = t[i - 1] + 2 * Step / maxOf(v[i - 1] + v[i], 0.1)
        // Before a standing start the car is at its start point: same time and no speed.
        for (i in 0 until first) t[i] = 0.0
        return Lap(start, t, v)
    }

    override fun fix(n: Long): List<ChannelValue> {
        val at = n.toDouble() / rateHz
        val lap = lapAt(at)
        val local = at - lap.startSeconds
        val t = lap.seconds
        var i = t.binarySearch(local).let { if (it >= 0) it else -it - 2 }.coerceIn(0, t.size - 2)
        while (i < t.size - 2 && t[i + 1] <= t[i]) i++ // Past the standing start's equal times.
        val f = if (t[i + 1] > t[i]) ((local - t[i]) / (t[i + 1] - t[i])).coerceIn(0.0, 1.0) else 0.0
        val p = path.xy[i] + (path.xy[i + 1] - path.xy[i]) * f
        val geo = frame.toGeo(p)
        val kmh = (lap.speed[i] + (lap.speed[i + 1] - lap.speed[i]) * f) * 3.6
        return listOf(
            ChannelValue(GnssChannels.UTC_MS.id, utcMillis(n).toDouble()),
            ChannelValue(GnssChannels.LAT.id, geo.lat),
            ChannelValue(GnssChannels.LON.id, geo.lon),
            ChannelValue(GnssChannels.SPEED_KMH.id, kmh),
            ChannelValue(GnssChannels.HEADING.id, path.heading[i]),
            ChannelValue(GnssChannels.ALT_M.id, 100.0),
            ChannelValue(GnssChannels.SATS.id, 16.0),
        )
    }

    companion object {
        /** The saved track's id; a track Arek edits keeps it, so the demo never overwrites his lines. */
        const val TrackId = "demo-circuit"

        /** The circuit's length, metres. */
        val lengthMeters: Double get() = TheCircuit.length

        /**
         * The demo circuit as a saved track: the start/finish line in the middle of the main straight,
         * and two sector lines on the straights after the flick and after the long left.
         */
        val track: Track by lazy {
            val path = TheCircuit
            val frame = LocalFrame(Origin)
            fun line(i: Int) = TrackLine.across(frame.toGeo(path.xy[i]), path.heading[i])
            // The middles of the straights after bend 1 (the flick) and bend 2 (the long left).
            fun straightAfter(bend: Int): Int {
                val from = path.corner.indexOfLast { it == bend } + 1
                val to = (from until path.size).first { path.corner[it] >= 0 }
                return (from + to) / 2
            }
            Track(
                id = TrackId,
                name = "Demo circuit",
                center = Origin,
                startFinish = line(0),
                sectorLines = listOf(line(straightAfter(1)), line(straightAfter(2))),
            )
        }
    }
}
