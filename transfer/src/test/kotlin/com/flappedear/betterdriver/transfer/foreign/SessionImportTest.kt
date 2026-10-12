@file:Suppress("MagicNumber") // Fixture positions, sizes and times.

package com.flappedear.betterdriver.transfer.foreign

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import com.flappedear.betterdriver.timing.circuits.Circuit
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackEvent
import com.flappedear.betterdriver.timing.track.TrackLine
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FBBD-164. The fixtures (src/test/resources/import) drive a circle of 80 m radius around
 * 52.4170 N 16.8130 E at 20 m/s (72 km/h, a lap every 25.13 s) from 10:42:00 UTC on 12 Oct 2026,
 * starting 0.3 rad before the start/finish line due north: a Racelogic VBO at 25 Hz for 60 s,
 * a phone GPX at 1 Hz in two segments (0–60 s and 90–150 s), and a RaceChrono CSV v3 export at
 * 10 Hz for 60 s with OBD-only rows in between.
 */
class SessionImportTest {
    @TempDir
    lateinit var tmp: File

    private val store by lazy { SessionStore(File(tmp, "sessions")) }
    private val staging by lazy { File(tmp, "staging") }
    private val importer by lazy { SessionImport(store, staging) }

    private val centerLat = 52.4170
    private val centerLon = 16.8130
    private val radius = 80.0
    private val lapSeconds = 2 * Math.PI * radius / 20.0
    private val start = Instant.parse("2026-10-12T10:42:00Z").toEpochMilli()

    /** The circle as a saved track: the start/finish line across it due north, outside end on the left. */
    private val track = Track(
        id = "circle",
        name = "Circle",
        center = GeoPoint(centerLat, centerLon),
        radiusMeters = 500.0,
        startFinish = TrackLine(
            GeoPoint(centerLat + (radius + 15) / 111_320.0, centerLon),
            GeoPoint(centerLat + (radius - 15) / 111_320.0, centerLon),
        ),
    )

    private fun fixture(name: String): InputStream =
        checkNotNull(javaClass.getResourceAsStream("/import/$name")) { "missing fixture $name" }

    private fun bytes(name: String) = fixture(name).use { it.readBytes() }

    private fun import(
        input: InputStream,
        name: String = "file",
        tracks: List<Track> = listOf(track),
        directory: CircuitList = CircuitList.Empty,
    ) = input.use { importer.import(it, name, tracks, directory, today = LocalDate.of(2026, 10, 12)) }

    private fun values(data: SessionData, channelId: Int) =
        data.sampleSets.mapNotNull { s -> s.values.firstOrNull { it.channelId == channelId }?.value }

    private fun channel(data: SessionData, name: String) = data.channels.values.first { it.name == name }

    private fun assertNothingLeft() {
        assertTrue(store.list().isEmpty(), "no session added")
        assertTrue(staging.listFiles().orEmpty().isEmpty(), "staging cleaned up")
    }

    @Test
    fun `formats are detected by content, not by name`() {
        val vboHead = bytes("racelogic-25hz.vbo").copyOf(ForeignFormat.PeekBytes)
        assertEquals(ForeignFormat.VBO, ForeignFormat.detect(vboHead))
        assertEquals(ForeignFormat.GPX, ForeignFormat.detect(bytes("trackday-1hz.gpx").copyOf(2_000)))
        assertEquals(ForeignFormat.RACECHRONO_CSV, ForeignFormat.detect(bytes("racechrono-v3.csv").copyOf(4_000)))
        // A RaceChrono CSV cut down to its columns row, and a GPX starting with a byte order mark.
        val columnsOnly = "Timestamp,Latitude,Longitude\n1,2,3".toByteArray()
        assertEquals(ForeignFormat.RACECHRONO_CSV, ForeignFormat.detect(columnsOnly))
        assertEquals(ForeignFormat.GPX, ForeignFormat.detect("﻿<gpx version=\"1.1\">".toByteArray()))
        assertNull(ForeignFormat.detect("BDBUNDLE\u0000\u0000\u0000\u0001".toByteArray()))
        assertNull(ForeignFormat.detect("{\"format\":\"betterdriver-track\"}".toByteArray()))
        assertNull(ForeignFormat.detect("a,b,c\n1,2,3".toByteArray()))
        assertNull(ForeignFormat.detect("<html><body/></html>".toByteArray()))
        assertNull(ForeignFormat.detect(ByteArray(0)))
        // Named .gpx, but a VBO inside: imported as the VBO it is.
        val made = import(fixture("racelogic-25hz.vbo"), "session.gpx")
        assertEquals(ForeignFormat.VBO, made.format)
    }

