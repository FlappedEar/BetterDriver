@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.circuits.Circuit
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.track.SessionLoop
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.findSessionLoop
import com.flappedear.betterdriver.timing.track.proposeTrack
import com.flappedear.betterdriver.timing.track.trackAlong
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Making a track from a session (FBBD-151): loops, straights and the line, on made-up drives. */
class SessionLoopTest {
    private val origin = GeoPoint(52.30, 17.10)
    private val frame = LocalFrame(origin)

    /**
     * An oval-ish circuit driven anticlockwise: an 800 m straight going east along y = 0, a 600 m
     * back straight and rounded ends. Corners are cut (Chaikin), so the long straights stay straight.
     */
    private val oval = closedPath(
        listOf(Xy(0.0, 0.0), Xy(800.0, 0.0), Xy(950.0, 150.0), Xy(700.0, 300.0), Xy(100.0, 300.0), Xy(-150.0, 150.0)),
    )

    /**
     * A circuit whose 1,200 m main straight going east has a chicane 400 m in; the longest straight
     * is then the 750 m after the chicane, not the 400 m before it or the 650 m back straight.
     */
    private val chicane = closedPath(
        listOf(
            Xy(0.0, 0.0), Xy(400.0, 0.0), Xy(425.0, 15.0), Xy(450.0, 0.0), Xy(1200.0, 0.0),
            Xy(1350.0, 200.0), Xy(1100.0, 400.0), Xy(800.0, 250.0), Xy(150.0, 250.0), Xy(-150.0, 120.0),
        ),
    )

    @Test
    fun `an oval gets its line across the middle of the longest straight`() {
        val loop = assertNotNull(findSessionLoop(oval.drive(laps = 4.3)))
        assertEquals(oval.length, loop.lengthMeters, oval.length * 0.03)
        assertLineAt(loop, Xy(400.0, 0.0), headingDeg = 90.0)
        assertTrue(loop.straightMeters > 500, "straight ${loop.straightMeters}")
        // From the start near the west end, the middle of the straight is passed once per lap.
        assertEquals(5, loop.passes)
        assertTrue(abs(frame.toXy(loop.center).y - 150) < 30, "centre ${frame.toXy(loop.center)}")
    }

    @Test
    fun `the line follows the direction the circuit is driven`() {
        val loop = assertNotNull(findSessionLoop(oval.reversed().drive(laps = 3.5)))
        assertLineAt(loop, Xy(400.0, 0.0), headingDeg = 270.0)
    }

    @Test
    fun `a chicane splits the main straight`() {
        val loop = assertNotNull(findSessionLoop(chicane.drive(laps = 3.2)))
        assertEquals(chicane.length, loop.lengthMeters, chicane.length * 0.03)
        // Middle of the straight from the chicane exit (about 450 m, plus the cut corner) to 1,200 m.
        assertLineAt(loop, Xy(825.0, 0.0), headingDeg = 90.0, alongMeters = 60.0)
    }

    @Test
    fun `noisy GNSS, a standing start and a pit stop still find the loop`() {
        val random = Random(7)
        val fixes = oval.drive(laps = 5.2, noiseMeters = 2.5, random = random, standSeconds = 60.0)
        val loop = assertNotNull(findSessionLoop(fixes))
        assertEquals(oval.length, loop.lengthMeters, oval.length * 0.05)
        assertLineAt(loop, Xy(400.0, 0.0), headingDeg = 90.0, alongMeters = 60.0, headingTolerance = 6.0)
    }

    @Test
    fun `the proposed track times the session's laps`() {
        val fixes = oval.drive(laps = 4.3)
        val loop = assertNotNull(findSessionLoop(fixes))
        val track = proposeTrack(loop, emptyList(), CircuitList.Empty, "Airfield", id = "airfield")
        assertEquals("Airfield", track.name)
        assertEquals(null, track.circuitId)
        assertEquals(loop.center, track.center)
        assertTrue(track.radiusMeters >= loop.extentMeters, "radius ${track.radiusMeters}")
        val book = timeSession(track, fixes.asSequence())
        val timed = book.laps.filter { it.kind == LapKind.Timed }
        assertEquals(loop.laps, timed.size)
        assertEquals(4, timed.size)
    }

