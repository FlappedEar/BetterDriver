package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.log.CheckLog
import com.flappedear.betterdriver.recorder.log.GnssSecondLog
import com.flappedear.betterdriver.recorder.log.LogLine
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drive checks replayed over Arek's real drive of 12 Oct 2026, 10:36 to 11:16 UTC (FBBD-183):
 * the debug logs of 0.3.1 as he shared them, and the GNSS of the same session from its VBO export
 * (the copy in resources/real, its positions moved 10° west).
 */
class DriveLogReplayTest {
    private val sessionId = "20261012-103607-3713ff1a"
    private val logs = File(javaClass.getResource("/drive-log/0.3.1")!!.toURI())

    private fun QualityReport.metric(id: String) = metrics.single { it.id == id }

    @Test
    fun `the 0_3_1 logs give the drive's rate, dropped samples, thermal steps and video coverage`() {
        val reports = driveChecks(logs)
        // Two short test starts that morning, then the 40-minute drive.
        val ids = listOf("20261012-055816-0075d00b", "20261012-072412-c7671bac", sessionId)
        assertEquals(ids, reports.map { it.sessionId })
        val r = reports.last()
        // The RaceBox counted 2,324 of 59,324 messages missing over the drive (FBBD-170).
        val dropped = r.metric("gnss.dropped")
        assertEquals(Verdict.FAIL, dropped.verdict)
        assertEquals(3.92, dropped.value!!, 0.01)
        assertEquals(Verdict.FAIL, r.metric("gnss.rate").verdict)
        assertEquals(24.0, r.metric("gnss.rate").value!!, 0.2)
        // Thermal status reached "light" only; the camera stepped down from 4K 60 to 4K 30 once, logged.
        assertEquals(1.0, r.metric("thermal.max").value)
        assertEquals(Verdict.PASS, r.metric("thermal.status").verdict)
        assertEquals(1.0, r.metric("thermal.step_downs").value)
        // Five phone files back to back: only the hand-overs between them are missing.
        assertEquals(Verdict.PASS, r.metric("video.phone.coverage").verdict)
        // A 40-minute drive is a soak; what 0.3.1 did not log stays not measured.
        assertEquals(Verdict.FAIL, r.metric("soak.gnss_dropped").verdict)
        assertEquals(Verdict.PASS, r.metric("soak.thermal").verdict)
        for (id in listOf("thermal.battery", "ui.jank", "ui.false_banners", "video.phone.sync_error", "soak.battery")) {
            assertEquals(Verdict.NOT_MEASURED, r.metric(id).verdict, id)
        }
    }

    /** The VBO's rows as the recorder would have logged them: one [GnssSecondLog] line per second. */
    private fun vboLog(): List<LogLine> {
        val start = LogLine.parse("2026-10-12T10:36:07.979Z 2646 recorder-control I Recording: x")!!.utcMillis
        val day = LocalDate.of(2026, 10, 12).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // Session time of each phone file's media time 0, from the video_start lines of the same drive.
        val fileStarts = listOf(613L, 417_641L, 835_016L, 1_247_361L, 2_090_764L)
        val lines = mutableListOf<LogLine>()
        fun log(tag: String, message: String) {
            lines += LogLine(start, null, "recorder", 1, "t", 'I', tag, sessionId, message)
        }
        log(CheckLog.SESSION, "start utc_ms=$start source=RaceBox_Micro")
        val gnss = GnssSecondLog(sessionId, start) { log(CheckLog.GNSS, it) }
        val vbo = javaClass.getResourceAsStream("/real/commute-2026-10-12-1036.vbo.gz")!!
        val text = GZIPInputStream(vbo).bufferedReader()
        var last = 0L
        text.useLines { lines ->
            val all = lines.toList()
            val names = all[all.indexOf("[column names]") + 1].split(' ')
            fun column(name: String) = names.indexOf(name).also { check(it >= 0) { name } }
            val at = listOf("time", "lat", "long", "velocity", "avifileindex", "avisynctime").map(::column)
            all.drop(all.indexOf("[data]") + 1).filter { it.isNotBlank() }.forEach { row ->
                val c = row.split(' ')
                val time = c[at[0]]
                val lat = c[at[1]]
                val lon = c[at[2]]
                val kmh = c[at[3]]
                val file = c[at[4]]
                val sync = c[at[5]]
                val hh = time.substring(0, 2).toLong()
                val mm = time.substring(2, 4).toLong()
                val ss = time.substring(4).toDouble()
                val utc = day + (hh * 3600 + mm * 60) * 1000 + Math.round(ss * 1000)
                last = (fileStarts[file.toInt() - 1] + sync.toLong()) * 1000
                gnss.onSamples(
                    SampleSet(
                        last,
                        listOf(
                            ChannelValue(GnssChannels.UTC_MS.id, utc.toDouble()),
                            ChannelValue(GnssChannels.LAT.id, vboDegrees(lat)),
                            ChannelValue(GnssChannels.LON.id, -vboDegrees(lon)),
                            ChannelValue(GnssChannels.SPEED_KMH.id, kmh.toDouble()),
                        ),
                    ),
                )
            }
        }
        gnss.onStatus(RecorderStatus.Stopped("user"))
        log(CheckLog.SESSION, "stop t_us=$last reason=user")
        return lines
    }

    /** VBO positions are minutes, signed: +03003.90355 is 50.065 059°. */
    private fun vboDegrees(text: String) = text.toDouble() / 60.0

    @Test
    fun `the same drive's GNSS through the new log finds what the RaceBox counted`() {
        val r = driveChecks(vboLog()).single()
        println("Replay of $sessionId from its VBO: " + r.metrics.filter { it.id.startsWith("gnss.") }
            .joinToString("; ") { "${it.id}=${it.value?.let { v -> "%.2f".format(v) }} ${it.unit} ${it.verdict}" })
        val dropped = r.metric("gnss.dropped")
        // Worked out from the receiver's fix times, the same share the RaceBox's own count gives.
        assertEquals(Verdict.FAIL, dropped.verdict)
        assertEquals(4.1, dropped.value!!, 0.3)
        assertEquals(Verdict.FAIL, r.metric("gnss.rate").verdict)
        // Single missed fixes only: 80 ms gaps, one of 120 ms, no link drop.
        assertEquals(120.0, r.metric("gnss.max_gap").value)
        assertEquals(Verdict.PASS, r.metric("gnss.max_gap").verdict)
        // The phone clock against the GNSS over the drive: the drift a camera aligned by GPS time would see.
        val clock = listOf(10, 20, 30).map { r.metric("gnss.clock_$it").value!! }
        assertTrue(clock.all { kotlin.math.abs(it) < 200.0 }, "$clock")
    }
}
