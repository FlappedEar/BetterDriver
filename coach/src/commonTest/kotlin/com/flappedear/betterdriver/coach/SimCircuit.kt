@file:Suppress("MagicNumber", "ReturnCount") // Test geometry and simulation.

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.math.toDegrees
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A made-up circuit near Poznań: a 600 m by 300 m rectangle with 40 m radius corners, driven
 * anticlockwise, start/finish in the middle of the bottom straight. Four braking corners, one per bend,
 * with their apex in the middle of each bend.
 *
 * The driver brakes at [BrakeMps2] down to each corner's minimum speed, holds it to the apex, then
 * accelerates at [AccelMps2] up to [TopKmh]. A [Pace] moves the braking point earlier (coasting at
 * the minimum speed from there to the apex) or changes the minimum speed, corner by corner.
 */
class SimCircuit(val origin: GeoPoint = GeoPoint(52.40, 16.80)) {
    val frame = LocalFrame(origin)
    private val w = 600.0
    private val h = 300.0
    private val r = 40.0
    private val sx = w - 2 * r
    private val sy = h - 2 * r
    val arc = PI * r / 2
    val length = 2 * sx + 2 * sy + 4 * arc

    /** Distance from the line to each apex. */
    val apexes: List<Double> = listOf(
        sx / 2 + arc / 2,
        sx / 2 + arc + sy + arc / 2,
        sx / 2 + 2 * arc + sy + sx + arc / 2,
        sx / 2 + 3 * arc + 2 * sy + sx + arc / 2,
    )

    val minKmh = listOf(80.0, 70.0, 85.0, 75.0)

    /** Driver choices per corner (index 0..3): braking [earlierM] metres early, apex speed changed by [kmh]. */
    data class Pace(val earlierM: List<Double> = List(4) { 0.0 }, val kmh: List<Double> = List(4) { 0.0 })

    /** Position at [s] metres from the start/finish line. */
    fun xy(s: Double): Xy {
        var d = ((s % length) + length) % length
        // Segments anticlockwise from the middle of the bottom straight (y = -h/2).
        val x0 = -sx / 2
        val y0 = -h / 2
        val segs = listOf(sx / 2, arc, sy, arc, sx, arc, sy, arc, sx / 2)
        var i = 0
        while (i < segs.size - 1 && d > segs[i]) {
            d -= segs[i]
            i++
        }
        return when (i) {
            0 -> Xy(d, y0)
            1 -> corner(Xy(sx / 2, y0 + r), -PI / 2, d)
            2 -> Xy(w / 2, y0 + r + d)
            3 -> corner(Xy(sx / 2, h / 2 - r), 0.0, d)
            4 -> Xy(sx / 2 - d, h / 2)
            5 -> corner(Xy(x0, h / 2 - r), PI / 2, d)
            6 -> Xy(-w / 2, h / 2 - r - d)
            7 -> corner(Xy(x0, y0 + r), PI, d)
            else -> Xy(x0 + d, y0)
        }
    }

    private fun corner(c: Xy, start: Double, d: Double): Xy {
        val a = start + d / r
        return Xy(c.x + r * cos(a), c.y + r * sin(a))
    }

    fun headingDeg(s: Double): Double {
        val a = xy(s)
        val b = xy(s + 0.5)
        return (toDegrees(atan2(b.x - a.x, b.y - a.y)) + 360) % 360
    }

    fun track() = Track(
        id = "sim",
        name = "Sim circuit",
        center = origin,
        startFinish = TrackLine.across(frame.toGeo(xy(0.0)), headingDeg(0.0)),
    )

    /** Speed in m/s at [s] (any lap distance, wraps) for [pace]. */
    fun speed(s: Double, pace: Pace): Double {
        val d = ((s % length) + length) % length
        var v = TopKmh / 3.6
        for (k in apexes.indices) {
            val vmin = (minKmh[k] + pace.kmh[k]) / 3.6
            for (lapShift in listOf(-length, 0.0, length)) {
                val apex = apexes[k] + lapShift
                val brakeEnd = apex - pace.earlierM[k]
                v = when {
                    d < brakeEnd -> min(v, sqrt(vmin * vmin + 2 * BrakeMps2 * (brakeEnd - d)))
                    d <= apex -> min(v, vmin)
                    else -> min(v, sqrt(vmin * vmin + 2 * AccelMps2 * (d - apex)))
                }
            }
        }
        return v
    }

    /** Where braking for corner [k] starts with [pace]: the braking curve meets the acceleration curve. */
    fun brakePoint(k: Int, pace: Pace): Double {
        var s = apexes[k] - pace.earlierM[k] - 1.0
        while (speed(s - 0.1, pace) > speed(s, pace) + 1e-9) s -= 0.1
        return ((s % length) + length) % length
    }

    /**
     * Drives one lap per entry of [paces], starting [startBefore] metres before the line, then [after]
     * metres. Fixes at [rateHz] with the speed in km/h. Returns the fixes and, for each fix, the distance
     * driven since the first line crossing (negative before it).
     */
    fun drive(
        paces: List<Pace>,
        rateHz: Int = 25,
        startBefore: Double = 100.0,
        after: Double = 150.0,
        startMicros: Long = 1_000_000L,
    ): Drive {
        val dt = 1e-3
        var s = -startBefore
        var t = 0.0
        var nextFix = 0.0
        val fixes = mutableListOf<CoachFix>()
        val at = mutableListOf<Double>()
        val end = paces.size * length + after
        while (s < end) {
            val pace = paces[(maxOf(s, 0.0) / length).toInt().coerceIn(0, paces.size - 1)]
            val v = speed(s, pace)
            if (t >= nextFix - 1e-9) {
                val g = frame.toGeo(xy(s))
                fixes += CoachFix(startMicros + (t * 1e6).roundToLong(), g.lat, g.lon, v * 3.6)
                at += s
                nextFix += 1.0 / rateHz
            }
            s += v * dt
            t += dt
        }
        return Drive(fixes, at)
    }

    class Drive(val fixes: List<CoachFix>, val distance: List<Double>)

    companion object {
        const val TopKmh = 170.0
        const val BrakeMps2 = 9.0
        const val AccelMps2 = 3.5
    }
}
