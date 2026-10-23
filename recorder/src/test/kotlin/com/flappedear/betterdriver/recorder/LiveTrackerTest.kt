package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.accel.fitAxis
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import com.flappedear.betterdriver.recorder.live.GnssCue
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.recorder.live.LiveTracker
import com.flappedear.betterdriver.recorder.live.gnssCue
import com.flappedear.betterdriver.recorder.live.gnssLostAfterMicros
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MicrosPerSecond = 1_000_000L

class LiveTrackerTest {
    @TempDir
    lateinit var tmp: File

    private val gnss = FakeGnss()

    @Test
    fun `waits for the first fix, then reports rate, speed and satellites`() {
        var now = 0L
        val tracker = LiveTracker("s", 0, { now })
        assertEquals(GnssState.Waiting, tracker.snapshot().gnss)

        // 2 s of 25 Hz fixes.
        for (n in 0 until 50L) {
            now = n * 40_000
            tracker.onSamples(SampleSet(now, gnss.fix(n)))
        }
        val s = tracker.snapshot()
        assertEquals(GnssState.Ok, s.gnss)
        assertEquals(50, s.fixes)
        assertEquals(25, s.fixRateHz)
        assertEquals(14, s.sats)
        assertEquals(gnss.fix(49).first { it.channelId == GnssChannels.SPEED_KMH.id }.value, s.speedKmh)
        assertEquals(now, s.elapsedMicros)
    }

    @Test
    fun `keeps the latest fix's position for the map screen, not a late one's`() {
        val tracker = LiveTracker("s", 0, { 200_000 })
        assertNull(tracker.snapshot().latitude)
        fun at(t: Long, lat: Double, lon: Double) =
            SampleSet(t, listOf(ChannelValue(GnssChannels.LAT.id, lat), ChannelValue(GnssChannels.LON.id, lon)))
        tracker.onSamples(at(100_000, 50.2, 18.9))
        tracker.onSamples(at(60_000, 50.1, 18.8))
        val s = tracker.snapshot()
        assertEquals(50.2, s.latitude)
        assertEquals(18.9, s.longitude)
    }

    @Test
    fun `reports GNSS lost when fixes stop, and a set without a position is not a fix`() {
        var now = 0L
        val tracker = LiveTracker("s", 0, { now })
        tracker.onSamples(SampleSet(0, gnss.fix(0)))
        tracker.onSamples(SampleSet(100_000, listOf(ChannelValue(GnssChannels.SATS.id, 3.0))))
        // One fix so far counts as 1 Hz: lost after 3 s.
        now = 3_100_000
        val s = tracker.snapshot()
        assertEquals(1, s.fixes)
        assertEquals(0, s.fixRateHz)
        assertEquals(3, s.sats)
        assertEquals(GnssState.Lost, s.gnss)
    }

    /**
     * A tracker fed 3 s of fixes at [hz], every other one [jitterMicros] late, checking that the
     * signal stays OK up to each next fix. Then the fixes stop: [after] is a snapshot on the timer
     * that long after the last fix, with no new sample.
     */
    private inner class Receiver(hz: Int, private val jitterMicros: Long = 0) {
        var now = 0L
        val tracker = LiveTracker("s", 0, { now })
        private val period = MicrosPerSecond / hz
        private val count = 3L * hz
        private fun fixAt(n: Long) = n * period + if (n % 2 == 1L) jitterMicros else 0
        val lastFix = fixAt(count - 1)

        init {
            for (n in 0 until count) {
                now = fixAt(n)
                tracker.onSamples(SampleSet(now, gnss.fix(n)))
                if (n + 1 < count) {
                    now = fixAt(n + 1) - 1
                    assertEquals(GnssState.Ok, tracker.snapshot().gnss, "lost between fixes at $hz Hz, fix $n")
                }
            }
        }

        fun after(ageMicros: Long): LiveSnapshot {
            now = lastFix + ageMicros
            return tracker.snapshot()
        }
    }

    @Test
    fun `a 1 Hz receiver is lost after 3 s, and a late fix does not flicker the banner`() {
        val r = Receiver(1, jitterMicros = 150_000)
        assertEquals(1, r.after(0).receiverRateHz)
        assertEquals(GnssState.Ok, r.after(1_500_000).gnss)
        assertEquals(GnssState.Ok, r.after(2_900_000).gnss)
        assertEquals(GnssState.Lost, r.after(3_100_000).gnss)
    }

