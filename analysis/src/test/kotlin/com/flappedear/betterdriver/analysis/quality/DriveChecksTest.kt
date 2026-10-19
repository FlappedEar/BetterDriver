package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.log.AppLifeLog
import com.flappedear.betterdriver.recorder.log.CheckLog
import com.flappedear.betterdriver.recorder.log.GnssSecondLog
import com.flappedear.betterdriver.recorder.log.LogLine
import com.flappedear.betterdriver.recorder.log.ProcessExit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drive checks from the continuous log alone (FBBD-183), on a synthetic 31-minute drive as the
 * app logs it: the GNSS second by second, every event, a link drop, a false GNSS banner, phone video
 * with frame times, a GoPro with GPS-aligned stretches, coach cues and a VBO export.
 */
class DriveChecksTest {
    private val start = 1_760_000_000_000L
    private val id = "20261012-103607-3713ff1a"
    private val lines = mutableListOf<LogLine>()
    private val minute = 60_000_000L
    private val end = 31 * minute

    private fun line(tMicros: Long, tag: String, message: String, process: String = "recorder", session: String? = id) {
        lines += LogLine(start + tMicros / 1_000, tMicros, process, 1, "t", 'I', tag, session, message)
    }

    private fun event(t: Long, kind: String, text: String, process: String = "recorder") =
        line(t, CheckLog.EVENT, CheckLog.eventText(t, kind, text), process)

    /** Parked for the first 70 s, then 100 km/h with a braking zone down to 60 km/h every minute. */
    private fun kmh(t: Long): Double {
        if (t < 70_000_000) return 0.0
        val inMinute = t % minute
        return if (inMinute in 40_000_000..45_000_000) 60.0 else 100.0
    }

    private fun drive(): List<QualityReport> {
        line(0, CheckLog.SESSION, "start utc_ms=$start source=RaceBox_Micro")
        val gnss = GnssSecondLog(id, start) { line(0, CheckLog.GNSS, it) }
        var i = 0L
        var t = 0L
        while (t <= end) {
            val inOutage = t in 600_000_000L until 602_000_000L
            if (t == 600_040_000L) gnss.onEvent(RecorderEvent(600_100_000, "racebox_disconnected", ""))
            // One sample in 400 never arrives: 0.25 % dropped.
            if (!inOutage && i % 400 != 399L) {
                val wobble = if (t < 70_000_000) ((i % 3) - 1) * 0.000001 else t / 1e6 * 0.00001
                gnss.onSamples(
                    SampleSet(
                        t + 30_000,
                        listOf(
                            ChannelValue(GnssChannels.UTC_MS.id, (start + t / 1_000).toDouble()),
                            ChannelValue(GnssChannels.LAT.id, 50.0 + wobble),
                            ChannelValue(GnssChannels.LON.id, 19.0),
                            ChannelValue(GnssChannels.SPEED_KMH.id, kmh(t)),
                        ),
                    ),
                )
            }
            t += 40_000
            i++
        }
        gnss.onStatus(RecorderStatus.Stopped("user"))
        // GNSS lines are timed by their own t_us.
        lines.replaceAll { l ->
            val t = CheckLog.fields(l.message)["t_us"]?.toLong()
            if (l.tag == CheckLog.GNSS && t != null) l.copy(utcMillis = start + t / 1_000) else l
        }
        event(600_100_000, "racebox_disconnected", "E7:D2:76:DC:ED:23")
        event(602_050_000, "racebox_connected", "E7:D2:76:DC:ED:23")
        // The banner after the outage, and one with fixes arriving all along.
        event(601_200_000, EventKinds.GNSS_BANNER, "state=shown fix_age_us=1170000", "main")
        event(602_500_000, EventKinds.GNSS_BANNER, "state=cleared fix_age_us=0", "main")
        event(900_000_000, EventKinds.GNSS_BANNER, "state=shown fix_age_us=1100000", "main")
        event(900_400_000, EventKinds.GNSS_BANNER, "state=cleared fix_age_us=0", "main")
        for (k in 1..62) {
            val at = k * 30_000_000L
            val thermal = if (k > 40) 1 else 0
            val device = "battery_c=${30 + k * 0.2} battery_pct=90 plugged=usb thermal=$thermal headroom=0.7"
            event(at, EventKinds.DEVICE, device)
            val frames = "frames=1800 janky=18 frozen=0 worst_ms=40 hist=900,500,330,40,15,10,5,0,0,0,0"
            event(at, EventKinds.UI_FRAMES, frames, "main")
        }
        phoneVideo()
        goPro()
        coach()
        event(0, EventKinds.VBO_EXPORT, "rows=46337 samples=46337 ms=4200", "main")
        line(end, CheckLog.SESSION, "stop t_us=$end reason=user")
        return driveChecks(lines.shuffled(java.util.Random(1)))
    }

