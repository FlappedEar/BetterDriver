@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.circuits.CircuitDirectory
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.circuits.CircuitListException
import com.flappedear.betterdriver.timing.circuits.CircuitListUrl
import com.flappedear.betterdriver.timing.circuits.CircuitRefresh
import com.flappedear.betterdriver.timing.circuits.readBundledCircuits
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.detectTrack
import com.flappedear.betterdriver.timing.track.newTrackFrom
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-79 (circuit directory) and FBBD-55 (track detection). */
class CircuitsTest {
    private val bundled = CircuitList.decode(readBundledCircuits())

    /** A point [east] and [north] metres from [p]. */
    private fun near(p: GeoPoint, east: Double, north: Double) = LocalFrame(p).toGeo(Xy(east, north))

    private val torPoznan = GeoPoint(52.4178, 16.8058)

    @Test
    fun `the bundled list is the Wikidata list from FlappedEar Telemetry`() {
        assertEquals(999, bundled.circuits.size)
        assertTrue(bundled.source.startsWith("Wikidata"))
        assertTrue(bundled.circuits.count { it.country == "PL" } >= 8)
    }

    @Test
    fun `a position at a known circuit resolves to its name`() {
        assertEquals("Tor Poznań", bundled.find(near(torPoznan, 600.0, -300.0))?.name)
        assertEquals("Tor Kielce", bundled.find(GeoPoint(50.9610, 20.5390))?.name)
        // The Baltic, far from any circuit.
        assertNull(bundled.find(GeoPoint(55.5, 18.0)))
    }

    @Test
    fun `a saved track wins over the directory, the directory names the rest`() {
        val mine = Track(id = "poznan-club", name = "Poznań club", center = torPoznan, radiusMeters = 1_500.0)
        val atPoznan = detectTrack(near(torPoznan, 200.0, 100.0), listOf(mine), bundled)
        assertEquals(mine, assertIs<TrackDetection.Saved>(atPoznan).track)
        val atKielce = detectTrack(GeoPoint(50.9610, 20.5390), listOf(mine), bundled)
        val circuit = assertIs<TrackDetection.Directory>(atKielce).circuit
        assertEquals("Tor Kielce", circuit.name)
        val newTrack = newTrackFrom(circuit)
        assertEquals("Tor Kielce", newTrack.name)
        assertEquals(circuit.position, newTrack.center)
        assertEquals(circuit.id, newTrack.circuitId)
        assertEquals(TrackDetection.Unknown, detectTrack(GeoPoint(55.5, 18.0), listOf(mine), bundled))
    }

    @Test
    fun `the nearest of two saved tracks is chosen`() {
        val a = Track(id = "a", name = "A", center = near(torPoznan, -500.0, 0.0))
        val b = Track(id = "b", name = "B", center = near(torPoznan, 400.0, 0.0))
        val found = detectTrack(near(torPoznan, 100.0, 0.0), listOf(a, b), bundled)
        assertEquals("b", assertIs<TrackDetection.Saved>(found).track.id)
    }

    @Test
    fun `refresh keeps a newer list and works offline`() {
        val cache = Files.createTempDirectory("circuits").toFile()
        val directory = CircuitDirectory(cache)
        assertEquals(bundled.revision, directory.load().revision)
        val newer = """{"format":"flappedear-circuits","version":1,"revision":${bundled.revision + 1},""" +
            """"source":"test","circuits":[{"id":"x","name":"New Ring","country":"PL","lat":50.0,"lon":19.0}]}"""
        var asked = ""
        assertEquals(CircuitRefresh.Updated, directory.refresh { asked = it; newer })
        assertEquals(CircuitListUrl, asked)
        assertEquals("New Ring", directory.find(GeoPoint(50.001, 19.0))?.name)
        assertEquals(CircuitRefresh.UpToDate, directory.refresh { newer })
        assertEquals(CircuitRefresh.Failed, directory.refresh { throw IOException("offline") })
        // After a restart the kept list is used.
        assertEquals(bundled.revision + 1, CircuitDirectory(cache).load().revision)
        // Without a cache folder the newer list is used but not kept.
        assertEquals(CircuitRefresh.NotSaved, CircuitDirectory(null).apply { load() }.refresh { newer })
    }

    @Test
    fun `bad lists are refused and bad entries skipped`() {
        assertFailsWith<CircuitListException> { CircuitList.decode("nope") }
        assertFailsWith<CircuitListException> { CircuitList.decode("""{"format":"betterdriver-track","version":1}""") }
        val list = CircuitList.decode(
            """{"format":"flappedear-circuits","version":1,"revision":1,"circuits":[""" +
                """{"id":"ok","name":"Ok","lat":1.0,"lon":2.0},""" +
                """{"id":"far","name":"Far","lat":91.0,"lon":2.0},""" +
                """{"id":"noname","lat":1.0,"lon":2.0},""" +
                """{"id":"big","name":"Big","lat":1.0,"lon":2.0,"radius":50000}]}""",
        )
        assertEquals(listOf("ok"), list.circuits.map { it.id })
    }
}
