package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.live.GPoint
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.recorder.live.LiveTracker
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val StepMicros = 40_000L
private const val G = 9.80665

/**
 * The G radar's dot (FBBD-318) follows the car's forward and left whatever way the receiver is
 * mounted. A drive at 25 Hz alternates braking and speeding up while weaving left and right; the
 * receiver's X and Y are made from the true G as a given mount reads it.
 */
class LiveGgTest {
    /** True forward and left G at time [t] in seconds, and the speed (m/s) and heading (degrees) they give. */
    private class Drive {
        var speed = 25.0
        var heading = 0.0

        fun forward(t: Double) = 0.6 * sin(2 * PI * t / 7.0)
        fun left(t: Double) = 0.8 * sin(2 * PI * t / 4.0)

        fun step(t: Double, dt: Double) {
            speed += forward(t) * G * dt
            // A left turn makes the heading smaller.
            heading -= Math.toDegrees(left(t) * G / speed * dt)
        }
    }

    /**
     * [fixLateMicros] makes each set's fix that much older than its acceleration (FBBD-229). With
     * [separate] the acceleration and the fix come in sets of their own, as RaceChrono DIY input sends
     * them; [fixUntil] stops the fixes there, as a lost fix.
     */
    private data class Feed(
        val fixLateMicros: Long = 0,
        val separate: Boolean = false,
        val fixUntil: Double = Double.MAX_VALUE,
    )

    /**
     * Feeds [seconds] of driving; [mount] turns the true (forward, left) into the receiver's X and Y.
     * [feed] says how the receiver sends them.
     */
    private fun drive(
        seconds: Double,
        mount: (Double, Double) -> Pair<Double, Double>,
        feed: Feed = Feed(),
        check: (t: Double, s: LiveSnapshot, truth: GPoint) -> Unit = { _, _, _ -> },
    ): LiveSnapshot {
        var now = 0L
        val tracker = LiveTracker("s", 0, { now }, accelIds = AccelX to AccelY)
        val car = Drive()
        val dt = StepMicros / 1e6
        val late = (feed.fixLateMicros / StepMicros).toInt()
        val fixes = ArrayDeque<Pair<Double, Double>>()
        var n = 0L
        while (n * dt < seconds) {
            val t = n * dt
            car.step(t, dt)
            fixes.addLast(car.speed to car.heading)
            val (speed, heading) = if (fixes.size > late) fixes.removeFirst() else fixes.first()
            val (x, y) = mount(car.forward(t), car.left(t))
            now = n * StepMicros
            val fix = if (t < feed.fixUntil) {
                listOf(
                    ChannelValue(GnssChannels.LAT.id, 50.0),
                    ChannelValue(GnssChannels.LON.id, 19.0),
                    ChannelValue(GnssChannels.SPEED_KMH.id, speed * 3.6),
                    ChannelValue(GnssChannels.HEADING.id, (heading % 360 + 360) % 360),
                )
            } else {
                emptyList()
            }
            val accel = listOf(ChannelValue(AccelX, x), ChannelValue(AccelY, y))
            if (feed.separate) {
                tracker.onSamples(SampleSet(now, accel))
                if (fix.isNotEmpty()) tracker.onSamples(SampleSet(now, fix))
            } else {
                tracker.onSamples(SampleSet(now, fix + accel))
            }
            // The dot is the last half second's mean: the truth a quarter second back.
            check(t, tracker.snapshot(), GPoint(car.forward(t - 0.25), car.left(t - 0.25)))
            n++
        }
        return tracker.snapshot()
    }

    private fun assertNear(truth: GPoint, g: GPoint?, within: Double, at: Double) {
        assertNotNull(g, "no dot at $at s")
        assertTrue(abs(g.forward - truth.forward) < within, "forward ${g.forward} vs ${truth.forward} at $at s")
        assertTrue(abs(g.left - truth.left) < within, "left ${g.left} vs ${truth.left} at $at s")
    }

    @Test
    fun `a backwards, tilted X and an on-time fix, braking reads as braking`() {
        // Arek's RaceBox: X against forward, pitched back (FBBD-204).
        drive(60.0, { f, l -> -f - 0.19 to l }) { t, s, truth -> if (t > 20) assertNear(truth, s.g, 0.08, t) }
    }