    @Test
    fun `a 25 Hz Racelogic VBO becomes a finished session marked as imported`() {
        val made = import(fixture("racelogic-25hz.vbo"), "racelogic-25hz.vbo")
        assertEquals(1_500, made.points)
        assertEquals(0, made.skipped)
        val summary = store.list().single()
        assertEquals(made.id, summary.id)
        assertEquals(SessionState.FINISHED, summary.state)
        assertEquals("VBO", summary.info.imported?.format)
        assertEquals("racelogic-25hz.vbo", summary.info.imported?.fileName)
        assertEquals(start, summary.info.startUtcMillis)
        assertEquals(59_960_000L, summary.durationMicros)

        val data = store.read(made.id)
        assertEquals(0L, data.sampleSets.first().tMicros)
        assertEquals(40_000L, data.sampleSets[1].tMicros)
        assertEquals(start.toDouble(), values(data, GnssChannels.UTC_MS.id).first())
        val lat = values(data, GnssChannels.LAT.id).first()
        val lon = values(data, GnssChannels.LON.id).first()
        // Minutes to degrees, longitude back to positive east.
        assertEquals(centerLat + radius * cos(-0.3) / 111_320.0, lat, 1e-6)
        assertTrue(lon in 16.80..16.82, "east longitude $lon")
        assertTrue(values(data, GnssChannels.SPEED_KMH.id).all { abs(it - 72.0) < 1e-6 })
        // 75 = 11 satellites with the DGPS flag (64).
        assertTrue(values(data, GnssChannels.SATS.id).all { it == 11.0 })
        assertEquals(-0.51, values(data, SessionImport.AccelLateral.id).first(), 1e-9)
        assertEquals(0.0, values(data, SessionImport.AccelForward.id).first(), 1e-9)
        val vertical = channel(data, "vert-vel")
        assertEquals("kmh", vertical.unit)
        assertEquals(1_500, values(data, vertical.id).size)
        assertTrue(data.channels.values.none { it.name.startsWith("avi") }, "video columns dropped")
        assertEquals("imported", data.end?.reason)
    }

    @Test
    fun `import through laps - the track is detected and laps are timed`() {
        for (name in listOf("racelogic-25hz.vbo", "racechrono-v3.csv")) {
            val made = import(fixture(name), name)
            val detected = made.detection as TrackDetection.Saved
            assertEquals(track.id, detected.track.id)
            val data = store.read(made.id)
            assertEquals("Circle", data.info.track)
            assertEquals(track.startFinish?.left?.lat, data.info.startFinish?.lat1)
            // Logged as a recording logs it, so analysis, review and sharing find the track.
            assertEquals(track.id, TrackEvent.savedTrackId(data.events))
            val laps = replayLaps(made.id, data.sampleSets, track)
            val timed = laps.laps.filter { it.lap.kind == LapKind.Timed }
            assertEquals(2, timed.size, name)
            timed.forEach { assertEquals(lapSeconds, it.lap.timeMicros / 1e6, 0.05, name) }
            assertEquals(timed.size, laps.book.laps.count { it.kind == LapKind.Timed })
        }
    }