    @Test
    fun `a 10 Hz receiver is lost after 1 s`() {
        val r = Receiver(10, jitterMicros = 20_000)
        assertEquals(10, r.after(0).receiverRateHz)
        assertEquals(GnssState.Ok, r.after(900_000).gnss)
        val lost = r.after(1_100_000)
        assertEquals(GnssState.Lost, lost.gnss)
        // The rate shown falls with the missing fixes; the receiver's rate is kept.
        assertEquals(0, lost.fixRateHz)
        assertEquals(10, lost.receiverRateHz)
    }

    @Test
    fun `a 25 Hz receiver is lost after 1 s, and the next fix brings it back`() {
        val r = Receiver(25)
        assertEquals(25, r.after(0).receiverRateHz)
        assertEquals(GnssState.Ok, r.after(900_000).gnss)
        assertEquals(GnssState.Lost, r.after(1_100_000).gnss)
        r.tracker.onSamples(SampleSet(r.now, gnss.fix(1_000)))
        assertEquals(GnssState.Ok, r.tracker.snapshot().gnss)
    }

    @Test
    fun `the first fix after a long gap keeps the receiver's rate`() {
        val r = Receiver(10)
        r.now = r.lastFix + 5 * MicrosPerSecond
        r.tracker.onSamples(SampleSet(r.now, gnss.fix(1_000)))
        assertEquals(10, r.tracker.snapshot().receiverRateHz)
        assertEquals(GnssState.Lost, r.after(6_100_000).gnss)
        r.now = r.lastFix + 5_100_000
        r.tracker.onSamples(SampleSet(r.now, gnss.fix(1_001)))
        assertEquals(10, r.tracker.snapshot().receiverRateHz)
    }

    @Test
    fun `the lost threshold is three fix periods and at least a second`() {
        assertEquals(3_000_000, gnssLostAfterMicros(0))
        assertEquals(3_000_000, gnssLostAfterMicros(1))
        assertEquals(1_500_000, gnssLostAfterMicros(2))
        assertEquals(1_000_000, gnssLostAfterMicros(10))
        assertEquals(1_000_000, gnssLostAfterMicros(25))
    }

    @Test
    fun `sounds only when the signal is lost and when it comes back`() {
        assertEquals(GnssCue.Lost, gnssCue(GnssState.Ok, GnssState.Lost))
        assertEquals(GnssCue.Back, gnssCue(GnssState.Lost, GnssState.Ok))
        assertNull(gnssCue(null, GnssState.Waiting))
        assertNull(gnssCue(GnssState.Waiting, GnssState.Ok))
        assertNull(gnssCue(GnssState.Ok, GnssState.Ok))
        assertNull(gnssCue(GnssState.Lost, GnssState.Lost))
        assertNull(gnssCue(GnssState.Lost, null))
        assertNull(gnssCue(GnssState.Ok, null))
    }

    @Test
    fun `combines X and Y acceleration into G only when the receiver has an accelerometer`() {
        val withAccel = LiveTracker("s", 0, { 0L }, accelIds = 110 to 111)
        assertNull(withAccel.snapshot().gCombined)
        val sample = listOf(ChannelValue(110, 0.3), ChannelValue(111, -0.4), ChannelValue(112, 1.0))
        withAccel.onSamples(SampleSet(0, sample))
        assertEquals(0.5, assertNotNull(withAccel.snapshot().gCombined), 1e-9)

        val without = LiveTracker("s", 0, { 0L })
        without.onSamples(SampleSet(0, listOf(ChannelValue(110, 0.3), ChannelValue(111, -0.4))))
        assertNull(without.snapshot().gCombined)
    }

    @Test
    fun `a tilted mount reads no G standing still and its tilt is taken off on the move`() {
        // Arek's RaceBox, 2026-10-12: pitched back, it reads X -0.187 g and Y 0.015 g at a standstill.
        val tracker = LiveTracker("s", 0, { 0L }, accelIds = 110 to 111)
        fun at(t: Long, kmh: Double, x: Double, y: Double) = tracker.onSamples(
            SampleSet(
                t,
                listOf(ChannelValue(GnssChannels.SPEED_KMH.id, kmh), ChannelValue(110, x), ChannelValue(111, y)),
            ),
        )
        for (i in 0 until 50) at(i * 40_000L, 0.2, -0.187, 0.015)
        assertEquals(0.0, assertNotNull(tracker.snapshot().gCombined), 1e-3)
        // Braking at 0.4 g on the move for half a second: the X axis reads 0.4 g more than at rest.
        for (i in 0 until 13) at(2_000_000 + i * 40_000L, 60.0, -0.187 + 0.4, 0.015)
        assertEquals(0.4, assertNotNull(tracker.snapshot().gCombined), 1e-3)
    }