    @Test
    fun `a receiver turned across the car, with its fix 0,8 s late`() {
        // X along the car's right, Y backwards; the Mini's late fix (FBBD-229).
        drive(60.0, { f, l -> -l to -f }, Feed(fixLateMicros = 800_000)) { t, s, truth ->
            if (t > 30) assertNear(truth, s.g, 0.08, t)
        }
    }

    @Test
    fun `RaceChrono DIY input, acceleration and fix in separate sets, is matched too`() {
        // On GPS G alone the dot would be the 0.8 s late fix's, well off the truth.
        drive(60.0, { f, l -> -l to -f }, Feed(fixLateMicros = 800_000, separate = true)) { t, s, truth ->
            if (t > 30) assertNear(truth, s.g, 0.08, t)
        }
    }

    @Test
    fun `a fix lost before the axes are matched takes the dot away, not freezes it`() {
        val s = drive(6.0, { f, l -> -f to l }, Feed(fixUntil = 3.0))
        assertNull(s.g)
        assertTrue(s.gTrail.isEmpty())
    }

    @Test
    fun `before the axes are matched the dot is the GPS's G, in the right direction`() {
        // GPS G over the last half second runs a quarter second behind the same as the accelerometer.
        drive(8.0, { f, l -> -f to l }) { t, s, truth -> if (t > 1) assertNear(truth, s.g, 0.1, t) }
    }

    @Test
    fun `the trail is the half second before the dot, oldest first`() {
        val s = drive(30.0, { f, l -> f to l })
        assertEquals(4, s.gTrail.size)
        // The car is coming out of a left turn at 29.96 s: older points read more left G.
        assertTrue((s.gTrail + assertNotNull(s.g)).zipWithNext().all { (a, b) -> a.left > b.left }, "${s.gTrail}")
    }

    @Test
    fun `no G before the first reading`() {
        val tracker = LiveTracker("s", 0, { 0L }, accelIds = AccelX to AccelY)
        assertNull(tracker.snapshot().g)
        assertTrue(tracker.snapshot().gTrail.isEmpty())
    }

    @Test
    fun `standing still the dot sits in the middle`() {
        var now = 0L
        val tracker = LiveTracker("s", 0, { now }, accelIds = AccelX to AccelY)
        for (n in 0 until 50L) {
            now = n * StepMicros
            val values = listOf(
                ChannelValue(GnssChannels.LAT.id, 50.0),
                ChannelValue(GnssChannels.LON.id, 19.0),
                ChannelValue(GnssChannels.SPEED_KMH.id, 0.3),
                ChannelValue(GnssChannels.HEADING.id, if (n % 2 == 0L) 10.0 else 200.0),
                ChannelValue(AccelX, 0.19),
                ChannelValue(AccelY, -0.02),
            )
            tracker.onSamples(SampleSet(now, values))
        }
        assertNear(GPoint(0.0, 0.0), tracker.snapshot().g, 0.01, 2.0)
    }

    /**
     * Arek's Jastrząb 29 Aug 16:06 session (RaceChrono 10.2.4 export, 10 Hz, 6 minutes; no positions):
     * speed and heading, a flat receiver whose X runs backwards, a phone tilted back, and RaceChrono's
     * GPS G to compare with. Returns how often the dot's directions agree with RaceChrono's above 0.4 g
     * and its RMS difference forward and sideways.
     */
    private fun jastrzab(x: Int, y: Int): Triple<Double, Double, Double> {
        val csv = javaClass.getResource("/live/jastrzab-1606-g.csv")!!.readText()
        val rows = csv.lines().drop(1).filter { it.isNotBlank() }
        var now = 0L
        val tracker = LiveTracker("s", 0, { now }, accelIds = AccelX to AccelY)
        var n = 0
        var agree = 0
        var sf = 0.0
        var sl = 0.0
        for (row in rows) {
            val v = row.split(',').map { it.toDouble() }
            now = (v[0] * 1e6).toLong()
            val values = listOf(
                ChannelValue(GnssChannels.LAT.id, 50.0),
                ChannelValue(GnssChannels.LON.id, 19.0),
                ChannelValue(GnssChannels.SPEED_KMH.id, v[1]),
                ChannelValue(GnssChannels.HEADING.id, v[2]),
                ChannelValue(AccelX, v[x]),
                ChannelValue(AccelY, v[y]),
            )
            tracker.onSamples(SampleSet(now, values))
            val g = tracker.snapshot().g
            val rf = v[7]
            val rl = v[8]
            if (g == null || v[1] < 30 || kotlin.math.hypot(rf, rl) < 0.4) continue
            n++
            val sameF = abs(rf) < 0.15 || (g.forward > 0) == (rf > 0)
            val sameL = abs(rl) < 0.15 || (g.left > 0) == (rl > 0)
            if (sameF && sameL) agree++
            sf += (g.forward - rf) * (g.forward - rf)
            sl += (g.left - rl) * (g.left - rl)
        }
        return Triple(agree.toDouble() / n, kotlin.math.sqrt(sf / n), kotlin.math.sqrt(sl / n))
    }