    /** Files of 7 min; each sync report arrives 15 ms after the frame it reports was captured. */
    private fun phoneVideo() {
        var fileStart = 600_000L
        var n = 1
        while (fileStart < end) {
            val file = "phone_%04d.mp4".format(n)
            val fileEnd = minOf(end, fileStart + 7 * minute)
            event(fileStart, EventKinds.VIDEO_START, "camera=phone file=$file")
            var s = fileStart + 1_000_000
            while (s < fileEnd) {
                // The media clock runs 30 ppm slow: 12.6 ms behind after 7 min.
                val media = ((s - fileStart) * (1 - 30e-6)).toLong()
                val sync = "camera=phone file=$file media_us=$media frame_us=${s - 5_000}"
                event(s + 10_000, EventKinds.VIDEO_SYNC, sync)
                s += 1_000_000
            }
            val reason = if (fileEnd == end) "user" else "file_full"
            event(fileEnd, EventKinds.VIDEO_STOP, "camera=phone file=$file reason=$reason")
            fileStart = fileEnd + 120_000
            n++
        }
    }

    private fun goPro() {
        val cam = "gopro-1"
        event(2_100_000, EventKinds.GOPRO, "camera=$cam action=start result=ok", "main")
        event(end + 1_500_000, EventKinds.GOPRO, "camera=$cam action=stop result=ok", "main")
        for (c in 0 until 4) {
            val file = "GX0${c + 1}0001.MP4"
            val offset = 2_400_000L + c * 8 * minute
            val placed = "camera=$cam file=$file result=aligned method=GPS_MATCH " +
                "offset_us=$offset duration_us=${8 * minute}"
            event(0, EventKinds.VIDEO_IMPORT, placed, "main")
            for (p in 0 until 4) {
                val at = offset + p * 2 * minute + minute
                // The GoPro's clock runs 20 ppm fast against the GNSS.
                val drifted = offset + ((at - offset) * 20e-6).toLong()
                val probe = "camera=$cam file=$file offset_us=$drifted clip_offset_us=$offset"
                event(at, EventKinds.VIDEO_PROBE, probe, "main")
            }
        }
        val outside = "camera=gopro-2 file=GH010099.MP4 result=rejected method=CAMERA_CLOCK offset_us=-90000000000"
        event(0, EventKinds.VIDEO_IMPORT, outside, "main")
    }

    /** A cue each minute at 30 s, said live 300 ms later, 1.2 s long, to a headset measured 80 ms slower. */
    private fun coach() {
        for (k in 2..30) {
            val at = k * minute + 30_000_000
            event(at, "coach_cue", "seq=$k lap=$k kind=Corner corner=1 zone_m=200.0 turn=1 text=Turn 1", "coach")
            event(
                at + 300_000, EventKinds.COACH_VOICE,
                "seq=$k path=live asked_us=${at + 5_000} route=headset ready_us=${at + 105_000} " +
                    "device=bt_a2dp focus=granted out_delay_ms=80 out_delay_src=measured",
                "coach",
            )
            event(at + 1_500_000, EventKinds.COACH_VOICE_END, "seq=$k end=done", "coach")
        }
    }

    private fun QualityReport.metric(id: String) = metrics.single { it.id == id }

