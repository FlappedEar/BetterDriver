package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.export.CsvExporter
import com.flappedear.betterdriver.recorder.export.ExportLap
import com.flappedear.betterdriver.recorder.export.GpxExporter
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.Frames
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CsvGpxExportTest {
    @TempDir
    lateinit var tmp: File

    private val start = 1_760_173_343_000L // 2025-10-11T09:02:23Z
    private val obd = ChannelDef(100, "rpm, engine", "1/min", "obd", 0)

    private fun fix(t: Long, lat: Double, lon: Double, speed: Double = 72.0, utc: Long? = null) = SampleSet(
        t,
        listOfNotNull(
            utc?.let { ChannelValue(GnssChannels.UTC_MS.id, it.toDouble()) },
            ChannelValue(GnssChannels.LAT.id, lat),
            ChannelValue(GnssChannels.LON.id, lon),
            ChannelValue(GnssChannels.SPEED_KMH.id, speed),
            ChannelValue(GnssChannels.HEADING.id, -90.0),
            ChannelValue(GnssChannels.ALT_M.id, 81.5),
            ChannelValue(GnssChannels.SATS.id, 12.0),
        ),
    )

    private fun session(sets: List<SampleSet>, track: String = "Tor <Poznań> & \"Co\"") =
        SessionData(
            id = "20251011-090223-abcd1234",
            info = SessionInfo(start, car = "Club car", track = track),
            state = SessionState.FINISHED,
            channels = (GnssChannels.ALL + obd).associateBy { it.id },
            sampleSets = sets,
            events = emptyList(),
            end = null,
            truncatedBytes = 0,
            skippedBytes = 0,
        )

    private fun csv(data: SessionData, laps: List<ExportLap> = emptyList()) =
        StringWriter().also { CsvExporter.write(data, it, laps) }.toString().split("\r\n").dropLast(1)

    private fun gpx(data: SessionData, laps: List<ExportLap> = emptyList()): Element {
        val out = ByteArrayOutputStream()
        GpxExporter.export(data, out, laps)
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(out.toByteArray())).documentElement
    }

    private fun Element.all(name: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.segments() = all("trkseg").map { it.all("trkpt").size }

    @Test
    fun `csv has one row per sample set in time order with units in the header`() {
        val rows = csv(
            session(
                listOf(
                    fix(40_000, 52.1, 16.7),
                    SampleSet(20_000, listOf(ChannelValue(obd.id, 3456.0))),
                    fix(0, 52.0, 16.6),
                ),
            ),
        )
        assertEquals(
            "time (UTC),elapsed (s),gnss_lat (deg),gnss_lon (deg),gnss_speed (km/h),gnss_heading (deg)," +
                "gnss_alt (m),gnss_sats,\"rpm, engine (1/min)\"",
            rows[0],
        )
        assertEquals(4, rows.size)
        assertEquals("2025-10-11T09:02:23.000Z,0.000,52.00000000,16.60000000,72.000,-90.00,81.50,12,", rows[1])
        // A set holds only its own values: the rest of the row is empty, not repeated.
        assertEquals("2025-10-11T09:02:23.020Z,0.020,,,,,,,3456", rows[2])
        assertTrue(rows[3].startsWith("2025-10-11T09:02:23.040Z,0.040,52.10000000,"))
    }

    @Test
    fun `csv gets lap number and lap time when laps are known`() {
        val sets = (0..5).map { fix(it * 1_000_000L, 52.0 + it * 0.001, 16.6) }
        val laps = listOf(ExportLap(1, 0, 2_500_000), ExportLap(2, 2_500_000, Long.MAX_VALUE))
        val rows = csv(session(sets), laps)
        assertTrue(rows[0].startsWith("time (UTC),elapsed (s),lap,lap time (s),gnss_lat (deg)"))
        assertEquals(listOf("1,0.000", "1,1.000", "1,2.000", "2,0.500", "2,1.500", "2,2.500"), rows.drop(1).map {
            it.split(',').subList(2, 4).joinToString(",")
        })
    }

    @Test
    fun `csv leaves lap cells empty outside the laps and skips non-finite values`() {
        val sets = listOf(
            SampleSet(0, listOf(ChannelValue(obd.id, Double.NaN))),
            fix(1_000_000, 52.0, 16.6),
        )
        val rows = csv(session(sets), listOf(ExportLap(1, 1_000_000, 2_000_000)))
        assertEquals("2025-10-11T09:02:23.000Z,0.000,,,,,,,,,", rows[1])
        assertTrue(rows[2].startsWith("2025-10-11T09:02:24.000Z,1.000,1,0.000,"))
    }

    @Test
    fun `csv fields with commas, quotes or line breaks are quoted`() {
        assertEquals("plain", CsvExporter.field("plain"))
        assertEquals("\"a,b\"", CsvExporter.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.field("say \"hi\""))
        assertEquals("\"two\nlines\"", CsvExporter.field("two\nlines"))
    }

    @Test
    fun `gpx is valid xml with escaped text, time, elevation and speed in m per s`() {
        val utc = start + 5_000
        val root = gpx(session(listOf(fix(0, 52.0, 16.6, speed = 36.0, utc = utc), fix(40_000, 52.0001, 16.6001))))
        assertEquals("http://www.topografix.com/GPX/1/1", root.namespaceURI)
        assertEquals("1.1", root.getAttribute("version"))
        assertEquals("Tor <Poznań> & \"Co\"", root.all("name").first().textContent)
        val pts = root.all("trkpt")
        assertEquals(2, pts.size)
        assertEquals("52.00000000", pts[0].getAttribute("lat"))
        assertEquals("16.60000000", pts[0].getAttribute("lon"))
        assertEquals("81.50", pts[0].all("ele").single().textContent)
        // The receiver's UTC when the set holds it, else session start plus the recorder's clock.
        assertEquals("2025-10-11T09:02:28.000Z", pts[0].all("time").single().textContent)
        assertEquals("2025-10-11T09:02:23.040Z", pts[1].all("time").single().textContent)
        assertEquals("10.000", pts[0].all("speed").single().textContent)
        assertEquals("270.00", pts[0].all("course").single().textContent)
        val tpx = "http://www.garmin.com/xmlschemas/TrackPointExtension/v2"
        assertEquals(tpx, pts[0].all("speed").single().namespaceURI)
        assertEquals(listOf("ele", "time", "sat", "extensions"), pts[0].childElements())
    }

    private fun Element.childElements(): List<String> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { (nodes.item(it) as? Element)?.localName }
    }

    @Test
    fun `gpx skips no-fix samples and splits stretches at a lost fix or a gap`() {
        val sets = listOf(
            fix(0, 52.0, 16.6),
            fix(100_000, 52.0, 16.6),
            fix(200_000, 0.0, 0.0), // no fix
            fix(300_000, 52.0, 16.6),
            fix(400_000, 52.0, 16.6),
            fix(400_000 + GpxExporter.GapMicros + 1, 52.0, 16.6),
            SampleSet(500_000_000, listOf(ChannelValue(obd.id, 1.0))), // not a position: ignored
        )
        assertEquals(listOf(2, 2, 1), gpx(session(sets)).segments())
    }

    @Test
    fun `gpx has one segment per lap when laps are known`() {
        val sets = (0..9).map { fix(it * 1_000_000L, 52.0 + it * 0.001, 16.6) } +
            fix(4_500_000, 0.0, 0.0) // a lost fix inside a lap does not split it
        val laps = listOf(ExportLap(1, 1_000_000, 4_000_000), ExportLap(2, 4_000_000, 8_000_000))
        // Before lap 1 (one point), lap 1, lap 2, after lap 2 (two points).
        assertEquals(listOf(1, 3, 4, 2), gpx(session(sets), laps).segments())
    }

    @Test
    fun `gpx text keeps only characters xml allows`() {
        assertEquals("a&amp;b&lt;c&gt;&quot;&apos;", GpxExporter.escape("a&b<c>\"'"))
        assertEquals("ab\tc", GpxExporter.escape("a\u0000b\tc\u001F"))
        assertEquals("x🏁y", GpxExporter.escape("x🏁y\uD800"))
        val root = gpx(session(listOf(fix(0, 52.0, 16.6)), track = "Bad\u0001 name \uDC00]]>"))
        assertEquals("Bad name ]]>", root.all("name").first().textContent)
    }

    @Test
    fun `a recovered session exports as csv and gpx`() {
        val gnss = FakeGnss()
        val store = SessionStore(tmp)
        val (id, dir) = store.createSession(
            SessionInfo(
                gnss.startUtcMillis, "FR-S", "Fake ring", listOf("fake-gnss"), gnss.startFinish, GnssChannels.ALL,
            ),
        )
        File(dir, SessionFiles.chunkName(1)).outputStream().use { out ->
            GnssChannels.ALL.forEach { out.write(Frames.encode(Record.Channel(it))) }
            for (i in 0 until 50) out.write(Frames.encode(Record.Samples(SampleSet(i * 40_000L, gnss.fix(i.toLong())))))
        }
        store.recoverInterrupted()
        val data = store.read(id)
        assertEquals(SessionState.RECOVERED, data.state)
        assertEquals(51, csv(data).size)
        val root = gpx(data)
        assertEquals(50, root.all("trkpt").size)
        assertTrue(root.all("desc").first().textContent.contains("Recovered"))
        assertFalse(root.all("trkpt").any { it.getAttribute("lat") == "0.00000000" })
    }
}