    @Test
    fun `on Arek's Jastrząb laps the dot points where RaceChrono's G does`() {
        // RaceChrono's G is centred on its moment, the dot's is the half second before: some of the
        // difference is that quarter second.
        val (agree, forward, left) = jastrzab(3, 4)
        assertTrue(agree > 0.97, "agree $agree")
        assertTrue(forward < 0.12 && left < 0.12, "rms forward $forward, left $left")
    }

    @Test
    fun `a phone tilted back does not agree well enough, so the dot stays on GPS and is still right`() {
        val (agree, forward, left) = jastrzab(5, 6)
        assertTrue(agree > 0.97, "agree $agree")
        assertTrue(forward < 0.12 && left < 0.12, "rms forward $forward, left $left")
    }

    /**
     * The phone's own GPS and accelerometer (no receiver): the phone upright in a windscreen mount,
     * tilted back [tiltDeg], its screen to the driver. Its X is the car's right, Y mostly up, Z mostly
     * back, each with its share of gravity. Readings at 25 Hz, each in its own set; a fix a second,
     * stamped [fixAgeMicros] before it arrives (phoneFixMicros).
     */
    private fun phoneDrive(
        seconds: Double,
        tiltDeg: Double = 25.0,
        fixAgeMicros: Long = 600_000,
        check: (t: Double, s: LiveSnapshot, truth: GPoint) -> Unit,
    ): LiveSnapshot {
        var now = 0L
        val tracker = LiveTracker("s", 0, { now }, accelIds = AccelX to AccelY, accelZId = AccelZ)
        val car = Drive()
        val dt = StepMicros / 1e6
        val c = kotlin.math.cos(Math.toRadians(tiltDeg))
        val s = kotlin.math.sin(Math.toRadians(tiltDeg))
        val stamped = ArrayDeque<Triple<Long, Double, Double>>()
        var n = 0L
        while (n * dt < seconds) {
            val t = n * dt
            car.step(t, dt)
            now = n * StepMicros
            val f = car.forward(t)
            val l = car.left(t)
            val shake = if (n % 2 == 0L) 0.05 else -0.05
            tracker.onSamples(
                SampleSet(
                    now,
                    listOf(
                        ChannelValue(AccelX, -l + shake),
                        ChannelValue(AccelY, -s * f + c + shake),
                        ChannelValue(AccelZ, -c * f - s + shake),
                    ),
                ),
            )
            // A fix taken now arrives fixAgeMicros later.
            if (n % 25 == 0L) stamped.addLast(Triple(now, car.speed, car.heading))
            while (stamped.isNotEmpty() && stamped.first().first <= now - fixAgeMicros) {
                val (at, speed, heading) = stamped.removeFirst()
                val fix = listOf(
                    ChannelValue(GnssChannels.LAT.id, 50.0),
                    ChannelValue(GnssChannels.LON.id, 19.0),
                    ChannelValue(GnssChannels.SPEED_KMH.id, speed * 3.6),
                    ChannelValue(GnssChannels.HEADING.id, (heading % 360 + 360) % 360),
                )
                tracker.onSamples(SampleSet(at, fix))
            }
            check(t, tracker.snapshot(), GPoint(car.forward(t - 0.25), car.left(t - 0.25)))
            n++
        }
        return tracker.snapshot()
    }