    @Test
    fun `live G averages half a second so road vibration neither jumps nor adds to it`() {
        // A steady 0.5 g turn with ±0.3 g of vibration on both axes, 25 Hz (FBBD-302).
        val tracker = LiveTracker("s", 0, { 0L }, accelIds = 110 to 111)
        val shown = ArrayList<Double>()
        for (i in 0 until 100) {
            val shake = if (i % 2 == 0) 0.3 else -0.3
            tracker.onSamples(SampleSet(i * 40_000L, listOf(ChannelValue(110, shake), ChannelValue(111, 0.5 + shake))))
            if (i >= 25) shown += assertNotNull(tracker.snapshot().gCombined)
        }
        // A single reading would show 0.3 to 0.86 g; the half-second mean stays near 0.5 g.
        assertTrue(shown.all { it in 0.45..0.56 }, "shown $shown")

        // A step from 0 to 0.8 g is shown in full half a second later.
        val step = LiveTracker("s", 0, { 0L }, accelIds = 110 to 111)
        fun x(i: Int, g: Double) =
            step.onSamples(SampleSet(i * 40_000L, listOf(ChannelValue(110, g), ChannelValue(111, 0.0))))
        for (i in 0 until 25) x(i, 0.0)
        for (i in 25 until 38) x(i, 0.8)
        assertEquals(0.8, assertNotNull(step.snapshot().gCombined), 1e-9)
    }

    @Test
    fun `axis fit turns a backwards axis round and takes its tilt off`() {
        val gps = DoubleArray(200) { kotlin.math.sin(it / 10.0) * 0.3 }
        val backwards = DoubleArray(gps.size) { -gps[it] - 0.19 + if (it % 2 == 0) 0.01 else -0.01 }
        val fit = assertNotNull(fitAxis(backwards, gps))
        assertEquals(-1.0, fit.sign)
        assertEquals(-0.19, fit.bias, 0.005)
        assertTrue(fit.usable)
        assertEquals(0.25, fit.correct(-0.25 - 0.19), 0.005)
        // An all-zero column (a VBO whose device had no accelerometer) has nothing to fit.
        assertNull(fitAxis(DoubleArray(gps.size), gps))
        // An axis that does not follow GPS (mounted across the car) is not trusted.
        val across = DoubleArray(gps.size) { kotlin.math.cos(it / 10.0) * 0.3 }
        assertFalse(assertNotNull(fitAxis(across, gps)).usable)
    }

    @Test
    fun `keeps storage warnings and the stop reason`() {
        val tracker = LiveTracker("s", 0, { 0L })
        tracker.onStatus(RecorderStatus.StorageLow(123))
        assertNull(tracker.snapshot().stoppedReason)
        tracker.onStatus(RecorderStatus.Stopped("storage_full"))
        val s = tracker.snapshot()
        assertEquals(123, s.storageLowBytes)
        assertEquals("storage_full", s.stoppedReason)
    }

