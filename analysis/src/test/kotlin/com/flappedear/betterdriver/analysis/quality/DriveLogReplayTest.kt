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

    @Test
    fun `the 1_6_1 drives at MTU 247 lose one burst of 0_68 s and nothing else (FBBD-282)`() {
        // Arek's two drives of 21 Oct 2026, 15:40 (6 min) and 15:50 (3 min), on 1.6.1 with the Vgate
        // over BLE: the RaceBox streamed at MTU 247 from its first setup, 1500 messages in 1500
        // notifications. The first lost 16 fixes in one 680 ms gap (one banner for 0.5 s) besides
        // single misses; the second lost 2. Same lines as the 12:52 copy, without positions.
        val reports = driveChecks(File(javaClass.getResource("/drive-log/1.6.1-1555")!!.toURI()))
        assertEquals(listOf("20261021-154053-286ff822", "20261021-155033-ccaf0125"), reports.map { it.sessionId })
        val (first, second) = reports
        assertEquals(Verdict.FAIL, first.metric("gnss.dropped").verdict, first.metric("gnss.dropped").how)
        assertEquals(0.65, first.metric("gnss.dropped").value!!, 0.05)
        assertEquals(680.0, first.metric("gnss.max_gap").value)
        assertEquals(1.0, first.metric("gnss.long_gaps").value)
        assertEquals(Verdict.PASS, first.metric("ui.false_banners").verdict, first.metric("ui.false_banners").how)
        assertEquals(Verdict.PASS, second.metric("gnss.dropped").verdict, second.metric("gnss.dropped").how)
        assertEquals(0.04, second.metric("gnss.dropped").value!!, 0.02)
        assertEquals(Verdict.PASS, second.metric("gnss.max_gap").verdict)
        assertEquals(0.0, second.metric("gnss.long_gaps").value)
    }

    @Test
    fun `the 1_6_2 parked recording loses no sample beyond the whole-second skip (FBBD-272, FBBD-282)`() {
        // Arek's recording of 21 Oct 2026, 19:42 (10 min, parked), on 1.6.2: the RaceBox connected at
        // its first setup, MTU 247 after 0.85 s, and streamed 1.1 s after Start; 1500 messages in 1500
        // notifications every minute, only the whole-second message missing. Without positions.
        val r = driveChecks(File(javaClass.getResource("/drive-log/1.6.2-1953")!!.toURI())).single()
        assertEquals("20261021-194257-d6288c97", r.sessionId)
        assertEquals(Verdict.PASS, r.metric("gnss.dropped").verdict, r.metric("gnss.dropped").how)
        assertEquals(0.0, r.metric("gnss.dropped").value!!, 0.01)
        assertEquals(80.0, r.metric("gnss.max_gap").value!!, 1.0)
        assertEquals(0.0, r.metric("gnss.long_gaps").value)
        assertEquals(Verdict.PASS, r.metric("gnss.rate").verdict, r.metric("gnss.rate").how)
        assertEquals(Verdict.PASS, r.metric("ui.false_banners").verdict, r.metric("ui.false_banners").how)
    }

    @Test
    fun `the 1_7_1 parked 30 min keeps 4K60 at 57 fps or more and loses 0_06 % (FBBD-278, FBBD-273)`() {
        // Arek's recording of 22 Oct 2026, 07:31 to 08:02 (30 min, parked), on 1.7.1: 4K60 through the
        // own encoder in five files, the first three 59.6 fps with no frame lost, the last two 58.4 and
        // 57.3 fps after the encoder slowed at 07:54. RaceBox at MTU 247 from its first setup; the HR
        // strap was not worn, so off after 45 s. Session, Gnss, event and file lines, without positions.
        val r = driveChecks(File(javaClass.getResource("/drive-log/1.7.1-0802")!!.toURI())).single()
        assertEquals("20261022-073147-0cfe869c", r.sessionId)
        assertEquals(Verdict.PASS, r.metric("gnss.dropped").verdict, r.metric("gnss.dropped").how)
        assertEquals(0.06, r.metric("gnss.dropped").value!!, 0.02)
        assertEquals(80.0, r.metric("gnss.max_gap").value!!, 1.0)
        assertEquals(0.0, r.metric("gnss.long_gaps").value)
        assertEquals(Verdict.PASS, r.metric("soak.gnss_dropped").verdict, r.metric("soak.gnss_dropped").how)
        val video = r.metric("video.phone.frame_rate")
        assertEquals(Verdict.PASS, video.verdict, video.how)
        assertEquals(57.3, video.value!!, 0.1)
        assertTrue("phone_0005.mp4" in video.how, video.how)
        assertEquals(Verdict.NOT_MEASURED, r.metric("hr.rate").verdict, r.metric("hr.rate").how)
    }

    @Test
    fun `the 1_7_1 46-min drive with BLE OBD and HR loses 0_33 % and keeps 4K60 at 60 fps (FBBD-273, FBBD-278)`() {
        // Arek's drive of 22 Oct 2026, 09:07 to 09:53, on 1.7.1: RaceBox at MTU 247 from its first setup,
        // Vgate vLinker over BLE, HR strap worn, 4K60 through the own encoder in seven files at 60.0 fps.
        // Two stops with the engine off (16 and 3 min): the six gaps of 520-1000 ms all fall in them,
        // and the strap's link dropped twice and came back. Session, Gnss, Hr, event and file lines,
        // without positions.
        val r = driveChecks(File(javaClass.getResource("/drive-log/1.7.1-0953")!!.toURI())).single()
        assertEquals("20261022-090738-feb2054c", r.sessionId)
        assertEquals(Verdict.PASS, r.metric("gnss.dropped").verdict, r.metric("gnss.dropped").how)
        assertEquals(0.33, r.metric("gnss.dropped").value!!, 0.03)
        assertEquals(Verdict.FAIL, r.metric("gnss.max_gap").verdict, r.metric("gnss.max_gap").how)
        assertEquals(1000.0, r.metric("gnss.max_gap").value!!, 1.0)
        assertEquals(6.0, r.metric("gnss.long_gaps").value)
        assertEquals(Verdict.PASS, r.metric("soak.gnss_dropped").verdict, r.metric("soak.gnss_dropped").how)
        val video = r.metric("video.phone.frame_rate")
        assertEquals(Verdict.PASS, video.verdict, video.how)
        assertTrue(video.value!! >= 59.9, video.how)
        assertEquals(Verdict.PASS, r.metric("hr.rate").verdict, r.metric("hr.rate").how)
        assertEquals(Verdict.PASS, r.metric("hr.reconnect").verdict, r.metric("hr.reconnect").how)
    }

    @Test
    fun `the 1_8_0 motorway drives lose 2_4 to 12 % of the RaceBox's messages and keep 1080p60 at 60 fps`() {
        // Arek's three drives of 22 Oct 2026, 15:07 to 16:41, on 1.8.0, mostly on the motorway: RaceBox at
        // MTU 247, Vgate vLinker over BLE, HR strap worn, 1080p60 through the own encoder. In town the
        // RaceBox lost almost nothing beyond its whole-second skip; on the motorway it lost 20 to 450
        // messages a minute, most in a jam at walking pace, with OBD steady. One engine-off stop
        // (16:08 to 16:12). Session, Gnss, Hr, RaceBox, event and file lines, without positions.
        val reports = driveChecks(File(javaClass.getResource("/drive-log/1.8.0-1641")!!.toURI()))
        val ids = listOf("20261022-150703-4f2cdce9", "20261022-160118-dc30a0f4", "20261022-161518-7e79d322")
        assertEquals(ids, reports.map { it.sessionId })
        // The phone said 2.36, 3.60 and 12.07 %. The third drive's RaceBox link dropped 5 s after the start,
        // and the replay counts those first seconds a little differently (about 11.75 %), hence its margin.
        for ((r, dropped) in reports.zip(listOf(2.36 to 0.1, 3.60 to 0.1, 11.9 to 0.4))) {
            val m = r.metric("gnss.dropped")
            assertEquals(Verdict.FAIL, m.verdict, m.how)
            assertEquals(dropped.first, m.value!!, dropped.second, m.how)
            val video = r.metric("video.phone.frame_rate")
            assertEquals(Verdict.PASS, video.verdict, video.how)
            assertTrue(video.value!! >= 59.85, video.how)
            assertEquals(Verdict.PASS, r.metric("hr.rate").verdict, r.metric("hr.rate").how)
        }
        // The 53-min drive: many single messages missing, never a gap over 120 ms.
        assertEquals(Verdict.PASS, reports[0].metric("gnss.max_gap").verdict, reports[0].metric("gnss.max_gap").how)
        assertEquals(120.0, reports[0].metric("gnss.max_gap").value!!, 1.0)
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
