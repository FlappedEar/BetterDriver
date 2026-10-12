package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.fake.FakeGnssEvents
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.live.LiveTracker
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Demo GNSS switched off and on, as the emulator tests drop GNSS on purpose (FBBD-43, FBBD-64). */
class FakeGnssSourceTest {
    @TempDir
    lateinit var tmp: File

    private val gnss = FakeGnss()

    @Test
    fun `switched off it sends no fixes and logs the drop and the return`() {
        val on = AtomicBoolean(true)
        val store = SessionStore(tmp)
        val recording = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL))
        val tracker = LiveTracker(recording.id, recording.info.startUtcMillis, recording::nowMicros)
        recording.listeners.add(tracker)
        recording.attach(FakeGnssSource(gnss, receiverOn = on::get))
        waitFor { tracker.snapshot().gnss == GnssState.Ok }

        on.set(false)
        waitFor { tracker.snapshot().gnss == GnssState.Lost }
        on.set(true)
        waitFor { tracker.snapshot().gnss == GnssState.Ok }
        Thread.sleep(200)
        assertTrue(recording.stop())

        val data = store.read(recording.id)
        val links = data.events.filter { it.kind.startsWith("demo_gnss_") }
        assertEquals(listOf(FakeGnssEvents.DISCONNECTED, FakeGnssEvents.CONNECTED), links.map { it.kind })
        assertEquals(FakeGnssEvents.REASON_SWITCHED_OFF, links.first().text)

        // Fix numbers kept counting while it was off: one jump, as long as the drop, where the events are.
        val sets = data.sampleSets.sortedBy { it.tMicros }
        val utc = sets.mapNotNull { s -> s.values.firstOrNull { it.channelId == GnssChannels.UTC_MS.id } }
            .map { it.value.toLong() }
        val steps = utc.zipWithNext { a, b -> b - a }
        val jumps = steps.filter { it != 1_000L / gnss.rateHz }
        assertEquals(1, jumps.size, "jumps $jumps")
        val dropMicros = links[1].tMicros - links[0].tMicros
        assertTrue(jumps.single() * 1_000 >= dropMicros, "a ${jumps.single()} ms jump for a $dropMicros us drop")
        val fixes = sets.filter { s -> s.values.any { it.channelId == GnssChannels.LAT.id } }
        assertTrue(fixes.none { it.tMicros in links[0].tMicros until links[1].tMicros }, "a fix while off")
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }
}
