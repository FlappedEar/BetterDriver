package com.flappedear.betterdriver.recorder

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
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
