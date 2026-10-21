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
 * (the copy in resources/real: the drive without its first and last 1.5 km, moved, see its README).
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
        // Just over 24 Hz, enough for Arek (2026-10-13); the missing messages fail on their own above.
        assertEquals(Verdict.PASS, r.metric("gnss.rate").verdict)
        assertEquals(24.02, r.metric("gnss.rate").value!!, 0.05)
        // Thermal status reached "light" only, far from shutdown: nothing about heat is reported.
        assertTrue(r.metrics.none { it.id.startsWith("thermal.") })
        // Five phone files back to back: only the hand-overs between them are missing.
        assertEquals(Verdict.PASS, r.metric("video.phone.coverage").verdict)
        // A 40-minute drive is a soak; what 0.3.1 did not log stays not measured.
        assertEquals(Verdict.FAIL, r.metric("soak.gnss_dropped").verdict)
        for (id in listOf("ui.jank", "ui.false_banners", "video.phone.sync_error")) {
            assertEquals(Verdict.NOT_MEASURED, r.metric(id).verdict, id)
        }
    }

    @Test
    fun `the 1_5_2 drive's banners for late fixes are not false (FBBD-282)`() {
        // Arek's drive of 21 Oct 2026, 12:27 to 12:50 local: the RaceBox at the default MTU stalled
        // 9 times for ~1.3 s. 3 gaps in the receiver's times were over 1 s, 6 were 0.88 to 1 s, and
        // each of the 9 banners logged a fix age over 1 s. The phone's report counted those 6 false.
        // The copy keeps the recording's Session, Gnss and banner, frame and RaceBox lines, without positions.
        val r = driveChecks(File(javaClass.getResource("/drive-log/1.5.2-1252")!!.toURI())).single()
        assertEquals("20261021-122716-e013e1c3", r.sessionId)
        val banners = r.metric("ui.false_banners")
        assertEquals(Verdict.PASS, banners.verdict, banners.how)
        assertEquals(0.0, banners.value)
        assertTrue("9 banner(s), 3 outage(s), 6 for a shorter gap" in banners.how, banners.how)
        assertEquals(Verdict.PASS, r.metric("ui.banner_show").verdict)
        assertEquals(Verdict.PASS, r.metric("ui.banner_clear").verdict)
        assertEquals(9.0, r.metric("gnss.long_gaps").value)
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

    /** VBO positions are minutes, signed: +02730.30000 is 45.505°. */
    private fun vboDegrees(text: String) = text.toDouble() / 60.0

    @Test
    fun `the same drive's GNSS through the new log finds what the RaceBox counted`() {
        val r = driveChecks(vboLog()).single()
        println("Replay of $sessionId from its VBO: " + r.metrics.filter { it.id.startsWith("gnss.") }
            .joinToString("; ") { "${it.id}=${it.value?.let { v -> "%.2f".format(v) }} ${it.unit} ${it.verdict}" })
        val dropped = r.metric("gnss.dropped")
        // Worked out from the receiver's fix times: of the 4.2 % the RaceBox's own count gives, all but
        // 0.24 % is its single whole-second skip with a fix (FBBD-170), counted apart.
        assertEquals(Verdict.PASS, dropped.verdict, dropped.how)
        assertEquals(0.24, dropped.value!!, 0.1)
        assertTrue("whole-second" in dropped.how)
        // The rate counts that skip as sent too (2026-10-14 13:02 log); the 0.24 % lost passes on its own above.
        assertEquals(Verdict.PASS, r.metric("gnss.rate").verdict, r.metric("gnss.rate").how)
        // Single missed fixes only: 80 ms gaps, one of 120 ms, no link drop.
        assertEquals(120.0, r.metric("gnss.max_gap").value)
        assertEquals(Verdict.PASS, r.metric("gnss.max_gap").verdict)
        // The phone clock against the GNSS over the drive: the drift a camera aligned by GPS time would see.
        val clock = listOf(10, 20, 30).map { r.metric("gnss.clock_$it").value!! }
        assertTrue(clock.all { kotlin.math.abs(it) < 200.0 }, "$clock")
    }
}
