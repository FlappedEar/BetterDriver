@file:Suppress("MagicNumber") // Format precisions and tolerances.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.transfer.foreign.SessionImport
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FBBD-14 and FBBD-68 (the format side) on Arek's real recordings: each file is imported as the app
 * imports it, exported as VBO and the export imported again. Nothing may be lost on the way: every
 * data row of the original becomes one sample and one exported row, the values survive to the
 * VBO's own precision, and the laps (and point-to-point runs on the road drives) are the same, to
 * 0.02 s. What Circuit Tools and RaceChrono make of the export, and the export time on the Pixel 6,
 * still need the PC and the phone.
 */
class VboRoundTripTest {
    @TempDir
    lateinit var tmp: File

    private val store by lazy { SessionStore(File(tmp, "sessions")) }
    private val staging by lazy { File(tmp, "staging") }

    private class Trip(
        val name: String,
        val rows: Int,
        val first: SessionData,
        val exported: String,
        val second: SessionData,
    )

    /** Imports [name] as the app does, exports it as VBO and imports the export. [text] is the file's text. */
    private fun trip(
        name: String,
        tracks: List<Track>,
        text: String = RealCorpus.text(name),
        open: () -> InputStream = { RealCorpus.open(name) },
    ): Trip {
        val made = open().use {
            SessionImport(store, staging).import(it, name, tracks, CircuitList.Empty, today = RealCorpus.Today)
        }
        assertEquals(0, made.skipped, "$name: rows the import could not read")
        val first = store.read(made.id)
        val out = ByteArrayOutputStream()
        VboExporter.export(first, out)
        val again = out.toByteArray().inputStream().use {
            SessionImport(store, staging)
                .import(it, "BetterDriver.vbo", tracks, CircuitList.Empty, today = RealCorpus.Today)
        }
        assertEquals(0, again.skipped, "$name: rows of the export the import could not read")
        val rows = text.lineSequence().dropWhile { it.trim() != "[data]" }.drop(1).count { it.isNotBlank() }
        return Trip(name, rows, first, out.toString(Charsets.UTF_8), store.read(again.id))
    }

    private fun values(data: SessionData, channel: ChannelDef) =
        data.sampleSets.map { s -> s.values.firstOrNull { it.channelId == channel.id }?.value }

    private fun assertNothingLost(trip: Trip) {
        val name = trip.name
        val exportedRows = trip.exported.lineSequence()
            .dropWhile { it.trim() != "[data]" }.drop(1).count { it.isNotBlank() }
        assertEquals(trip.rows, trip.first.sampleSets.size, "$name: original rows vs imported samples")
        assertEquals(trip.rows, exportedRows, "$name: original rows vs exported rows")
        assertEquals(trip.rows, trip.second.sampleSets.size, "$name: original rows vs samples imported from the export")
        assertEquals(trip.first.info.startUtcMillis, trip.second.info.startUtcMillis, name)
        val times = trip.second.sampleSets.map { it.tMicros }
        assertEquals(trip.first.sampleSets.map { it.tMicros }, times, "$name: sample times")
        // To the VBO's own precision: 0.00001 arc minutes of position, 0.001 km/h, 0.01 deg and 0.01 m.
        val precision = mapOf(
            GnssChannels.LAT to 1e-5 / 60, GnssChannels.LON to 1e-5 / 60, GnssChannels.SPEED_KMH to 5e-4,
            GnssChannels.HEADING to 5e-3, GnssChannels.ALT_M to 5e-3, GnssChannels.UTC_MS to 0.0,
            GnssChannels.SATS to 0.0, SessionImport.AccelForward to 5e-4, SessionImport.AccelLateral to 5e-4,
        )
        for ((channel, tolerance) in precision) {
            val a = values(trip.first, channel)
            val b = values(trip.second, channel)
            if (a.all { it == null }) continue
            a.indices.forEach { i ->
                val x = a[i]
                val y = b[i]
                // A heading of 360.00 (359.995 and up, as written) is read back as 0.00: the same direction.
                val turn = if (channel == GnssChannels.HEADING) 360.0 else Double.POSITIVE_INFINITY
                assertTrue(
                    x != null && y != null && abs(x - y).let { minOf(it, abs(it - turn)) } <= tolerance + 1e-9,
                    "$name: ${channel.name} of sample $i: $x then $y",
                )
            }
        }
        // Every other channel of the file (OBD, accelerometer axes, accuracy) is carried too, under
        // its name with "RaceBox" made neutral (FBBD-184).
        val extras = trip.first.channels.values.filter { it.source == SessionImport.Source && it !in precision.keys }
        for (c in extras) {
            val exportedName = c.name.replace(Regex("race\\s*box", RegexOption.IGNORE_CASE), "gnss")
            val again = trip.second.channels.values.firstOrNull { it.name == exportedName }
            assertTrue(again != null, "$name: channel ${c.name} missing after the round trip")
            val a = values(trip.first, c)
            val b = trip.second.sampleSets.map { s -> s.values.firstOrNull { it.channelId == again.id }?.value }
            assertEquals(a.count { it != null }, b.count { it != null }, "$name: samples of ${c.name}")
        }
    }