    @Test
    fun `a 31-minute drive is checked against every hardware criterion from its log alone`() {
        val r = drive().single()
        assertEquals(id, r.sessionId)
        fun check(metric: String, verdict: Verdict, value: Double? = null, tolerance: Double = 0.0) {
            val m = r.metric(metric)
            assertEquals(verdict, m.verdict, "$metric: ${m.value} ${m.how}")
            value?.let { assertEquals(it, m.value!!, tolerance, metric) }
        }
        // GNSS (FBBD-8, FBBD-46): 0.25 % dropped, one 2 s link drop left out of rate and gaps.
        check("gnss.rate", Verdict.PASS, 24.94, 0.02)
        check("gnss.dropped", Verdict.PASS, 0.25, 0.01)
        check("gnss.max_gap", Verdict.PASS, 80.0)
        check("gnss.reconnect", Verdict.PASS, 2.0, 0.05)
        check("gnss.stationary", Verdict.PASS)
        assertEquals(listOf("FBBD-8", "FBBD-46", "FBBD-170"), r.metric("gnss.rate").keys)
        // Video (FBBD-21): each file is placed by its frame times, which are 15 ms closer than the reports
        // (FBBD-218; placed by the reports alone it was 15 ms off); its clock runs slow.
        check("video.phone.sync_error", Verdict.PASS, 0.0, 0.1)
        check("video.phone.drift_10", Verdict.PASS, 3.5, 0.2)
        check("video.phone.drift_20", Verdict.PASS)
        check("video.phone.drift_30", Verdict.PASS)
        check("video.phone.coverage", Verdict.PASS)
        // GoPro (FBBD-82, FBBD-22): 20 ppm drift, start 2.1 s and stop 1.5 s after the session.
        check("gopro.gopro-1.start", Verdict.PASS, 2.1, 1e-9)
        check("gopro.gopro-1.stop", Verdict.PASS, 1.5, 1e-9)
        check("video.import", Verdict.PASS, 100.0)
        check("video.gopro-1.drift_10", Verdict.PASS)
        check("video.gopro-1.sync_error", Verdict.PASS)
        // Coach (FBBD-27, FBBD-47, FBBD-48, FBBD-49).
        check("coach.delay_live", Verdict.PASS, 300.0)
        check("coach.tts", Verdict.PASS, 100.0)
        check("coach.total_live", Verdict.PASS, 380.0)
        assertTrue("80 ms, measured by the app" in r.metric("coach.total_live").how)
        check("coach.overlap", Verdict.PASS, 0.0)
        check("coach.per_straight", Verdict.PASS, 0.0)
        check("coach.focus", Verdict.PASS, 100.0)
        check("coach.headset", Verdict.PASS, 100.0)
        check("coach.margin", Verdict.PASS, 100.0)
        check("coach.in_zone", Verdict.PASS, 0.0)
        check("coach.clips", Verdict.NOT_MEASURED)
        // Screen (FBBD-41, FBBD-62, FBBD-63, FBBD-64): one banner shown with fixes arriving all along.
        check("ui.frame_p95", Verdict.PASS, 16.0)
        check("ui.jank", Verdict.PASS, 1.0, 1e-9)
        check("ui.frozen", Verdict.PASS, 0.0)
        check("ui.screen_off", Verdict.PASS, 0.0)
        check("ui.false_banners", Verdict.FAIL, 1.0)
        check("ui.banner_show", Verdict.PASS, 1.2, 0.05)
        check("ui.banner_clear", Verdict.PASS, 0.5, 0.05)
        // Soak (FBBD-46), export (FBBD-14); no thermal shutdown, so no thermal rows.
        assertTrue(r.metrics.none { it.id.startsWith("thermal.") })
        check("soak.gnss_rate", Verdict.PASS)
        check("soak.jank", Verdict.PASS)
        check("soak.video", Verdict.PASS)
        check("export.lost", Verdict.PASS, 0.0)
        check("export.time", Verdict.PASS, 4.2, 1e-9)
        assertTrue(r.metric("gnss.clock_10").verdict == Verdict.INFO)
    }

    @Test
    fun `a short drive is no soak`(@TempDir dir: File) {
        line(0, CheckLog.SESSION, "start utc_ms=$start source=RaceBox_Micro")
        line(5 * minute, CheckLog.SESSION, "stop t_us=${5 * minute} reason=user")
        val log = File(dir, "betterdriver.log").apply { writeText(lines.joinToString("\n") { it.format() }) }
        val r = driveChecks(log).single()
        assertEquals(Verdict.NOT_MEASURED, r.metric("soak.gnss_rate").verdict)
        assertTrue("5 min" in r.metric("soak.gnss_rate").how)
    }