    @Test
    fun `follows a real recording as a listener and the session lists with its duration`() {
        val store = SessionStore(tmp)
        val recording = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL))
        val tracker = LiveTracker(recording.id, recording.info.startUtcMillis, recording::nowMicros)
        recording.listeners.add(tracker)
        recording.attach(FakeGnssSource(gnss))
        Thread.sleep(600)
        val live = tracker.snapshot()
        assertTrue(live.fixes > 0, "no fixes seen")
        assertEquals(GnssState.Ok, live.gnss)
        assertNull(store.list().single().durationMicros)

        assertTrue(recording.stop())
        val summary = store.list().single()
        assertEquals(SessionState.FINISHED, summary.state)
        val duration = assertNotNull(summary.durationMicros)
        assertTrue(duration >= live.elapsedMicros, "duration $duration < ${live.elapsedMicros}")
    }

    @Test
    fun `shows the latest OBD and heart-rate values, NaN once they stop arriving`() {
        var now = 0L
        val tracker = LiveTracker("s", 0, { now })
        val coolant = ChannelDef(300, "obd_coolant_temp", "°C", "obd", 2)
        val heart = ChannelDef(400, "heart_rate", "bpm", "heart_rate", 0)
        val rr = ChannelDef(401, "hr_rr_interval", "ms", "heart_rate", 1)
        listOf(coolant, heart, rr).forEach(tracker::onChannel)
        assertTrue(tracker.snapshot().channels.isEmpty())

        // Coolant every 5 s (a slow PID), heart rate every second.
        tracker.onSamples(SampleSet(0, listOf(ChannelValue(300, 88.0))))
        tracker.onSamples(SampleSet(5 * MicrosPerSecond, listOf(ChannelValue(300, 90.0))))
        for (s in 0..5L) {
            val beat = listOf(ChannelValue(400, 120.0 + s), ChannelValue(401, 500.0))
            tracker.onSamples(SampleSet(s * MicrosPerSecond, beat))
        }
        now = 5 * MicrosPerSecond
        assertEquals(mapOf("obd_coolant_temp" to 90.0, "heart_rate" to 125.0), tracker.snapshot().channels)

        // 4 s later: heart rate (1 s period) is stale after 3 s; coolant (5 s period) keeps for 15 s.
        now = 9 * MicrosPerSecond
        assertEquals(mapOf("obd_coolant_temp" to 90.0, "heart_rate" to Double.NaN), tracker.snapshot().channels)
        now = 21 * MicrosPerSecond
        assertEquals(mapOf("obd_coolant_temp" to Double.NaN, "heart_rate" to Double.NaN), tracker.snapshot().channels)
    }

    @Test
    fun `a value after an outage goes stale at the channel's own rate`() {
        var now = 0L
        val tracker = LiveTracker("s", 0, { now })
        tracker.onChannel(ChannelDef(400, "heart_rate", "bpm", "heart_rate", 0))
        for (s in 0..3L) tracker.onSamples(SampleSet(s * MicrosPerSecond, listOf(ChannelValue(400, 120.0))))
        // The strap is away for two minutes, sends one beat, and is gone again.
        tracker.onSamples(SampleSet(123 * MicrosPerSecond, listOf(ChannelValue(400, 130.0))))
        now = 127 * MicrosPerSecond
        assertTrue(tracker.snapshot().channels.getValue("heart_rate").isNaN())
    }

    @Test
    fun `a PID polled every 13 s keeps its value between polls`() {
        var now = 0L
        val tracker = LiveTracker("s", 0, { now })
        tracker.onChannel(ChannelDef(300, "obd_coolant_temp", "°C", "obd", 2))
        for (i in 0..3L) tracker.onSamples(SampleSet(i * 13 * MicrosPerSecond, listOf(ChannelValue(300, 90.0))))
        now = 51 * MicrosPerSecond
        assertEquals(90.0, tracker.snapshot().channels.getValue("obd_coolant_temp"))
    }

    @Test
    fun `fast RPM shows the mean of the last 0,3 s, not the one reading that came last`() {
        var now = 0L
        val tracker = LiveTracker("s", 0, { now })
        tracker.onChannel(ChannelDef(300, "obd_rpm", "rpm", "obd", 0))
        // 20 Hz readings that alternate by 100 rpm around 3050, as a jittery adapter reads them.
        val shown = mutableListOf<Double>()
        for (i in 0 until 40L) {
            now = i * 50_000
            tracker.onSamples(SampleSet(now, listOf(ChannelValue(300, if (i % 2 == 0L) 3000.0 else 3100.0))))
            if (i % 5 == 4L) shown += tracker.snapshot().channels.getValue("obd_rpm")
        }
        // Seven readings in each 0.3 s window: within 100/7 rpm of 3050 every time, never a 100 rpm jump.
        assertTrue(shown.all { abs(it - 3050.0) <= 100.0 / 7 + 1e-9 }, "$shown")
    }

    @Test
    fun `a reading on its own is shown as it is, and an old one leaves the mean`() {
        var now = 0L
        val tracker = LiveTracker("s", 0, { now })
        tracker.onChannel(ChannelDef(300, "obd_rpm", "rpm", "obd", 0))
        tracker.onSamples(SampleSet(0, listOf(ChannelValue(300, 900.0))))
        assertEquals(900.0, tracker.snapshot().channels.getValue("obd_rpm"))
        tracker.onSamples(SampleSet(200_000, listOf(ChannelValue(300, 1100.0))))
        assertEquals(1000.0, tracker.snapshot().channels.getValue("obd_rpm"))
        // 0.4 s after the first reading it is out of the window.
        tracker.onSamples(SampleSet(400_000, listOf(ChannelValue(300, 1300.0))))
        assertEquals(1200.0, tracker.snapshot().channels.getValue("obd_rpm"))
    }

    @Test
    fun `GNSS and other channels are not shown as live values`() {
        val tracker = LiveTracker("s", 0, { 0L })
        GnssChannels.ALL.forEach(tracker::onChannel)
        tracker.onChannel(ChannelDef(110, "accel_x", "g", "racebox"))
        tracker.onSamples(SampleSet(0, gnss.fix(0) + ChannelValue(110, 0.2)))
        assertTrue(tracker.snapshot().channels.isEmpty())
    }
}