    @Test
    fun `RaceChrono at Silesia Ring - nothing lost, same laps to 0_02 s (FBBD-14, FBBD-68)`() {
        val track = RealCorpus.silesiaTrack()
        val trip = trip(RealCorpus.SILESIA, listOf(track))
        assertNothingLost(trip)
        val before = replayLaps(trip.first.id, trip.first.sampleSets, track).laps.map { it.lap }
        val after = replayLaps(trip.second.id, trip.second.sampleSets, track).laps.map { it.lap }
        assertEquals(7, before.count { it.kind == LapKind.Timed }, "the session's timed laps")
        assertEquals(before.map { it.number to it.kind }, after.map { it.number to it.kind }, "lap count and kinds")
        before.zip(after).forEach { (a, b) ->
            val what = "lap ${a.number}: ${a.timeMicros} then ${b.timeMicros} us"
            assertTrue(abs(a.timeMicros - b.timeMicros) <= 20_000, what)
            a.sectorMicros.zip(b.sectorMicros).forEach { (x, y) ->
                val same = x == null && y == null || x != null && y != null && abs(x - y) <= 20_000
                assertTrue(same, "lap ${a.number} sectors")
            }
        }
        // The export carries the start/finish line, so the exported file can be timed on its own.
        assertTrue(trip.exported.contains("[laptiming]"), "start/finish line in the export")
    }

    @Test
    fun `BetterDriver's own exports - nothing lost, same point-to-point runs to 0_02 s (FBBD-14, FBBD-68)`() {
        for (name in RealCorpus.BETTERDRIVER) {
            val tracks = listOfNotNull(RealCorpus.roadTrack(name))
            val trip = trip(name, tracks)
            assertNothingLost(trip)
            assertSameRows(name, RealCorpus.text(name), trip.exported)
            val track = tracks.singleOrNull() ?: continue
            val before = replayLaps(trip.first.id, trip.first.sampleSets, track).laps.map { it.lap }
            val after = replayLaps(trip.second.id, trip.second.sampleSets, track).laps.map { it.lap }
            assertEquals(1, before.size, "$name: one run from start to finish")
            assertEquals(before.size, after.size, name)
            before.zip(after).forEach { (a, b) -> assertTrue(abs(a.timeMicros - b.timeMicros) <= 20_000, name) }
        }
    }

    @Test
    fun `a BetterDriver export parked and crawling, with a 188 s gap in its fixes - nothing lost (FBBD-14, FBBD-68)`() {
        // Too slow for a road track: the session has no runs, only its samples to carry.
        val name = "parked-synthetic.vbo"
        val text = parkedVbo()
        val trip = trip(name, emptyList(), text) { text.byteInputStream() }
        assertNothingLost(trip)
        assertSameRows(name, text, trip.exported)
        // The gap survives the round trip: nothing made up across it.
        val gaps = trip.second.sampleSets.zipWithNext { a, b -> b.tMicros - a.tMicros }
        assertEquals(1, gaps.count { it > 1_000_000L }, "$name: gaps over 1 s")
        assertEquals(188_040_000.0, gaps.max().toDouble(), 1_000.0, "$name: the gap")
    }