    @Test
    fun `a 29-minute drive is a soak`(@TempDir dir: File) {
        val length = 29 * minute + 18_000_000L
        line(0, CheckLog.SESSION, "start utc_ms=$start source=RaceBox_Micro")
        line(length, CheckLog.SESSION, "stop t_us=$length reason=user")
        val log = File(dir, "betterdriver.log").apply { writeText(lines.joinToString("\n") { it.format() }) }
        val r = driveChecks(log).single()
        assertEquals(29.0, r.metric("soak.minutes").value)
        assertTrue("a soak needs" !in r.metric("soak.gnss_rate").how)
    }

    /**
     * A 2.7K recording of 7.5 min as the app logs it: video_start with the set rate, sync reports, and
     * WideRecorder's line for the file it closed, written after the session stopped.
     */
    private fun wideDrive(dir: File, frames: Long): QualityReport {
        val length = 450_000_000L
        line(0, CheckLog.SESSION, "start utc_ms=$start source=Demo_track")
        event(500_000, EventKinds.VIDEO_START, "camera=phone file=phone_0001.mp4 fps=60")
        var s = 1_500_000L
        while (s < length) {
            event(s, EventKinds.VIDEO_SYNC, "camera=phone file=phone_0001.mp4 media_us=${s - 500_000} frame_us=$s")
            s += 1_000_000
        }
        line(length, CheckLog.SESSION, "stop t_us=$length reason=user")
        val wide = "phone_0001.mp4: 2688x1512@60 $frames frames in 446.5 s = 0.0 fps 2045178095 bytes"
        line(length + 900_000, "WideRecorder", wide, process = "camera")
        val log = File(dir, "betterdriver.log").apply { writeText(lines.joinToString("\n") { it.format() }) }
        return driveChecks(log).single()
    }

    @Test
    fun `a 2_7K file that holds a fifth fewer frames fails the frame rate check`(@TempDir dir: File) {
        // Arek's 2026-10-19 16:18 replay on 0.3.43: 21,603 frames in 446.5 s at 2.7K·60 (FBBD-246).
        val m = wideDrive(dir, frames = 21_603).metric("video.phone.frame_rate")
        assertEquals(Verdict.FAIL, m.verdict, m.how)
        assertEquals(48.38, m.value!!, 0.01)
        assertEquals(listOf("FBBD-239", "FBBD-246"), m.keys)
        assertTrue("set to 60 fps" in m.how)
    }

    @Test
    fun `a 2_7K file at the camera's own 59_5 fps passes the frame rate check`(@TempDir dir: File) {
        // The sensor's 16.814 ms frame time from the same log: 59.47 fps.
        val m = wideDrive(dir, frames = 26_555).metric("video.phone.frame_rate")
        assertEquals(Verdict.PASS, m.verdict, m.how)
        assertEquals(59.47, m.value!!, 0.01)
    }

    @Test
    fun `a file with a set rate but no count is named in the frame rate check`(@TempDir dir: File) {
        event(460_000_000, EventKinds.VIDEO_START, "camera=phone file=phone_0002.mp4 fps=60")
        val m = wideDrive(dir, frames = 26_555).metric("video.phone.frame_rate")
        assertEquals(Verdict.PASS, m.verdict)
        assertTrue("not counted: phone_0002.mp4" in m.how, m.how)
    }

    @Test
    fun `phone video with a set rate but no counted file has its frame rate not measured`() {
        line(0, CheckLog.SESSION, "start utc_ms=$start source=Demo_track")
        event(500_000, EventKinds.VIDEO_START, "camera=phone file=phone_0001.mp4 fps=60")
        event(1_500_000, EventKinds.VIDEO_SYNC, "camera=phone file=phone_0001.mp4 media_us=1000000 frame_us=1500000")
        line(minute, CheckLog.SESSION, "stop t_us=$minute reason=user")
        val m = driveChecks(lines).single().metric("video.phone.frame_rate")
        assertEquals(Verdict.NOT_MEASURED, m.verdict)
    }

    /** A line of the app outside any recording, [tMicros] from the first recording's start. */
    private fun app(tMicros: Long, tag: String, message: String) = line(tMicros, tag, message, "main", session = null)

    private fun exit(tMicros: Long, reason: String, pid: Int = 4711) =
        AppLifeLog.exit(ProcessExit(start + tMicros / 1_000, pid, "com.flappedear.betterdriver", reason, 100, "x y"))

    /** A 5-minute recording [at] after the first one's start, with session id [sid]. */
    private fun recording(sid: String, at: Long) {
        line(at, CheckLog.SESSION, "start utc_ms=${start + at / 1_000} source=RaceBox_Micro", session = sid)
        line(at + 5 * minute, CheckLog.SESSION, "stop t_us=${5 * minute} reason=user", session = sid)
    }

