@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.timing.circuits.Circuit
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.TrackJson
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A track made on the iPhone at a place the app does not know times laps from the next session (FBBD-336). */
class TrackLibraryTest {
    private val base = NSTemporaryDirectory() + "track-library-test-" + NSUUID().UUIDString
    private val sessions = "$base/sessions"
    private val tracks = "$base/tracks"
    private val library = TrackLibrary(tracks, sessions)
    private val gnss = FakeGnss()
    private val circuits =
        CircuitList(1, "test", listOf(Circuit("Q1", "Ring circuit", "PL", GeoPoint(gnss.centerLat, gnss.centerLon))))
    private val info = SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL)

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    /** Records three and a half laps of 40 s at 25 Hz; returns what the track screen showed at the end. */
    private fun record(): LiveScreen {
        val session = LiveSession.start(sessions, tracks, CircuitList.Empty, info, receiverHasG = false)
        for (n in 0L..(25 * 140)) session.submit(SampleSet(n * 40_000, gnss.fix(n)))
        return session.screen().also { session.stop() }
    }

    @Test
    fun aNewTrackHereIsSavedAndListed() {
        val made = library.createAt(gnss.centerLat, gnss.centerLon, "  Moto ring ")
        assertEquals("Moto ring", made.name)
        assertEquals(1, made.revision)
        assertEquals(listOf(made.id), library.list().map { it.id })
        assertEquals(listOf(made.id), TrackFiles.read(tracks).map { it.id })
        val again = library.save(made.copy(name = "Renamed"))
        assertEquals(2, again.revision)
        assertEquals("Renamed", library.get(made.id)?.name)
        assertTrue(library.delete(made.id))
        assertNull(library.get(made.id))
        assertTrue(library.list().isEmpty())
    }

    @Test
    fun whereYouAreNamesTheTrackThenTheCircuit() {
        val fromList = library.here(gnss.centerLat, gnss.centerLon, circuits)
        assertEquals("Ring circuit", fromList.circuit?.name)
        assertNull(fromList.track)
        assertEquals("here", fromList.away)
        val made = library.createFrom(assertNotNull(fromList.circuit))
        assertEquals("Ring circuit", made.name)
        assertEquals(made.id, library.here(gnss.centerLat, gnss.centerLon, circuits).track?.id)
        val nothing = library.here(0.0, 0.0, circuits)
        assertNull(nothing.track)
        assertNull(nothing.circuit)
    }

    @Test
    fun aLineFromTheLapGivesLiveLapsInTheNextSession() {
        // Session 1 at an unknown place: laps from a provisional line (FBBD-335), and no track saved.
        assertNotNull(record().timing)
        assertTrue(library.list().isEmpty())
        val track = library.createAt(gnss.centerLat, gnss.centerLon, "Moto ring")
        val found = library.editorSessions(track)
        assertEquals(1, found.size)
        val editor = TrackEditor(track, found)
        assertEquals(PlaceMethod.FromLap, editor.method)
        assertFalse(editor.changed)
        editor.select(0)
        assertEquals("Start / finish", editor.title)
        val gate = gnss.startFinish
        editor.onTap((gate.lat1 + gate.lat2) / 2, (gate.lon1 + gate.lon2) / 2)
        assertNotNull(editor.selectedLine)
        editor.done()
        assertTrue(editor.changed)
        assertTrue(editor.lines().first().placed)
        assertTrue("1 session" in editor.retimeNote())
        val sent = editor.track
        editor.saved(sent, library.save(sent))
        assertFalse(editor.changed)

        // Session 2: timed from the first fix.
        val timing = assertNotNull(record().timing)
        assertEquals(40_000L, timing.bestMillis)
    }

    @Test
    fun twoTapsAreTurnedTheWayTheLapGoesAndFlipTurnsThemBack() {
        record()
        val track = library.createAt(gnss.centerLat, gnss.centerLon, "Moto ring")
        val editor = TrackEditor(track, library.editorSessions(track))
        editor.select(0)
        editor.pick(PlaceMethod.TwoTaps)
        val gate = gnss.startFinish
        // The edges tapped right to left: the line is turned so the lap crosses it forwards.
        editor.onTap(gate.lat2, gate.lon2)
        assertEquals("Now tap the other edge.", editor.hint)
        editor.onTap(gate.lat1, gate.lon1)
        val placed = assertNotNull(editor.selectedLine)
        assertEquals(placed, assertNotNull(editor.path).oriented(placed))
        editor.flip()
        assertEquals(placed.flipped(), editor.selectedLine)
        editor.cancel()
        assertNull(editor.track.startFinish)
        assertFalse(editor.changed)
    }

    @Test
    fun withoutASessionLinesArePlacedWithTwoTaps() {
        val track = library.createAt(gnss.centerLat, gnss.centerLon, "Moto ring")
        val editor = TrackEditor(track, library.editorSessions(track))
        assertEquals(PlaceMethod.TwoTaps, editor.method)
        editor.pick(PlaceMethod.FromLap)
        assertEquals(PlaceMethod.TwoTaps, editor.method)
        assertTrue(editor.retimeNote().startsWith("No sessions here yet."))
        editor.addSector()
        assertEquals("Sector line 1", editor.title)
        editor.onTap(gnss.centerLat, gnss.centerLon)
        editor.onTap(gnss.centerLat + 0.0002, gnss.centerLon)
        editor.done()
        val circuit = listOf("Start / finish", "Sector line 1", "Pit entry", "Pit exit")
        assertEquals(circuit, editor.lines().map { it.label })
        editor.setPointToPoint(true)
        assertEquals(listOf("Start", "Sector line 1", "Finish"), editor.lines().map { it.label })
        assertNotNull(editor.kindNote)
    }

    @Test
    fun aFreshInstallHasNoTracksAndNothingFails() {
        // Neither folder exists yet: first start, no location, no sessions.
        assertTrue(library.list().isEmpty())
        assertTrue(TrackFiles.read(tracks).isEmpty())
        assertNull(library.get("nope"))
        assertTrue(library.delete("nope")) // nothing there: already gone
        assertNull(library.here(gnss.centerLat, gnss.centerLon, CircuitList.Empty).track)
        val track = library.createAt(gnss.centerLat, gnss.centerLon, "Moto ring")
        assertTrue(library.editorSessions(track).isEmpty())
    }

    @Test
    fun badIdsAndNamesAreRefusedWithoutACrash() {
        assertNull(library.get("../escape"))
        assertNull(library.get(""))
        assertFalse(library.delete("../escape"))
        assertFalse(library.delete(""))
        // Swift checks validName first; a name it let through still only fails the save.
        assertFalse(library.validName("   "))
        assertFailsWith<TrackSaveException> { library.createAt(gnss.centerLat, gnss.centerLon, "   ") }
        assertFailsWith<TrackSaveException> { library.createAt(gnss.centerLat, gnss.centerLon, "x".repeat(500)) }
        assertTrue(library.list().isEmpty())
    }

    @Test
    fun damagedTrackFilesAreSkipped() {
        val good = library.createAt(gnss.centerLat, gnss.centerLon, "Moto ring")
        write("garbage", "{not json")
        write("empty", "")
        write("other", """{"format":"something else","version":1}""")
        // A track file whose track breaks the rules (a blank name): decoding throws, the list skips it.
        val text = TrackJson.encode(good.copy(id = "bad"))
        val blank = text.replace("\"name\": \"Moto ring\"", "\"name\": \"\"")
        assertTrue(blank != text)
        write("bad", blank)
        assertEquals(listOf(good.id), library.list().map { it.id })
        assertEquals(listOf(good.id), TrackFiles.read(tracks).map { it.id })
        assertNull(library.get("garbage"))
        assertNull(library.get("bad"))
        // A damaged file under a track's name is replaced by the next save.
        write(good.id, "{not json")
        assertNull(library.get(good.id))
        library.save(good)
        assertEquals(good.id, library.get(good.id)?.id)
    }

    @Test
    fun tracksSavedByEarlierBuildsAreKeptUnderTheSharedStoresNames() {
        val made = library.createAt(gnss.centerLat, gnss.centerLon, "Moto ring")
        val old = made.copy(id = "earlier-build", name = "Old ring")
        write(old.id, TrackJson.encode(old), suffix = ".json")
        assertNull(library.get(old.id))
        // The next start of the app (a new library) moves it to <id>.track.json, as Android names it.
        val next = TrackLibrary(tracks, sessions)
        assertEquals(old, next.get(old.id))
        assertEquals(listOf("Moto ring", "Old ring"), next.list().map { it.displayName })
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$tracks/${old.id}.json"))
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath("$tracks/${old.id}.track.json"))
    }

    @Test
    fun oddTapsNeverEndTheEditor() {
        record()
        val track = library.createAt(gnss.centerLat, gnss.centerLon, "Moto ring")
        val editor = TrackEditor(track, library.editorSessions(track))
        editor.select(99)
        editor.flip()
        editor.remove()
        editor.moveEnd(true, 0.0, 0.0)
        editor.select(0)
        editor.pick(PlaceMethod.TwoTaps)
        // Both taps on one spot, then one end dragged onto the other: a line with no length.
        editor.onTap(gnss.centerLat, gnss.centerLon)
        editor.onTap(gnss.centerLat, gnss.centerLon)
        val line = assertNotNull(editor.selectedLine)
        editor.moveEnd(false, line.left.lat, line.left.lon)
        editor.flip()
        editor.done()
        editor.retimeNote()
        library.save(editor.track)
        assertNotNull(library.get(track.id))
        // From your lap tapped far off the lap places nothing.
        editor.select(0)
        editor.pick(PlaceMethod.FromLap)
        editor.onTap(0.0, 0.0)
        editor.cancel()
    }

    /** Writes [text] as track [id]'s file, as the shared TrackStore names it ([suffix]) or as earlier builds did. */
    private fun write(id: String, text: String, suffix: String = ".track.json") {
        NSFileManager.defaultManager.createDirectoryAtPath(tracks, true, null, null)
        NSString.create(string = text)
            .writeToFile("$tracks/$id$suffix", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }
}
