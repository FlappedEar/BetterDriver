@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.demo.DemoTrack
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.timing.circuits.CircuitList
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The iPhone's Demo track drives Arek's built-in laps into a recording as Android's does (FBBD-373):
 * the choice, the feed from the bundled replay, and a recording that times laps from it.
 */
class DemoTrackCoreTest {
    private val base = NSTemporaryDirectory() + "demo-track-core-" + NSUUID().UUIDString
    private val sessions = "$base/sessions"
    private val tracks = "$base/tracks"

    init {
        listOf(sessions, tracks).forEach { NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null) }
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    /** Collects the feed's fixes on a clock the test moves. */
    private class ClockSink : SampleSink {
        var now = 0L
        val sets = mutableListOf<SampleSet>()
        val channels = mutableListOf<ChannelDef>()
        override fun defineChannel(def: ChannelDef) {
            channels += def
        }
        override fun submit(set: SampleSet) {
            sets += set
        }
        override fun event(kind: String, text: String) = Unit
        override fun nowMicros() = now
    }

    @Test
    fun theDrivesListAndTheChoiceAreAndroids() {
        val choices = DemoTrackCore.choices(sessions)
        assertEquals(listOf(null, DemoTrack.SilesiaRing, DemoTrack.Jastrzab), choices.map { it.id })
        assertEquals("Demo circuit (made up)", DemoTrackCore.chosenLabel(base))
        DemoTrackCore.choose(base, DemoTrack.Jastrzab)
        assertEquals(DemoTrack.Jastrzab, DemoTrackCore.chosen(base))
        assertEquals("Autodrom Jastrząb", DemoTrackCore.chosenLabel(base))
        assertEquals(DemoTrack.Jastrzab, DemoTrackCore.current(choices, DemoTrackCore.chosen(base)).id)
    }

    @Test
    fun theJastrzabReplayTimesLapsInARecording() {
        DemoTrackCore.choose(base, DemoTrack.Jastrzab)
        val sink = ClockSink()
        val feed = DemoTrackCore.feed(sink, base, sessions, tracks, DemoAssetsDir)
        feed.start()
        // Seven minutes of fixes, a tick every 40 ms.
        while (sink.now < 7 * 60 * 1_000_000L) {
            sink.now += 40_000L
            feed.advance()
        }
        assertTrue(TrackFiles.read(tracks).any { it.id == "jastrzab" }, "the replay's track is saved")
        val info = SessionInfo(1_790_000_000_000L, devices = listOf("Demo track"), channels = sink.channels)
        val circuits = CircuitList(1, "test", emptyList())
        val session = LiveSession.start(sessions, tracks, circuits, info, receiverHasG = false)
        try {
            sink.channels.forEach(session::defineChannel)
            sink.sets.forEach(session::submit)
            val timing = assertNotNull(session.screen().timing, "timed at the saved track")
            assertTrue(timing.lapNumber >= 2, "lap ${timing.lapNumber}")
            assertNotNull(timing.bestMillis, "a lap timed")
        } finally {
            session.stop()
        }
    }
}
