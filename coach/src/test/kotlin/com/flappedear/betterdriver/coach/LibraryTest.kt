@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.RecorderConfig
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackEvent
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryTest {
    @TempDir
    lateinit var tmp: File

    private val sim = SimCircuit()
    private val track = sim.track()

    /** Another circuit, 200 km away. */
    private val elsewhere = SimCircuit(GeoPoint(50.4, 19.0))

    private fun record(
        store: SessionStore,
        at: Track?,
        paces: List<SimCircuit.Pace>,
        startUtc: Long,
        circuit: SimCircuit = sim,
    ): String = record(store, at, circuit.drive(paces).fixes, startUtc)

    private fun record(store: SessionStore, at: Track?, fixes: List<CoachFix>, startUtc: Long): String {
        val rec = Recorder(store, RecorderConfig(queueCapacity = 200_000))
            .start(SessionInfo(startUtcMillis = startUtc, channels = GnssChannels.ALL))
        rec.event(TrackEvent.KIND, TrackEvent.text(at?.let { TrackDetection.Saved(it, 0.0) } ?: TrackDetection.Unknown))
        for (f in fixes) {
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

    private fun place(id: String): SessionPlace =
        assertNotNull(SessionPlaces(File(tmp, "coach/places.json")).read()[id])

    @Test
    fun `a reference is built from one earlier session and rebuilt when another appears`() {
        val store = SessionStore(File(tmp, "sessions"))
        val library = CoachLibrary(store, File(tmp, "coach")) { listOf(track) }
        assertNull(library.reference(track))
        val first = record(store, track, List(3) { SimCircuit.Pace() }, 1_000L)
        // Laps somewhere else, with no track: not this track's.
        record(store, null, List(2) { SimCircuit.Pace() }, 2_000L, elsewhere)
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

    @Test
    fun `a reference is rebuilt when the track's line plan changes`() {
        val store = SessionStore(File(tmp, "sessions"))
        var plan: PlannedTargets? = null
        val library = CoachLibrary(store, File(tmp, "coach"), plans = { plan }) { listOf(track) }
        record(store, track, List(3) { SimCircuit.Pace() }, 1_000L)
        assertNull(assertNotNull(library.reference(track)).planSession)
        plan = PlannedTargets("p1", emptyList())
        assertEquals("p1", assertNotNull(library.reference(track)).planSession)
        plan = PlannedTargets("p2", emptyList())
        assertEquals("p2", assertNotNull(library.reference(track)).planSession)
        // The same session's plan made again with other targets (corner identity fixed, more sessions).
        plan = PlannedTargets("p2", listOf(PlannedTarget("c1", "p1", 61.0)))
        assertEquals(plan?.key, assertNotNull(library.reference(track)).planKey)
    }

    @Test
    fun `a reference counts as the same day for 12 hours after its last session ended`() {
        val store = SessionStore(File(tmp, "sessions"))
        val library = CoachLibrary(store, File(tmp, "coach")) { listOf(track) }
        val start = 1_800_000_000_000L
        val id = record(store, track, List(2) { SimCircuit.Pace() }, start)
        val ref = assertNotNull(library.reference(track))
        val ended = start + assertNotNull(store.list().single { it.id == id }.durationMicros) / 1_000
        val hour = 60 * 60 * 1_000L
        assertTrue(library.drivenSameDay(ref, ended + 5 * 60 * 1_000L))
        assertTrue(library.drivenSameDay(ref, ended + 12 * hour))
        assertFalse(library.drivenSameDay(ref, ended + 12 * hour + 1))
        // A clock behind the session's end is not trusted.
        assertFalse(library.drivenSameDay(ref, start))
    }

    @Test
    fun `a session not yet recovered ended at its last sample`() {
        val store = SessionStore(File(tmp, "sessions"))
        val library = CoachLibrary(store, File(tmp, "coach")) { listOf(track) }
        val now = System.currentTimeMillis()
        val fixes = sim.drive(List(2) { SimCircuit.Pace() }).fixes
        val driveMillis = fixes.last().tMicros / 1_000
        // Never stopped cleanly: no length is known. Started more than 12 hours ago, but its last
        // sample is less than 12 hours ago.
        val id = record(store, track, fixes, now - 12 * 60 * 60 * 1_000L - driveMillis / 2)
        val ref = assertNotNull(library.reference(track))
        assertTrue(File(store.dir(id), SessionFiles.FINISHED).delete())
        assertNull(store.list().single { it.id == id }.durationMicros)
        assertTrue(library.drivenSameDay(ref, now))
    }

    @Test
    fun `a short session copied in without its length counts from its samples, not its file times`() {
        val store = SessionStore(File(tmp, "sessions"))
        val library = CoachLibrary(store, File(tmp, "coach")) { listOf(track) }
        val now = System.currentTimeMillis()
        // Driven 14 hours ago for a few minutes and imported now: its files are new, its samples are not.
        val id = record(store, track, List(2) { SimCircuit.Pace() }, now - 14 * 60 * 60 * 1_000L)
        val ref = assertNotNull(library.reference(track))
        assertTrue(File(store.dir(id), SessionFiles.FINISHED).delete())
        assertFalse(library.drivenSameDay(ref, now))
    }

    @Test
    fun `a session recorded with no track feeds the reference of a track made from it`() {
        val store = SessionStore(File(tmp, "sessions"))
        val saved = mutableListOf<Track>()
        val library = CoachLibrary(store, File(tmp, "coach")) { saved.toList() }
        // Recorded before the track existed: its track event says "unknown".
        val drive = record(store, null, List(3) { SimCircuit.Pace() }, 1_000L)
        library.refresh()
        assertNull(place(drive).matched)
        // Make a track from this session (FBBD-151): a new id, with lines where the session drove.
        val made = track.copy(id = "made-from-session")
        saved += made
        library.refresh()
        assertEquals(made.id, place(drive).matched)
        val ref = assertNotNull(library.references.get(made.id))
        assertEquals(listOf(drive), ref.sessions)
        assertEquals(4, ref.corners.size)
        // The coach starting at the new track gets it as it is.
        assertEquals(ref, CoachLibrary(store, File(tmp, "coach")) { saved.toList() }.reference(made))
    }

    @Test
    fun `a session whose track was deleted feeds the track at its place`() {
        val store = SessionStore(File(tmp, "sessions"))
        val gone = track.copy(id = "deleted")
        val again = track.copy(id = "made-again")
        val id = record(store, gone, List(2) { SimCircuit.Pace() }, 1_000L)
        val library = CoachLibrary(store, File(tmp, "coach")) { listOf(again) }
        assertEquals(listOf(id), assertNotNull(library.reference(again)).sessions)
        // While the track it recorded exists, the session is that track's only.
        val both = CoachLibrary(store, File(tmp, "coach")) { listOf(gone, again) }
        assertNull(both.reference(again))
        assertEquals(listOf(id), assertNotNull(both.reference(gone)).sessions)
    }

    @Test
    fun `a drive past the track with no timed lap there does not count`() {
        val store = SessionStore(File(tmp, "sessions"))
        val library = CoachLibrary(store, File(tmp, "coach")) { listOf(track) }
        // Round the far end of the circuit, never over the line.
        val fixes = (0..400).map { i ->
            val g = sim.frame.toGeo(sim.xy(200.0 + i * 2.0))
            CoachFix(1_000_000L + i * 100_000L, g.lat, g.lon, 72.0)
        }
        val past = record(store, null, fixes, 1_000L)
        assertNull(library.reference(track))
        assertTrue(place(past).read)
        assertNull(place(past).matched)
    }

    @Test
    fun `matches are kept until a track near the session changes`() {
        val store = SessionStore(File(tmp, "sessions"))
        var saved = listOf(track)
        val library = CoachLibrary(store, File(tmp, "coach")) { saved }
        val drive = record(store, null, List(2) { SimCircuit.Pace() }, 1_000L)
        library.refresh()
        val first = place(drive)
        assertEquals(track.id, first.matched)
        // A track saved far away is no reason to read the session again.
        saved = saved + elsewhere.track().copy(id = "far")
        library.refresh()
        assertEquals(first, place(drive))
        // Lines edited: matched again with the new lines, and the reference follows them.
        val edited = track.copy(revision = 2)
        saved = listOf(edited)
        library.refresh()
        assertNotEquals(first.against, place(drive).against)
        assertEquals(track.id, place(drive).matched)
        assertEquals(2, assertNotNull(library.references.get(track.id)).trackRevision)
        // Deleted: the session no longer matches anything.
        saved = emptyList()
        library.refresh()
        assertNull(place(drive).matched)
        // Back, then the session deleted: the reference is rebuilt without it.
        saved = listOf(edited)
        assertNotNull(library.reference(edited))
        store.delete(drive)
        assertNull(library.reference(edited))
        assertNull(library.references.get(track.id))
        assertNull(SessionPlaces(File(tmp, "coach/places.json")).read()[drive])
    }

    @Test
    fun `a refresh asked to stop builds nothing, and the next one finishes`() {
        val store = SessionStore(File(tmp, "sessions"))
        val library = CoachLibrary(store, File(tmp, "coach")) { listOf(track) }
        record(store, track, List(2) { SimCircuit.Pace() }, 1_000L)
        library.refresh { true }
        assertNull(library.references.get(track.id))
        library.refresh()
        assertNotNull(library.references.get(track.id))
    }

    @Test
    fun `a refresh stopped while building keeps the reference as it was`() {
        val store = SessionStore(File(tmp, "sessions"))
        val library = CoachLibrary(store, File(tmp, "coach")) { listOf(track) }
        val first = record(store, track, List(2) { SimCircuit.Pace() }, 1_000L)
        library.refresh()
        val before = assertNotNull(library.references.get(track.id))
        record(store, track, List(2) { SimCircuit.Pace(kmh = List(4) { 3.0 }) }, 2_000L)
        record(store, track, List(2) { SimCircuit.Pace() }, 3_000L)
        // Asked once per session while matching and once before the track; a recording starts after
        // the build has read its first session.
        var asked = 0
        library.refresh { ++asked > 3 + 1 + 1 }
        assertTrue(asked > 5)
        assertEquals(before, library.references.get(track.id))
        assertEquals(listOf(first), before.sessions)
        library.refresh()
        assertEquals(3, assertNotNull(library.references.get(track.id)).sessions.size)
    }

    @Test
    fun `a refresh removes the references of deleted tracks`() {
        val store = SessionStore(File(tmp, "sessions"))
        var saved = listOf(track)
        val library = CoachLibrary(store, File(tmp, "coach")) { saved }
        record(store, track, List(2) { SimCircuit.Pace() }, 1_000L)
        library.refresh()
        val ref = assertNotNull(library.references.get(track.id))
        // Deleted while a build for it was under way, which wrote the reference back.
        saved = emptyList()
        library.references.save(ref)
        library.refresh()
        assertNull(library.references.get(track.id))
        assertEquals(emptyList(), library.references.trackIds())
    }

    @Test
    fun `an area reaches only tracks whose radius gets to it`() {
        val area = assertNotNull(Area.of(sim.drive(listOf(SimCircuit.Pace())).fixes.asSequence()))
        assertTrue(area.reaches(track))
        assertTrue(!area.reaches(elsewhere.track()))
        assertNull(Area.of(emptySequence()))
    }
}