    @Test
    fun `cold start and ANRs are graded from the app's lines around each recording`() {
        val second = "20261012-113607-0badf00d"
        app(-60_000_000, AppLifeLog.STARTUP, AppLifeLog.coldStart(2_100))
        app(-59_000_000, AppLifeLog.EXIT, AppLifeLog.check(34, 0))
        recording(id, 0)
        // During the first: the UI process stopped answering and Android ended it; a crash is no ANR.
        val anr = exit(2 * minute, ProcessExit.ANR)
        val crash = exit(3 * minute, "CRASH", pid = 4712)
        // Read when the recording ended, by the recorder, and again at the next start, by the UI.
        app(5 * minute + 1_000_000, AppLifeLog.EXIT, AppLifeLog.check(34, 2))
        app(5 * minute + 1_000_000, AppLifeLog.EXIT, anr)
        app(5 * minute + 1_000_000, AppLifeLog.EXIT, crash)
        app(10 * minute, AppLifeLog.STARTUP, AppLifeLog.coldStart(820))
        app(10 * minute, AppLifeLog.EXIT, anr)
        recording(second, 20 * minute)
        app(25 * minute + 1_000_000, AppLifeLog.EXIT, AppLifeLog.check(34, 0))
        val (first, next) = driveChecks(lines.shuffled(java.util.Random(2)))
        fun QualityReport.check(id: String, verdict: Verdict, value: Double) {
            val m = this.metric(id)
            assertEquals(verdict, m.verdict, "$sessionId $id: ${m.value} ${m.how}")
            assertEquals(value, m.value)
            assertEquals(listOf("FBBD-41"), m.keys)
        }
        // The slow start led to the first drive; the second only counts the start after the first ended.
        first.check("app.cold_start", Verdict.FAIL, 2_100.0)
        next.check("app.cold_start", Verdict.PASS, 820.0)
        // The ANR read twice is one, and it was in the first drive only.
        first.check("app.anr", Verdict.FAIL, 1.0)
        next.check("app.anr", Verdict.PASS, 0.0)
    }

    @Test
    fun `an ANR that ended an unfinished recording is that recording's`() {
        // No stop line: the recorder stopped answering 2 minutes in and Android ended it a little later.
        line(0, CheckLog.SESSION, "start utc_ms=$start source=RaceBox_Micro")
        event(2 * minute, "racebox_connected", "E7:D2:76:DC:ED:23")
        val anr = exit(2 * minute + 6_000_000, ProcessExit.ANR)
        // Read at the next start of the app, which was cold.
        app(9 * minute, AppLifeLog.STARTUP, AppLifeLog.coldStart(900))
        app(9 * minute, AppLifeLog.EXIT, AppLifeLog.check(34, 1))
        app(9 * minute, AppLifeLog.EXIT, anr)
        recording("20261012-113607-0badf00d", 20 * minute)
        app(25 * minute + 1_000_000, AppLifeLog.EXIT, AppLifeLog.check(34, 0))
        val (first, next) = driveChecks(lines.toList())
        assertEquals(Verdict.FAIL, first.metric("app.anr").verdict, first.metric("app.anr").how)
        assertEquals(1.0, first.metric("app.anr").value)
        assertEquals(Verdict.PASS, next.metric("app.anr").verdict, next.metric("app.anr").how)
        // The start after the unfinished recording led to the next drive only.
        assertEquals(Verdict.NOT_MEASURED, first.metric("app.cold_start").verdict, first.metric("app.cold_start").how)
        assertEquals(900.0, next.metric("app.cold_start").value)
    }

    @Test
    fun `a cold start during an unfinished recording is not graded again for the next`() {
        line(0, CheckLog.SESSION, "start utc_ms=$start source=RaceBox_Micro")
        app(minute, AppLifeLog.STARTUP, AppLifeLog.coldStart(2_000))
        event(3 * minute, "racebox_connected", "E7:D2:76:DC:ED:23")
        recording("20261012-113607-0badf00d", 20 * minute)
        val (first, next) = driveChecks(lines.toList())
        assertEquals(2_000.0, first.metric("app.cold_start").value)
        assertEquals(Verdict.NOT_MEASURED, next.metric("app.cold_start").verdict, next.metric("app.cold_start").how)
    }