    @Test
    fun `a circuit on the list names the track`() {
        val loop = assertNotNull(findSessionLoop(oval.drive(laps = 3.0)))
        val circuit = Circuit("Q42", "Tor Testowy", "PL", frame.toGeo(Xy(300.0, 200.0)), 1_500.0)
        val far = Circuit("Q1", "Elsewhere", "PL", GeoPoint(50.0, 20.0))
        val track = proposeTrack(loop, emptyList(), CircuitList(1, "test", listOf(far, circuit)), "Unused")
        assertEquals("Tor Testowy", track.name)
        assertEquals("Q42", track.circuitId)
        assertEquals("PL", track.country)
        assertEquals(loop.center, track.center)
        assertEquals(loop.startFinish, track.startFinish)
    }

    @Test
    fun `a saved track here without a line gets the line`() {
        val loop = assertNotNull(findSessionLoop(oval.drive(laps = 3.0)))
        val lineless = Track("mine", "My airfield", frame.toGeo(Xy(500.0, 500.0)), layout = "Long")
        val elsewhere = Track("other", "Other", GeoPoint(50.0, 20.0))
        val track = proposeTrack(loop, listOf(elsewhere, lineless), CircuitList.Empty, "Unused")
        assertEquals("mine", track.id)
        assertEquals("Long", track.layout)
        assertEquals(lineless.center, track.center)
        assertEquals(loop.startFinish, track.startFinish)
    }

    @Test
    fun `a figure of eight keeps its line off the crossing`() {
        // Diagonals of 850 m cross at (300, 300), over a bridge; the sides are 600 m.
        val eight = closedPath(
            listOf(
                Xy(0.0, 0.0), Xy(600.0, 600.0), Xy(900.0, 600.0), Xy(900.0, 0.0),
                Xy(600.0, 0.0), Xy(0.0, 600.0), Xy(-300.0, 600.0), Xy(-300.0, 0.0),
            ),
        )
        val fixes = eight.drive(laps = 3.4)
        val loop = assertNotNull(findSessionLoop(fixes))
        assertEquals(eight.length, loop.lengthMeters, eight.length * 0.03)
        val l = frame.toXy(loop.startFinish.left)
        val r = frame.toXy(loop.startFinish.right)
        val mid = Xy((l.x + r.x) / 2, (l.y + r.y) / 2)
        assertTrue(hypot(mid.x - 300, mid.y - 300) > 30, "line at the crossing: $mid")
        assertTrue(abs(abs(mid.x - 300) - abs(mid.y - 300)) < 5, "line off the diagonal: $mid")
        val track = proposeTrack(loop, emptyList(), CircuitList.Empty, "Eight", id = "eight")
        assertEquals(loop.laps, timeSession(track, fixes.asSequence()).laps.count { it.kind == LapKind.Timed })
        // 3.4 laps driven: 2 or 3 full laps, by which diagonal the line is on.
        assertTrue(loop.laps in 2..3, "laps ${loop.laps}")
    }

    @Test
    fun `a road drive is not a track`() {
        // Out along a winding road, round a 1.2 km block once, then back the same way home.
        val road = listOf(Xy(0.0, 0.0), Xy(1500.0, 300.0), Xy(2500.0, -400.0), Xy(4000.0, 0.0), Xy(4200.0, 900.0))
        val block = listOf(
            Xy(4300.0, 1000.0), Xy(4600.0, 1000.0), Xy(4600.0, 1300.0), Xy(4300.0, 1300.0), Xy(4300.0, 1000.0),
        )
        val out = openPath(road + block)
        val back = openPath((road + block.first()).reversed())
        val fixes = out.drive(laps = 1.0) + back.drive(laps = 1.0, startMicros = 1_000_000_000L)
        assertNull(findSessionLoop(fixes))
        assertNull(findSessionLoop(noisy(fixes, Random(3))))
    }

