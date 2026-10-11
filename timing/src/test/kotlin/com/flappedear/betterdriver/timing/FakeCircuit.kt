@file:Suppress("MagicNumber", "ReturnCount", "LongParameterList", "NestedBlockDepth") // Test geometry and simulation.

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * A made-up stadium circuit (two 400 m straights, two 100 m radius bends, driven anticlockwise)
 * near Poznań, and a driver whose speed changes all the time, so line crossings never fall on a
 * fix and the car is always accelerating or braking. The drive is simulated in 0.1 ms steps, which
 * gives the true crossing times; fixes are taken from it at [rateHz].
 */
class FakeCircuit(val origin: GeoPoint = GeoPoint(52.40, 16.80)) {
    val frame = LocalFrame(origin)
    val straight = 400.0
    val radius = 100.0
    val length = 2 * straight + 2 * PI * radius

    /** Position at [s] metres from the start/finish line (middle of the bottom straight). */
    fun xy(s: Double): Xy {
        var d = ((s % length) + length) % length
        val half = straight / 2
        if (d < half) return Xy(d, -radius)
        d -= half
        val bend = PI * radius
        if (d < bend) {
            val a = -PI / 2 + d / radius
            return Xy(half + radius * cos(a), radius * sin(a))
        }
        d -= bend
        if (d < straight) return Xy(half - d, radius)
        d -= straight
        if (d < bend) {
            val a = PI / 2 + d / radius
            return Xy(-half + radius * cos(a), radius * sin(a))
        }
        d -= bend
        return Xy(-half + d, -radius)
    }

    /** Heading in degrees clockwise from north at [s]. */
    fun headingDeg(s: Double): Double {
        val a = xy(s)
        val b = xy(s + 0.5)
        val deg = Math.toDegrees(atan2(b.x - a.x, b.y - a.y))
        return (deg + 360) % 360
    }

    fun geo(s: Double): GeoPoint = frame.toGeo(xy(s))

    fun lineAt(s: Double, width: Double = 30.0) = TrackLine.across(geo(s), headingDeg(s), width)

    /** Start/finish at 0 and sector lines at a third and two thirds of the lap. */
    fun track(sectorLines: Int = 2) = Track(
        id = "fake",
        name = "Fake circuit",
        center = origin,
        startFinish = lineAt(0.0),
        sectorLines = (1..sectorLines).map { lineAt(length * it / (sectorLines + 1)) },
    )

    /** Base speed in m/s at [s]; always changing. */
    fun speed(s: Double) = 32.0 + 10.0 * cos(2 * PI * 2 * s / length) + 3.0 * sin(2 * PI * 7 * s / length)

    /** The simulated drive: fixes, and the true time each lap boundary was crossed. */
    class Drive(val fixes: List<Fix>, val lineTimesMicros: Map<Double, List<Double>>)

    /**
     * Drives one lap per entry of [paces] (1.0 = base speed, higher is faster), starting [startBefore]
     * metres before the start/finish line, then [after] metres past the last crossing. Records the true
     * time of every pass of each distance in [marks] (fractions of the lap).
     */
    fun drive(
        paces: List<Double>,
        rateHz: Int = 25,
        startBefore: Double = 50.0,
        after: Double = 100.0,
        marks: List<Double> = listOf(0.0, 1.0 / 3, 2.0 / 3),
        startMicros: Long = 1_000_000L,
    ): Drive {
        val dt = 1e-4
        val fixEvery = 1.0 / rateHz
        var s = -startBefore
        var t = 0.0
        var nextFix = 0.0
        val fixes = mutableListOf<Fix>()
        val crossings = marks.associateWith { mutableListOf<Double>() }
        val end = paces.size * length + after
        fun pace(at: Double) = paces[(at / length).toInt().coerceIn(0, paces.size - 1)]
        while (s < end) {
            if (t >= nextFix - 1e-9) {
                val g = geo(s)
                fixes += Fix(startMicros + (t * 1e6).roundToLong(), g.lat, g.lon)
                nextFix += fixEvery
            }
            val v = speed(s) * pace(maxOf(s, 0.0))
            val s2 = s + v * dt
            for (m in marks) {
                val target = m * length
                // Passes of target + k * length in (s, s2].
                var lap = ceil((s - target) / length)
                while (target + lap * length <= s2) {
                    val at = target + lap * length
                    if (at > s) crossings.getValue(m) += startMicros + (t + dt * (at - s) / (s2 - s)) * 1e6
                    lap += 1
                }
            }
            s = s2
            t += dt
        }
        return Drive(fixes, crossings)
    }
}
