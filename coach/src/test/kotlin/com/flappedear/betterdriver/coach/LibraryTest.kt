@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.RecorderConfig
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackEvent
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryTest {
    @TempDir
    lateinit var tmp: File

    private val sim = SimCircuit()
    private val track = sim.track()

    private fun record(store: SessionStore, at: Track?, paces: List<SimCircuit.Pace>, startUtc: Long): String {
        val rec = Recorder(store, RecorderConfig(queueCapacity = 200_000))
            .start(SessionInfo(startUtcMillis = startUtc, channels = GnssChannels.ALL))
        rec.event(TrackEvent.KIND, TrackEvent.text(at?.let { TrackDetection.Saved(it, 0.0) } ?: TrackDetection.Unknown))
        for (f in sim.drive(paces).fixes) {
            rec.submit(
                SampleSet(
                    f.tMicros,
                    listOf(
                        ChannelValue(GnssChannels.LAT.id, f.lat),
                        ChannelValue(GnssChannels.LON.id, f.lon),
                        ChannelValue(GnssChannels.SPEED_KMH.id, f.speedKmh!!),
                    ),
                ),
            )
        }
        rec.stop()
        return rec.id
    }

    @Test
    fun `a reference is built from one earlier session and rebuilt when another appears`() {
        val store = SessionStore(File(tmp, "sessions"))
        val library = CoachLibrary(store, File(tmp, "coach"))
        assertNull(library.reference(track))
        val first = record(store, track, List(3) { SimCircuit.Pace() }, 1_000L)
        record(store, null, List(2) { SimCircuit.Pace() }, 2_000L)
        val ref = assertNotNull(library.reference(track))
        assertEquals(listOf(first), ref.sessions)
        assertEquals(4, ref.corners.size)
        // Nothing new: the saved reference is used as it is.
        assertEquals(ref, library.reference(track))
        val second = record(store, track, List(2) { SimCircuit.Pace(kmh = List(4) { 3.0 }) }, 3_000L)
        val rebuilt = assertNotNull(library.reference(track))
        assertEquals(setOf(first, second), rebuilt.sessions.toSet())
        assertTrue(rebuilt.lapMicros < ref.lapMicros)
        assertEquals(second, rebuilt.lapSession)
        // New lines: rebuilt for the new revision.
        val edited = track.copy(revision = 2)
        assertEquals(2, assertNotNull(library.reference(edited)).trackRevision)
        // The session being recorded is left out.
        assertEquals(listOf(first), assertNotNull(library.reference(edited, exclude = second)).sessions)
    }
}