    @Test
    fun `phone GPS only, the phone's accelerometer upright in its mount gives the dot`() {
        var shown = 0
        phoneDrive(120.0) { t, s, truth ->
            if (s.g != null) shown++
            // A minute of 1 Hz fixes to match the axes; then the accelerometer's G, not the GPS's.
            if (t > 75) assertNear(truth, s.g, 0.1, t)
        }
        assertTrue(shown > 2900, "the dot shows from the second fix on, shown $shown of 3000")
    }

    @Test
    fun `phone GPS only, the G tile reads the size of the dot`() {
        val s = phoneDrive(100.0) { _, _, _ -> }
        val g = assertNotNull(s.g)
        assertEquals(kotlin.math.hypot(g.forward, g.left), assertNotNull(s.gCombined), 1e-9)
    }

    /**
     * Arek's Jastrząb 29 Aug 16:06 session again, with the phone's three axes (it sat upright, tilted
     * back about 28°, RaceChrono 10.2.4 export, 10 Hz): how often the dot agrees with RaceChrono's G
     * above 0.4 g, and the RMS difference forward and sideways. [fixEvery] row has a fix, in a set of
     * its own: 10 is the phone GPS's one a second.
     */
    private fun jastrzabPhone(fixEvery: Int): Triple<Double, Double, Double> {
        val csv = javaClass.getResource("/live/jastrzab-1606-phone.csv")!!.readText()
        val rows = csv.lines().drop(1).filter { it.isNotBlank() }
        var now = 0L
        val tracker = LiveTracker("s", 0, { now }, accelIds = AccelX to AccelY, accelZId = AccelZ)
        var n = 0
        var agree = 0
        var sf = 0.0
        var sl = 0.0
        for ((i, row) in rows.withIndex()) {
            val v = row.split(',').map { it.toDouble() }
            now = (v[0] * 1e6).toLong()
            val accel = listOf(ChannelValue(AccelX, v[3]), ChannelValue(AccelY, v[4]), ChannelValue(AccelZ, v[5]))
            tracker.onSamples(SampleSet(now, accel))
            if (i % fixEvery == 0) {
                val fix = listOf(
                    ChannelValue(GnssChannels.LAT.id, 50.0),
                    ChannelValue(GnssChannels.LON.id, 19.0),
                    ChannelValue(GnssChannels.SPEED_KMH.id, v[1]),
                    ChannelValue(GnssChannels.HEADING.id, v[2]),
                )
                tracker.onSamples(SampleSet(now, fix))
            }
            val g = tracker.snapshot().g
            val rf = v[6]
            val rl = v[7]
            if (g == null || v[1] < 30 || kotlin.math.hypot(rf, rl) < 0.4) continue
            n++
            val sameF = abs(rf) < 0.15 || (g.forward > 0) == (rf > 0)
            val sameL = abs(rl) < 0.15 || (g.left > 0) == (rl > 0)
            if (sameF && sameL) agree++
            sf += (g.forward - rf) * (g.forward - rf)
            sl += (g.left - rl) * (g.left - rl)
        }
        return Triple(agree.toDouble() / n, kotlin.math.sqrt(sf / n), kotlin.math.sqrt(sl / n))
    }

    @Test
    fun `on Arek's Jastrząb laps the phone's three axes point where RaceChrono's G does`() {
        val (agree, forward, left) = jastrzabPhone(fixEvery = 1)
        assertTrue(agree > 0.97, "agree $agree")
        assertTrue(forward < 0.12 && left < 0.12, "rms forward $forward, left $left")
    }

    @Test
    fun `on Arek's Jastrząb laps with a fix a second, as the phone's GPS, the dot still points right`() {
        val (agree, forward, left) = jastrzabPhone(fixEvery = 10)
        assertTrue(agree > 0.97, "agree $agree")
        assertTrue(forward < 0.12 && left < 0.12, "rms forward $forward, left $left")
    }

    private companion object {
        const val AccelX = 20
        const val AccelY = 21
        const val AccelZ = 22
    }
}