    @Test
    fun `an exit record that could not be read measures no ANRs`() {
        recording(id, 0)
        app(6 * minute, AppLifeLog.EXIT, "check sdk=34 failed")
        val r = driveChecks(lines.toList()).single()
        assertEquals(Verdict.NOT_MEASURED, r.metric("app.anr").verdict)
        assertTrue("could not be read" in r.metric("app.anr").how, r.metric("app.anr").how)
    }

    @Test
    fun `the ANR check counts only the ANRs that ended a process`() {
        recording(id, 0)
        app(6 * minute, AppLifeLog.EXIT, AppLifeLog.check(34, 0))
        val m = driveChecks(lines.toList()).single().metric("app.anr")
        assertEquals("ANR process deaths", m.label)
        assertTrue("process" in m.criterion.orEmpty(), m.criterion)
        assertTrue("recovered" in m.how, m.how)
    }

    @Test
    fun `without the app's lines cold start and ANRs are not measured, and say why`() {
        recording(id, 0)
        val none = driveChecks(lines.toList()).single()
        assertEquals(Verdict.NOT_MEASURED, none.metric("app.cold_start").verdict)
        assertEquals(Verdict.NOT_MEASURED, none.metric("app.anr").verdict)
        assertTrue("no read after this recording" in none.metric("app.anr").how)
        // Android 10 keeps no record of how processes ended.
        app(6 * minute, AppLifeLog.EXIT, AppLifeLog.check(29, null))
        val old = driveChecks(lines.toList()).single()
        assertTrue("before 11" in old.metric("app.anr").how, old.metric("app.anr").how)
        // A read during the drive does not cover its end.
        lines.clear()
        recording(id, 0)
        app(minute, AppLifeLog.EXIT, AppLifeLog.check(34, 0))
        assertEquals(Verdict.NOT_MEASURED, driveChecks(lines.toList()).single().metric("app.anr").verdict)
    }

    @Test
    fun `the phone clock against the GNSS counts from the first fix, not the receiver's guess before it`() {
        // 2026-10-18: no fix for 33 s, the receiver's time 178 ms ahead until then; the phone clock then
        // drifts 1 ms a minute against the receiver's.
        line(0, CheckLog.SESSION, "start utc_ms=$start source=RaceBox_Micro")
        val gnss = GnssSecondLog(id, start) { line(0, CheckLog.GNSS, it) }
        var t = 0L
        while (t <= 12 * minute) {
            val fix = t >= 33_520_000L
            val utc = start + t / 1_000 + (if (fix) -t / minute else 178L)
            val position = if (fix) 50.0 else 0.0
            gnss.onSamples(
                SampleSet(
                    t + 30_000,
                    listOf(
                        ChannelValue(GnssChannels.UTC_MS.id, utc.toDouble()),
                        ChannelValue(GnssChannels.LAT.id, position),
                        ChannelValue(GnssChannels.LON.id, if (fix) 19.0 else 0.0),
                        ChannelValue(GnssChannels.SPEED_KMH.id, 50.0),
                    ),
                ),
            )
            t += 40_000
        }
        gnss.onStatus(RecorderStatus.Stopped("user"))
        lines.replaceAll { l ->
            val tl = CheckLog.fields(l.message)["t_us"]?.toLong()
            if (l.tag == CheckLog.GNSS && tl != null) l.copy(utcMillis = start + tl / 1_000) else l
        }
        // A log before 0.3.31 took the lag of the second the fix began in over its samples without a fix too.
        lines.replaceAll { l ->
            val f = CheckLog.fields(l.message)
            val lag = f["lag_us"]
            if (l.tag == CheckLog.GNSS && f["sec"] == "33" && lag != null) {
                l.copy(message = l.message.replace("lag_us=$lag", "lag_us=${lag.toLong() - 178_000}"))
            } else {
                l
            }
        }
        line(12 * minute, CheckLog.SESSION, "stop t_us=${12 * minute} reason=user")
        val clock = driveChecks(lines.toList()).single().metric("gnss.clock_10")
        // 10 min less the first minute with a fix: about 9 ms, not the 187 ms the guess gave.
        assertEquals(9.0, clock.value!!, 1.5, clock.how)
        assertTrue("first minute with a fix" in clock.how, clock.how)
    }
}
