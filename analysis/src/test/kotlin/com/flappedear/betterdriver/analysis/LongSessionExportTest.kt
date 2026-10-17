package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.recorded.vboAccelerations
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.export.CsvExporter
import com.flappedear.betterdriver.recorder.store.ChunkWriter
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A long drive exports within the memory Android gives the app (FBBD-232). On 2026-10-17 the VBO
 * export of a 63-minute drive (RaceBox at 25 Hz, the phone's accelerometer at 109 Hz) ended the app:
 * out of memory at the Pixel 6's 256 MB, holding one object per recorded value and then every value
 * again, boxed, to work out the G columns. Here a drive is written to disk as the recorder writes
 * it, then read and exported as VBO and CSV by a child JVM with that little memory.
 */
class LongSessionExportTest {
    @TempDir
    lateinit var tmp: File

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `the 63-minute drive exports in 256 MB, the default heap`() = exports(minutes = 63, heap = "256m")

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `two hours export in 512 MB, a large heap`() = exports(minutes = 120, heap = "512m")

    private fun exports(minutes: Int, heap: String) {
        val root = File(tmp, "sessions")
        val id = LongSession.write(SessionStore(root), minutes)
        val java = File(System.getProperty("java.home"), "bin/java").path
        val proc = ProcessBuilder(
            java, "-Xmx$heap", "-cp", System.getProperty("java.class.path"),
            LongSessionExportChild::class.java.name, root.path, id,
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        assertEquals(0, exit, "the export ran out of memory or failed:\n$output")
        val (rows, fixes) = output.lines().first { it.startsWith("rows ") }.split(" ").let { it[1].toInt() to it[3].toInt() }
        // One VBO row per fix; the fixes the session read back with are all but up to a second of them
        // (the fix age correction can move the first ones before the start).
        assertEquals(fixes, rows, output)
        assertTrue(fixes > (minutes * 60 - 1) * RaceBoxHz, output)
    }
}

private const val RaceBoxHz = 25
private const val PhoneHz = 109.3

/** A drive as the recorder stores it: RaceBox fixes with its 10 other channels, and the phone's accelerometer. */
internal object LongSession {
    private val receiver = listOf(
        100 to "gnss_hacc", 101 to "gnss_vacc", 102 to "gnss_speed_acc",
        110 to "accel_x", 111 to "accel_y", 112 to "accel_z",
        113 to "gyro_x", 114 to "gyro_y", 115 to "gyro_z", 120 to "input_voltage",
    ).map { (id, name) -> ChannelDef(id, name, "", "racebox", 3) }
    private val phone = (0..2).map { ChannelDef(700 + it, "phone_accel_${"xyz"[it]}", "g", "phone", 3) }

    fun write(store: SessionStore, minutes: Int): String {
        val defs = GnssChannels.ALL + receiver + phone
        val (id, dir) = store.createSession(SessionInfo(startUtcMillis = StartUtc, channels = defs))
        val writer = ChunkWriter(dir, maxBytes = 16L shl 20, maxAgeMs = Long.MAX_VALUE, syncIntervalMs = 60_000)
        writer.open(defs)
        val fixes = minutes * 60 * RaceBoxHz
        var p = 0
        for (i in 0 until fixes) {
            val t = i * 1_000_000L / RaceBoxHz
            // The phone's samples queue alongside, as they do in a recording.
            while (p / PhoneHz * 1e6 < t) {
                val tp = (p / PhoneHz * 1e6).toLong()
                writer.write(Record.Samples(SampleSet(tp, phone.map { ChannelValue(it.id, 0.1 * sin(tp / 1e6 + it.id)) })))
                p++
            }
            writer.write(Record.Samples(fix(t)))
        }
        writer.write(Record.End(fixes * 1_000_000L / RaceBoxHz, "user"))
        writer.close()
        return id
    }

    /** A 2 km loop at about 100 km/h. */
    private fun fix(t: Long): SampleSet {
        val s = t / 1e6
        val angle = s * 2 * PI / LapSeconds
        return SampleSet(
            t,
            listOf(
                ChannelValue(GnssChannels.UTC_MS.id, StartUtc + t / 1000.0),
                ChannelValue(GnssChannels.LAT.id, 50.0 + 0.003 * sin(angle)),
                ChannelValue(GnssChannels.LON.id, 19.0 + 0.0045 * cos(angle)),
                ChannelValue(GnssChannels.SPEED_KMH.id, 100.0 + 5 * sin(s)),
                ChannelValue(GnssChannels.HEADING.id, Math.toDegrees(angle) % 360),
                ChannelValue(GnssChannels.ALT_M.id, 300.0),
                ChannelValue(GnssChannels.SATS.id, 20.0),
            ) + receiver.map { ChannelValue(it.id, 0.1 * sin(s + it.id)) },
        )
    }

    private const val StartUtc = 1_792_000_000_000L
    private const val LapSeconds = 72.0
}

/** The child JVM of [LongSessionExportTest]: reads session args[1] from store args[0] and exports it, as Sessions does. */
object LongSessionExportChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val data = SessionStore(File(args[0])).read(args[1])
        val sink = object : OutputStream() {
            override fun write(b: Int) = Unit

            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }
        val written = VboExporter.export(data, sink, derived = vboAccelerations(data))
        CsvExporter.export(data, sink)
        println("rows ${written.rows} fixes ${written.fixes}")
    }
}