    @Test
    fun `positions off the globe or at 0, 0 are left out`() {
        val drive = oval.drive(laps = 4.3)
        val damaged = drive.toMutableList()
        damaged.add(0, Fix(-1, 0.0, 0.0))
        damaged.add(10, damaged[10].copy(lat = 123.0))
        damaged.add(2000, damaged[2000].copy(lon = -400.0))
        damaged.add(3000, damaged[3000].copy(lat = Double.NaN))
        val loop = assertNotNull(findSessionLoop(damaged))
        assertEquals(findSessionLoop(drive), loop)
        assertNull(findSessionLoop(listOf(Fix(0, 95.0, 17.0), Fix(1, 95.0, 17.1))))
    }

    @Test
    fun `a car that never leaves the paddock is not a track`() {
        val random = Random(11)
        val standing = (0 until 6_000).map { k ->
            val p = frame.toGeo(Xy(random.nextGaussian() * 3, random.nextGaussian() * 3))
            Fix(k * 100_000L, p.lat, p.lon)
        }
        assertNull(findSessionLoop(standing))
    }

    @Test
    fun `a loop shorter than a circuit is not a track`() {
        val small = closedPath(listOf(Xy(0.0, 0.0), Xy(250.0, 0.0), Xy(250.0, 150.0), Xy(0.0, 150.0)))
        assertNull(findSessionLoop(small.drive(laps = 6.0)))
    }

    @Test
    fun `one lap with nothing driven twice is not enough`() {
        assertNull(findSessionLoop(oval.drive(laps = 1.1)))
    }

    @Test
    fun `a session that started away from its track still finds it`() {
        val loop = assertNotNull(findSessionLoop(oval.drive(laps = 3.0)))
        val track = proposeTrack(loop, emptyList(), CircuitList.Empty, "Airfield", id = "airfield")
        val far = Track("far", "Far", GeoPoint(50.0, 20.0), startFinish = loop.startFinish)
        val home = frame.toGeo(Xy(-30_000.0, 0.0))
        val drive = listOf(home, frame.toGeo(Xy(-10_000.0, 0.0)), frame.toGeo(Xy(100.0, 10.0)))
        assertEquals(track, trackAlong(drive.asSequence(), listOf(far, track)))
        assertNull(trackAlong(drive.take(2).asSequence(), listOf(far, track)))
        // A track without a line times nothing, so it is not picked.
        assertNull(trackAlong(drive.asSequence(), listOf(track.copy(startFinish = null))))
    }

    private fun assertLineAt(
        loop: SessionLoop,
        at: Xy,
        headingDeg: Double,
        alongMeters: Double = 40.0,
        headingTolerance: Double = 3.0,
    ) {
        val l = frame.toXy(loop.startFinish.left)
        val r = frame.toXy(loop.startFinish.right)
        val mid = Xy((l.x + r.x) / 2, (l.y + r.y) / 2)
        assertTrue(abs(mid.x - at.x) <= alongMeters && abs(mid.y - at.y) <= 5, "line at $mid, expected near $at")
        // Driving forwards, left is on the left: the heading is 90 degrees clockwise from left to right.
        val across = Math.toDegrees(atan2(r.x - l.x, r.y - l.y))
        val heading = ((across - 90) % 360 + 360) % 360
        val off = abs(((heading - headingDeg) % 360 + 540) % 360 - 180)
        assertTrue(off <= headingTolerance, "heading $heading, expected $headingDeg")
        assertEquals(30.0, distanceMeters(loop.startFinish.left, loop.startFinish.right), 0.5)
    }

    private fun noisy(fixes: List<Fix>, random: Random, sigma: Double = 2.5): List<Fix> = fixes.map { f ->
        val p = frame.toXy(f.lat, f.lon)
        val g = frame.toGeo(Xy(p.x + random.nextGaussian() * sigma, p.y + random.nextGaussian() * sigma))
        Fix(f.tMicros, g.lat, g.lon)
    }

