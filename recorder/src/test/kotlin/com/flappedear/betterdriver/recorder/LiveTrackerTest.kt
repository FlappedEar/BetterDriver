package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.live.LiveTracker
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        now = 2_500_000
        val s = tracker.snapshot()
        assertEquals(1, s.fixes)
        assertEquals(0, s.fixRateHz)
        assertEquals(3, s.sats)
        assertEquals(GnssState.Lost, s.gnss)
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