    @Test
    fun `away from saved tracks the directory names the place, as for a recording`() {
        val circuit = Circuit("tor-poznan", "Tor Poznań", "PL", GeoPoint(centerLat, centerLon), 2_000.0)
        val directory = CircuitList(1, "", listOf(circuit))
        val made = import(fixture("racelogic-25hz.vbo"), tracks = emptyList(), directory = directory)
        assertTrue(made.detection is TrackDetection.Directory)
        val data = store.read(made.id)
        assertEquals("Tor Poznań", data.info.track)
        assertNull(data.info.startFinish)
        assertEquals("circuit tor-poznan Tor Poznań", data.events.first { it.kind == TrackEvent.KIND }.text)
    }

    @Test
    fun `a 1 Hz GPX in two segments, speed and heading derived where missing`() {
        val made = import(fixture("trackday-1hz.gpx"), "trackday-1hz.gpx")
        assertEquals(120, made.points)
        assertEquals("GPX", store.list().single().info.imported?.format)
        val data = store.read(made.id)
        val speeds = values(data, GnssChannels.SPEED_KMH.id)
        // Every point, including each segment's first and the one after the pause, near 72 km/h
        // (a chord of the circle is a little shorter than the arc).
        assertEquals(120, speeds.size)
        assertTrue(speeds.all { abs(it - 72.0) < 0.5 }, "speeds $speeds")
        val headings = values(data, GnssChannels.HEADING.id)
        assertEquals(120, headings.size)
        assertEquals(72.81, headings.first(), 0.1)
        assertEquals(82.0 + 1.5 * kotlin.math.sin(-0.3), values(data, GnssChannels.ALT_M.id).first(), 0.06)
        // The 30 s pause stays a gap in time.
        val times = data.sampleSets.map { it.tMicros }
        assertEquals(31_000_000L, times[60] - times[59])
    }

