@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackFileVersion
import com.flappedear.betterdriver.timing.track.TrackFormatException
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackKind
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.timing.track.TrackStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-54: track model, file format and store. */
class TrackTest {
    private val poznan = Track(
        id = "tor-poznan",
        name = "Tor Poznań",
        layout = "Full",
        country = "PL",
        center = GeoPoint(52.4178, 16.8058),
        startFinish = TrackLine.across(GeoPoint(52.4170, 16.8050), 90.0),
        sectorLines = listOf(
            TrackLine.across(GeoPoint(52.4190, 16.8070), 0.0),
            TrackLine.across(GeoPoint(52.4160, 16.8080), 200.0),
        ),
        pitEntry = TrackLine(GeoPoint(52.4172, 16.8040), GeoPoint(52.4171, 16.8041)),
        circuitId = "wd:Q9360123",
    )

    @Test
    fun `json round trip keeps every field`() {
        val text = TrackJson.encode(poznan)
        assertTrue(text.contains("\"format\": \"betterdriver-track\""))
        assertEquals(poznan, TrackJson.decode(text))
    }

    @Test
    fun `refuses files that are not tracks or are too new`() {
        assertFailsWith<TrackFormatException> { TrackJson.decode("not json") }
        assertFailsWith<TrackFormatException> { TrackJson.decode("[]") }
        assertFailsWith<TrackFormatException> { TrackJson.decode("""{"format":"flappedear-circuits","version":1}""") }
        val newer = TrackJson.encode(poznan).replace("\"version\": 1", "\"version\": ${TrackFileVersion + 1}")
        assertFailsWith<TrackFormatException> { TrackJson.decode(newer) }
        val badLat = TrackJson.encode(poznan).replace("52.4178", "152.4178")
        assertFailsWith<TrackFormatException> { TrackJson.decode(badLat) }
    }

    @Test
    fun `reads a track file as builds before point-to-point tracks wrote it`() {
        // As 0.2.x wrote it (before FBBD-155): version 1, no kind and no finish line.
        val old = """
            {
                "format": "betterdriver-track",
                "version": 1,
                "track": {
                    "id": "tor-poznan",
                    "name": "Tor Poznań",
                    "center": { "lat": 52.4178, "lon": 16.8058 },
                    "country": "PL",
                    "layout": "Full",
                    "radiusMeters": 2000.0,
                    "startFinish": {
                        "left": { "lat": 52.417, "lon": 16.8048 },
                        "right": { "lat": 52.417, "lon": 16.8052 }
                    },
                    "sectorLines": [],
                    "pitEntry": null,
                    "pitExit": null,
                    "circuitId": "wd:Q9360123",
                    "revision": 4,
                    "updatedUtcMillis": 1786000000000
                }
            }
        """.trimIndent()
        val track = TrackJson.decode(old)
        assertEquals(TrackKind.Circuit, track.kind)
        assertNull(track.finish)
        assertTrue(track.timeable)
        assertEquals(4, track.revision)
        assertEquals(TrackLine(GeoPoint(52.417, 16.8048), GeoPoint(52.417, 16.8052)), track.startFinish)
        // Written back as version 1, so it still moves to those builds.
        assertTrue(TrackJson.encode(track).contains("\"version\": 1"))
    }

    @Test
    fun `reads files with fields it does not know`() {
        val withExtra = TrackJson.encode(poznan).replace("\"name\":", "\"surface\": \"asphalt\",\n    \"name\":")
        assertEquals(poznan, TrackJson.decode(withExtra))
    }

    @Test
    fun `tracks survive a restart and edits bump the revision`() {
        val dir = Files.createTempDirectory("tracks").toFile()
        val saved = TrackStore(dir).save(poznan, nowUtcMillis = 1_000)
        assertEquals(1, saved.revision)
        val edited = saved.copy(sectorLines = saved.sectorLines.take(1))
        TrackStore(dir).save(edited, nowUtcMillis = 2_000)
        // A new store on the same folder, as after an app restart.
        val again = TrackStore(dir)
        val read = again.get("tor-poznan")!!
        assertEquals(1, read.sectorLines.size)
        assertEquals(2, read.revision)
        assertEquals(2_000, read.updatedUtcMillis)
        assertEquals(listOf("tor-poznan"), again.all().map { it.id })
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertTrue(again.delete("tor-poznan"))
        assertNull(again.get("tor-poznan"))
    }

    @Test
    fun `a damaged file is set aside, not lost or overwritten`() {
        val dir = Files.createTempDirectory("tracks").toFile()
        val store = TrackStore(dir)
        store.save(poznan)
        File(dir, "broken.track.json").writeText("{ half a track")
        assertEquals(listOf("tor-poznan"), store.all().map { it.id })
        val aside = store.setAside()
        assertEquals(1, aside.size)
        assertEquals("{ half a track", aside.single().readText())
        assertEquals(listOf("tor-poznan"), store.all().map { it.id })
    }

    @Test
    fun `rejects bad ids and names`() {
        assertFailsWith<IllegalArgumentException> { poznan.copy(id = "../etc") }
        assertFailsWith<IllegalArgumentException> { poznan.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { poznan.copy(radiusMeters = 0.0) }
    }
}