    /**
     * The re-export of one of BetterDriver's own VBOs has the original's rows: the same time,
     * position, speed, heading and height, as written.
     */
    private fun assertSameRows(name: String, originalText: String, exported: String) {
        val original = VboRows.of(originalText)
        val again = VboRows.of(exported)
        assertEquals(original.size, again.size, name)
        for (column in listOf("time", "lat", "long", "velocity", "heading", "height", "sats")) {
            val a = original.column(column)
            val b = again.column(column)
            // VboWriter can write a heading of 359.995 and up as 360.00; read back it is 0.00.
            val turn = if (column == "heading") 360.0 else Double.POSITIVE_INFINITY
            val bad = a.indices.firstOrNull { abs(a[it] - b[it]).let { d -> minOf(d, abs(d - turn)) } > 1e-9 }
            val what = bad?.let { "row ${it + 1}: ${a[it]} then ${b[it]}" }
            assertEquals(null, bad, "$name: $column of $what")
        }
    }

    /** The [data] rows of a VBO by [column names] name. */
    private class VboRows(private val names: List<String>, private val rows: List<List<String>>) {
        val size get() = rows.size

        fun column(name: String): DoubleArray {
            val i = names.indexOf(name)
            return DoubleArray(rows.size) { rows[it][i].toDouble() }
        }

        companion object {
            fun of(text: String): VboRows {
                val lines = text.lines().map { it.trim() }
                val names = lines[lines.indexOf("[column names]") + 1].split(' ')
                val rows = lines.drop(lines.indexOf("[data]") + 1).filter { it.isNotEmpty() }.map { it.split(' ') }
                return VboRows(names, rows)
            }
        }
    }

    private companion object {
        /**
         * A BetterDriver VBO export shaped like the parked session Arek shared, which this corpus no
         * longer holds (FBBD-194): the same sections and columns at 25 Hz, parked and crawling at up
         * to 8 km/h, with a 188 s gap in the fixes after its first 730 rows. The place is made up:
         * 45° N, 2° W, at sea.
         */
        fun parkedVbo(): String {
            val header = listOf(
                "satellites", "time", "latitude", "longitude", "velocity kmh", "heading", "height",
                "gnss_fix_type", "gnss_hacc", "gnss_speed_acc", "accel_x", "accel_y", "accel_z",
                "gyro_x", "gyro_y", "gyro_z", "racebox_input",
            )
            val units = listOf("", "m", "km/h", "g", "g", "g", "deg/s", "deg/s", "deg/s", "V")
            val columns = "sats time lat long velocity heading height gnss_fix_type gnss_hacc gnss_speed_acc " +
                "accel_x accel_y accel_z gyro_x gyro_y gyro_z racebox_input"
            val head = listOf("File created on 11/10/2026 @ 14:27:59", "", "[header]") + header +
                listOf("", "[channel units]") + units +
                listOf("", "[comments]", "Generated by BetterDriver", "Session 20261011-142759-00000000") +
                listOf("Device racebox", "", "[column names]", columns, "", "[data]")
            val startMs = (14 * 3600 + 27 * 60 + 58) * 1000L + 199
            var north = 0.0
            var east = 0.0
            val rows = (0 until 1460).map { i ->
                val ms = startMs + i * 40L + if (i >= 730) 188_000L else 0L
                val kmh = 4.0 + 4.0 * sin(i / 50.0)
                val heading = (i * 0.2) % 360.0
                val step = kmh / 3.6 * 0.04
                north += step * cos(Math.toRadians(heading))
                east += step * sin(Math.toRadians(heading))
                val lat = 45.0 + north / 111_195.0
                // VBO longitude is positive west.
                val west = 2.0 - east / (111_195.0 * cos(Math.toRadians(45.0)))
                String.format(
                    Locale.ROOT,
                    "%03d %02d%02d%02d.%03d %+012.5f %+012.5f %07.3f %06.2f %+09.2f 3 0.74 0.39 " +
                        "%.3f %.3f %.3f %.2f %.2f %.2f 3.9",
                    11 + i % 4, ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000,
                    lat * 60.0, west * 60.0, kmh, heading, 243.0 + i * 0.001,
                    0.1 * sin(i / 7.0), 0.05 * cos(i / 9.0), -1.0 + 0.01 * sin(i / 3.0),
                    2.0 * sin(i / 11.0), 1.5 * cos(i / 13.0), 0.2 * i % 5,
                )
            }
            return (head + rows).joinToString("\r\n", postfix = "\r\n")
        }
    }
}