    @Test
    fun `a gzip-compressed GPX imports like the plain one`() {
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(bytes("trackday-1hz.gpx")) }
        val made = import(gz.toByteArray().inputStream(), "trackday.gpx.gz")
        assertEquals(ForeignFormat.GPX, made.format)
        assertEquals(120, made.points)
    }

    @Test
    fun `a RaceChrono CSV v3 export maps units, accelerations and extra channels`() {
        val made = import(fixture("racechrono-v3.csv"), "racechrono-v3.csv")
        assertEquals(600, made.points)
        assertEquals(0, made.skipped) // the OBD-only rows have no position: passed over, not counted as damage
        val data = store.read(made.id)
        assertEquals("RaceChrono CSV", data.info.imported?.format)
        assertEquals(start, data.info.startUtcMillis)
        assertTrue(values(data, GnssChannels.SPEED_KMH.id).all { abs(it - 72.0) < 1e-6 }, "m/s to km/h")
        assertEquals(14.0, values(data, GnssChannels.SATS.id).first())
        assertEquals(0.51, values(data, SessionImport.AccelLateral.id).first(), 1e-9)
        val rpm = channel(data, "Engine_RPM")
        assertEquals("rpm", rpm.unit)
        assertEquals(600, values(data, rpm.id).size)
        assertEquals("m", channel(data, "Accuracy").unit)
        // RaceChrono's own lap bookkeeping is not carried; the app times laps itself.
        assertTrue(data.channels.values.none { it.name in setOf("Lap_#", "Fragment_ID", "Elapsed_time") })
        // The OBD speed in km/h is a second "Speed" column: the GPS one is used, and it is no extra.
        assertTrue(data.channels.values.none { it.name.contains("Speed") })
    }

    @Test
    fun `an import stores where each channel came from, with the file's maker and rate`() {
        val vbo = store.read(import(fixture("racelogic-25hz.vbo"), "a.vbo").id)
        val gpx = store.read(import(fixture("trackday-1hz.gpx"), "a.gpx").id)
        val csv = store.read(import(fixture("racechrono-v3.csv"), "a.csv").id)
        fun kind(data: SessionData, name: String) = channel(data, name).provenance

        // A VBO logger is a GNSS receiver; its acceleration is worked out from GPS speed.
        assertEquals(Provenance.MEASURED_GNSS_RECEIVER, kind(vbo, "gnss_lat"))
        assertEquals(Provenance.MEASURED_GNSS_RECEIVER, kind(vbo, "gnss_speed"))
        assertEquals(Provenance.CALCULATED, kind(vbo, "accel_y"))
        assertEquals(Provenance.UNKNOWN, kind(vbo, "vert-vel"))
        assertEquals(25.0, vbo.info.imported?.sampleRateHz)

        // A GPX without speed or course: positions from a GPS, speed and heading calculated from them.
        assertEquals(Provenance.MEASURED_GPS, kind(gpx, "gnss_lat"))
        assertEquals(Provenance.CALCULATED, kind(gpx, "gnss_speed"))
        assertEquals(Provenance.CALCULATED, kind(gpx, "gnss_heading"))
        assertEquals("GPSLogger 131", gpx.info.imported?.receiver)
        assertEquals(1.0, gpx.info.imported?.sampleRateHz)

        // RaceChrono's sources row: its accelerometer and the OBD reader.
        assertEquals(Provenance.MEASURED_GPS, kind(csv, "gnss_speed"))
        assertEquals(Provenance.MEASURED_ACCELEROMETER, kind(csv, "accel_y"))
        assertEquals(Provenance.MEASURED_OBD, kind(csv, "Engine_RPM"))
        assertEquals(Provenance.MEASURED_GPS, kind(csv, "Accuracy"))
        assertEquals(10.0, csv.info.imported?.sampleRateHz)
        // Its first header row names the app that wrote it.
        assertEquals("RaceChrono v9.0.6", csv.info.imported?.receiver)
        assertEquals("RaceChrono v9.0.6", RaceChronoCsvReader.maker("This file is created using RaceChrono v9.0.6."))
        assertNull(RaceChronoCsvReader.maker("Format,3"))

        // Stored for every channel, in the metadata the session list and a transfer read.
        for (data in listOf(vbo, gpx, csv)) {
            val meta = SessionFiles.readProps(File(store.dir(data.id), SessionFiles.META))!!
            val count = meta.getProperty("channels").toInt()
            assertEquals(data.channels.size, count)
            for (i in 0 until count) assertNotNull(Provenance.parse(meta.getProperty("channel.$i.provenance")))
        }
    }

    @Test
    fun `each import gets its own session id`() {
        val a = import(fixture("racelogic-25hz.vbo"))
        val b = import(fixture("racelogic-25hz.vbo"))
        assertNotEquals(a.id, b.id)
        assertEquals(setOf(a.id, b.id), store.list().map { it.id }.toSet())
        assertTrue(a.id.startsWith("20261012-104200-"), a.id)
    }

    @Test
    fun `a refusal just before adding, such as a recording that started, adds nothing`() {
        assertFailsWith<IOException> {
            fixture("racelogic-25hz.vbo").use {
                val refuse = { throw IOException("recording") }
                importer.import(it, "f", listOf(track), CircuitList.Empty, beforeAdd = refuse)
            }
        }
        assertNothingLeft()
    }

    @Test
    fun `an imported session exports as VBO and that VBO imports again the same`() {
        val made = import(fixture("racelogic-25hz.vbo"))
        val out = ByteArrayOutputStream()
        VboExporter.export(store.read(made.id), out)
        val again = import(out.toByteArray().inputStream(), "BetterDriver.vbo")
        assertEquals(made.points, again.points)
        val first = store.read(made.id)
        val second = store.read(again.id)
        assertEquals(first.info.startUtcMillis, second.info.startUtcMillis)
        val lat1 = values(first, GnssChannels.LAT.id)
        val lat2 = values(second, GnssChannels.LAT.id)
        assertEquals(lat1.size, lat2.size)
        lat1.indices.forEach { assertEquals(lat1[it], lat2[it], 1e-6) }
        assertEquals(values(first, SessionImport.AccelLateral.id), values(second, SessionImport.AccelLateral.id))
    }

    @Test
    fun `a VBO running past midnight continues on the next day`() {
        val vbo = """
            File created on 12/10/2026 @ 23:59:59

            [header]
            time
            latitude
            longitude

            [column names]
            time lat long

            [data]
            235959.80 +03145.00000 -01008.70000
            000000.00 +03145.00100 -01008.70100
            000000.20 +03145.00200 -01008.70200
        """.trimIndent()
        val made = import(vbo.byteInputStream())
        val data = store.read(made.id)
        assertEquals(listOf(0L, 200_000L, 400_000L), data.sampleSets.map { it.tMicros })
        assertEquals(Instant.parse("2026-10-12T23:59:59.800Z").toEpochMilli(), data.info.startUtcMillis)
    }

    @Test
    fun `broken, empty and foreign files are refused with a reason and leave nothing`() {
        val gpxHead = bytes("trackday-1hz.gpx").toString(Charsets.UTF_8).substringBefore("<trkseg>")
        val cases = mapOf(
            "empty" to ByteArray(0),
            "binary" to byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0),
            "plain text" to "shopping list\nmilk\n".toByteArray(),
            "VBO without data" to "File created on 12/10/2026\r\n\r\n[header]\r\ntime\r\n".toByteArray(),
            "VBO without positions" to
                "[header]\ntime\nvelocity kmh\n[column names]\ntime velocity\n[data]\n101010.00 50\n".toByteArray(),
            "VBO with only bad rows" to
                "[header]\ntime\nlatitude\nlongitude\n[column names]\ntime lat long\n[data]\nx y z\n".toByteArray(),
            "GPX cut short" to bytes("trackday-1hz.gpx").copyOf(5_000),
            "GPX without times" to
                (gpxHead + "<trkseg><trkpt lat=\"52.4\" lon=\"16.8\"/></trkseg></trk></gpx>").toByteArray(),
            "GPX with an entity" to (
                "<?xml version=\"1.0\"?><!DOCTYPE gpx [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" +
                    "<gpx version=\"1.1\"><trk><trkseg><trkpt lat=\"1\" lon=\"1\">" +
                    "<time>&x;</time></trkpt></trkseg></trk></gpx>"
                ).toByteArray(),
            "CSV columns only" to "Format,3\n\nTimestamp,Latitude,Longitude\nunix time,deg,deg\n".toByteArray(),
            "CSV one row" to "Format,3\nTimestamp,Latitude,Longitude\n1791801720.0,52.41,16.81\n".toByteArray(),
            "damaged gzip" to byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 1, 2, 3, 4, 5),
            "endless line" to ("Format,3\n" + "x".repeat(ImportLimits.MaxLineChars + 10)).toByteArray(),
        )
        for ((what, file) in cases) {
            val e = assertFailsWith<ImportFormatException>(what) { import(file.inputStream()) }
            assertTrue(!e.message.isNullOrBlank(), what)
            assertNothingLeft()
        }
    }

    @Test
    fun `reading stops past the size limit`() {
        val input = CountingInput(ByteArray(10_000).inputStream(), 4_096)
        val e = assertFailsWith<ImportFormatException> { input.readBytes() }
        assertTrue(e.message.orEmpty().contains("too large"))
    }

    @Test
    fun `progress counts the bytes of the file picked`() {
        var read = 0L
        val file = bytes("racechrono-v3.csv")
        file.inputStream().use {
            importer.import(it, "f", emptyList(), CircuitList.Empty, onBytes = { n -> read += n })
        }
        assertEquals(file.size.toLong(), read)
    }
}