    /** A path through [corners] with the corners cut, densely sampled; [closed] joins the end to the start. */
    private inner class Polyline(val corners: List<Xy>, val closed: Boolean) {
        private val points: List<Xy> = densify(chaikin(withCuts(corners, closed), closed, 4), closed)
        private val along: DoubleArray = DoubleArray(points.size).also { d ->
            for (k in 1 until points.size) {
                d[k] = d[k - 1] + hypot(points[k].x - points[k - 1].x, points[k].y - points[k - 1].y)
            }
        }
        val length: Double get() = along.last()

        fun reversed() = Polyline(corners.reversed(), closed)

        /**
         * Fixes at 10 Hz of [laps] times round (or once along, open), at a speed that keeps changing.
         * With [standSeconds], the car first stands still that long and again halfway through.
         */
        fun drive(
            laps: Double,
            noiseMeters: Double = 0.0,
            random: Random = Random(1),
            standSeconds: Double = 0.0,
            startMicros: Long = 0L,
        ): List<Fix> {
            val total = laps * length
            val fixes = ArrayList<Fix>()
            var t = 0.0
            var s = 0.0
            var stoodHalfway = false
            fun fix() {
                val p = at(s)
                val noise = Xy(random.nextGaussian() * noiseMeters, random.nextGaussian() * noiseMeters)
                val g = frame.toGeo(p + noise)
                fixes += Fix(startMicros + (t * 1e6).roundToLong(), g.lat, g.lon)
                t += 0.1
            }
            repeat((standSeconds * 10).toInt()) { fix() }
            while (s < total) {
                fix()
                if (!stoodHalfway && s > total / 2) {
                    stoodHalfway = true
                    repeat((standSeconds * 10).toInt()) { fix() }
                }
                s += 0.1 * (30.0 + 8.0 * sin(2 * PI * s / 700.0))
            }
            return fixes
        }

        private fun at(distance: Double): Xy {
            val d = if (closed) ((distance % length) + length) % length else distance.coerceIn(0.0, length)
            var lo = 0
            var hi = points.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (along[mid] <= d) lo = mid else hi = mid
            }
            val span = along[hi] - along[lo]
            val u = if (span == 0.0) 0.0 else (d - along[lo]) / span
            return points[lo] + (points[hi] - points[lo]) * u
        }

        /** Each corner with a point on each side, so a corner is only rounded near it and edges stay straight. */
        private fun withCuts(path: List<Xy>, closed: Boolean): List<Xy> {
            val out = ArrayList<Xy>()
            val edges = if (closed) path.size else path.size - 1
            for (k in 0 until edges) {
                val a = path[k]
                val b = path[(k + 1) % path.size]
                val len = hypot(b.x - a.x, b.y - a.y)
                val c = minOf(40.0, len / 3) / len
                out += a
                out += Xy(a.x + (b.x - a.x) * c, a.y + (b.y - a.y) * c)
                out += Xy(b.x - (b.x - a.x) * c, b.y - (b.y - a.y) * c)
            }
            if (!closed) out += path.last()
            return out
        }

        private fun chaikin(path: List<Xy>, closed: Boolean, times: Int): List<Xy> {
            var p = path
            repeat(times) {
                val out = ArrayList<Xy>()
                if (!closed) out += p.first()
                val edges = if (closed) p.size else p.size - 1
                for (k in 0 until edges) {
                    val a = p[k]
                    val b = p[(k + 1) % p.size]
                    out += Xy(0.75 * a.x + 0.25 * b.x, 0.75 * a.y + 0.25 * b.y)
                    out += Xy(0.25 * a.x + 0.75 * b.x, 0.25 * a.y + 0.75 * b.y)
                }
                if (!closed) out += p.last()
                p = out
            }
            return p
        }

        private fun densify(path: List<Xy>, closed: Boolean): List<Xy> {
            val ring = if (closed) path + path.first() else path
            val out = ArrayList<Xy>()
            for (k in 0 until ring.size - 1) {
                val a = ring[k]
                val b = ring[k + 1]
                val n = maxOf(1, hypot(b.x - a.x, b.y - a.y).toInt())
                for (i in 0 until n) out += Xy(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n)
            }
            out += ring.last()
            return out
        }
    }

    private fun closedPath(corners: List<Xy>) = Polyline(corners, true)

    private fun openPath(corners: List<Xy>) = Polyline(corners, false)
}
