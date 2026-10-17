package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.quality.Basis
import com.flappedear.betterdriver.analysis.quality.PreviousSession
import com.flappedear.betterdriver.analysis.quality.QualityMetric
import com.flappedear.betterdriver.analysis.quality.QualityReport
import com.flappedear.betterdriver.analysis.quality.Verdict
import com.flappedear.betterdriver.analysis.quality.sessionQuality
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.thermalText
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The session validation report (FBBD-183) on synthetic sessions with known gaps, drops and reconnects. */
class SessionQualityTest {
    private val start = 1_760_000_000_000L
    private val frame = LocalFrame(GeoPoint(50.0, 19.0))

    /** A 25 Hz fix at session time [t] µs, the receiver's time in step with it unless [utc] says otherwise. */
    private fun fix(t: Long, utc: Long? = start + t / 1_000, x: Double = 0.0, kmh: Double = 100.0): SampleSet {
        val p = frame.toGeo(Xy(x, 0.0))
        return SampleSet(
            t,
            listOfNotNull(
                utc?.let { ChannelValue(GnssChannels.UTC_MS.id, it.toDouble()) },
                ChannelValue(GnssChannels.LAT.id, p.lat),
                ChannelValue(GnssChannels.LON.id, p.lon),
                ChannelValue(GnssChannels.SPEED_KMH.id, kmh),
            ),
        )
    }

    /** Fixes every 40 ms from [from] to [to] µs, both included. */
    private fun stream(from: Long, to: Long, kmh: Double = 100.0) = (from..to step 40_000L).map { fix(it, kmh = kmh) }

    private fun session(
        sets: List<SampleSet>,
        events: List<RecorderEvent> = emptyList(),
        state: SessionState = SessionState.FINISHED,
        id: String = "s",
        startUtc: Long = start,
    ) = SessionData(
        id, SessionInfo(startUtc), state, emptyMap(), sets, events,
        Record.End(sets.last().tMicros, "user"), truncatedBytes = 0, skippedBytes = 0,
    )

    private fun QualityReport.metric(id: String): QualityMetric = metrics.single { it.id == id }

    private fun QualityReport.value(id: String): Double? = metric(id).value

    private fun QualityReport.verdict(id: String): Verdict = metric(id).verdict

    @Test
    fun `a clean 25 Hz minute passes rate, drops and gaps`() {
        val r = sessionQuality(session(stream(0, 60_000_000)))
        assertEquals(25.0, r.value("gnss.rate"))
        assertEquals(Verdict.PASS, r.verdict("gnss.rate"))
        assertEquals(25.0, r.value("gnss.rate_p1"))
        assertEquals(0.0, r.value("gnss.dropped"))
        assertEquals(Verdict.PASS, r.verdict("gnss.dropped"))
        assertEquals(40.0, r.value("gnss.max_gap"))
        assertEquals(40.0, r.value("gnss.mean_gap"))
        assertEquals(0.0, r.value("gnss.long_gaps"))
        assertEquals(0.0, r.value("gnss.link_drops"))
        assertEquals(Verdict.NOT_MEASURED, r.verdict("gnss.reconnect"))
        assertEquals(Basis.MEASURED, r.metric("gnss.dropped").basis)
    }

    @Test
    fun `missing samples are counted from the receiver's fix times, a long gap fails`() {
        // Ten single samples missing (80 ms steps, off the whole second) and seven in a row (a 320 ms gap): 17 of 1501.
        val singles = (1..10).map { it * 100 + 51 }.toSet()
        val run = 1_000..1_006
        val sets = stream(0, 60_000_000).filterIndexed { i, _ -> i !in singles && i !in run }
        val r = sessionQuality(session(sets))
        assertEquals(1_484, sets.size)
        assertEquals(17 * 100.0 / 1_501, r.value("gnss.dropped"))
        assertEquals(Verdict.FAIL, r.verdict("gnss.dropped"))
        assertEquals(320.0, r.value("gnss.max_gap"))
        assertEquals(Verdict.FAIL, r.verdict("gnss.max_gap"))
        assertEquals(1.0, r.value("gnss.long_gaps"))
        // 1483 intervals over 60 s on the phone clock still make 24.7 Hz.
        assertEquals(1_483 / 60.0, r.value("gnss.rate"))
        assertEquals(Verdict.PASS, r.verdict("gnss.rate"))
        val gap = r.sections.first { it.title == "GNSS" }.notes.single()
        assertEquals(1_007 * 40_000L, gap.tMicros)
        assertEquals("gap 320 ms", gap.text)
    }

    @Test
    fun `the RaceBox's single missing whole-second message is counted apart and does not fail`() {
        // FBBD-170: with a fix the RaceBox sends 24 of its 25 messages, never the whole-second one.
        val sets = stream(0, 60_000_000).filterIndexed { i, _ -> i == 0 || i % 25 != 0 }
        fun from(kept: List<SampleSet>, device: String) =
            sessionQuality(session(kept).copy(info = SessionInfo(start, devices = listOf(device))))
        val r = from(sets, "racebox")
        assertEquals(0.0, r.value("gnss.dropped"))
        assertEquals(Verdict.PASS, r.verdict("gnss.dropped"))
        assertTrue("besides 59 single whole-second messages" in r.metric("gnss.dropped").how)
        // Two in a row lost across a whole second are a real loss.
        val pairs = stream(0, 60_000_000).filterIndexed { i, _ -> i == 0 || (i % 25 != 0 && i % 25 != 1) }
        assertEquals(Verdict.FAIL, from(pairs, "racebox").verdict("gnss.dropped"))
        // Any other receiver losing one sample a second loses real data.
        assertEquals(Verdict.FAIL, from(sets, "nmea").verdict("gnss.dropped"))
        // Arek's 2026-10-14 13:02 log: two more lost off the whole second take the 24 received a second under
        // 24 Hz. The skip counts as sent for the rate too; the two lost are the dropped check's to judge.
        val twoLost = sets.filterIndexed { i, _ -> i != 300 && i != 700 }
        val q = from(twoLost, "racebox")
        assertEquals(Verdict.PASS, q.verdict("gnss.rate"), q.metric("gnss.rate").how)
        assertEquals((1_438 + 59) / 59.96, q.value("gnss.rate")!!, 1e-6)
        assertTrue("received: 23.98 Hz" in q.metric("gnss.rate").how)
        assertEquals(Verdict.PASS, q.verdict("gnss.dropped"))
        assertEquals(Verdict.FAIL, from(twoLost, "nmea").verdict("gnss.rate"))
    }

    @Test
    fun `the RaceBox Micro's whole-second skip before its first fix does not fail either`() {
        // 2026-10-16 midday: the Micro skipped the whole-second message through 2 min without a fix (0, 0),
        // and those 118 counted as dropped: 0.76 %, a FAIL with no message really lost.
        val position = setOf(GnssChannels.LAT.id, GnssChannels.LON.id)
        val noFix = { s: SampleSet ->
            SampleSet(s.tMicros, s.values.map { if (it.channelId in position) ChannelValue(it.channelId, 0.0) else it })
        }
        val sets = stream(0, 60_000_000).mapIndexedNotNull { i, s ->
            when {
                i != 0 && i % 25 == 0 -> null
                s.tMicros < 30_000_000 -> noFix(s)
                else -> s
            }
        }
        val mini = session(sets).copy(info = SessionInfo(start, devices = listOf("racebox")))
        // Only the Micro has an input voltage channel; a Mini sends the message without a fix, so it is lost.
        val micro = mini.copy(channels = mapOf(120 to ChannelDef(120, "racebox_input", "V", "racebox", 1)))
        val r = sessionQuality(micro)
        assertEquals(0.0, r.value("gnss.dropped"))
        assertTrue("besides 59 single whole-second messages" in r.metric("gnss.dropped").how)
        // The 30 losses without a fix on both sides count: 30 of 1500, 2 %.
        assertEquals(Verdict.FAIL, sessionQuality(mini).verdict("gnss.dropped"))
    }

    @Test
    fun `late arrivals with steady fix times are no drops, but gaps without fix times are timed on arrival`() {
        // The phone got samples in pairs (BLE batching): 0 and 80 ms apart, the receiver's times 40 ms apart.
        val batched = (0..1_500).map { i -> fix(t = (i / 2) * 80_000L, utc = start + i * 40L) }
        val r = sessionQuality(session(batched))
        assertEquals(0.0, r.value("gnss.dropped"))
        assertEquals(40.0, r.value("gnss.max_gap"))
        val noUtc = (0..1_500).map { i -> fix(t = (i / 2) * 80_000L, utc = null) }
        val q = sessionQuality(session(noUtc))
        assertEquals(Verdict.NOT_MEASURED, q.verdict("gnss.dropped"))
        assertEquals(80.0, q.value("gnss.max_gap"))
        assertTrue("arrival" in q.metric("gnss.max_gap").how)
    }

    @Test
    fun `a reconnect is timed from the last sample before the drop to the first after, and left out of gaps`() {
        val drop = RecorderEvent(21_000_000, "racebox_disconnected", "timeout")
        val sets = stream(0, 20_000_000) + stream(24_000_000, 40_000_000)
        val r = sessionQuality(session(sets, listOf(drop)))
        assertEquals(1.0, r.value("gnss.link_drops"))
        assertEquals(4.0, r.value("gnss.reconnect"))
        assertEquals(Verdict.PASS, r.verdict("gnss.reconnect"))
        assertEquals(40.0, r.value("gnss.max_gap"))
        assertEquals(0.0, r.value("gnss.dropped"))
        // 500 + 400 intervals over 20 s + 16 s.
        assertEquals(25.0, r.value("gnss.rate"))
        val note = r.sections.first { it.title == "GNSS" }.notes.single()
        assertEquals("link drop (racebox_disconnected timeout): 4.0 s without data, data back", note.text)

        val slow = sessionQuality(session(stream(0, 20_000_000) + stream(27_000_000, 40_000_000), listOf(drop)))
        assertEquals(7.0, slow.value("gnss.reconnect"))
        assertEquals(Verdict.FAIL, slow.verdict("gnss.reconnect"))

        // Heart rate is no GNSS link; its drops are not reconnects.
        val hr = sessionQuality(session(sets, listOf(drop.copy(kind = "hr_disconnected"))))
        assertEquals(0.0, hr.value("gnss.link_drops"))
        assertEquals(4_000.0, hr.value("gnss.max_gap"))
    }

    @Test
    fun `a drop the data never comes back from fails and runs to the end`() {
        val sets = stream(0, 20_000_000)
        val data = session(sets, listOf(RecorderEvent(21_000_000, "nmea_disconnected", "lost")))
            .copy(end = Record.End(30_000_000, "user"))
        val r = sessionQuality(data)
        assertEquals(10.0, r.value("gnss.reconnect"))
        assertEquals(Verdict.FAIL, r.verdict("gnss.reconnect"))
    }

    @Test
    fun `a drop while parked just before the driver stops is the drive ending, not a failed reconnect`() {
        // The 2026-10-14 drive: parked, the link dropped 7 s before the stop and never came back.
        val sets = stream(0, 20_000_000, kmh = 0.2)
        val drop = RecorderEvent(21_000_000, "racebox_disconnected", "no data for 3877 ms")
        val parked = session(sets, listOf(drop)).copy(end = Record.End(28_000_000, "user"))
        val r = sessionQuality(parked)
        assertEquals(1.0, r.value("gnss.link_drops"))
        assertEquals(Verdict.NOT_MEASURED, r.verdict("gnss.reconnect"))
        val note = r.sections.first { it.title == "GNSS" }.notes.single()
        assertTrue("parked at the end of the session" in note.text)

        // Still driving, or the recording ran on long after the drop: a failed reconnect.
        val driving = session(stream(0, 20_000_000), listOf(drop)).copy(end = Record.End(28_000_000, "user"))
        assertEquals(Verdict.FAIL, sessionQuality(driving).verdict("gnss.reconnect"))
        val longAfter = parked.copy(end = Record.End(60_000_000, "user"))
        assertEquals(Verdict.FAIL, sessionQuality(longAfter).verdict("gnss.reconnect"))
        // A fix with no speed (GGA-only NMEA) does not show the car parked.
        val noSpeed = sets.map { set ->
            set.copy(values = set.values.filter { it.channelId != GnssChannels.SPEED_KMH.id })
        }
        val unknown = session(noSpeed, listOf(drop)).copy(end = Record.End(28_000_000, "user"))
        assertEquals(Verdict.FAIL, sessionQuality(unknown).verdict("gnss.reconnect"))
    }

    @Test
    fun `position noise is the 2D standard deviation of the first 60 s of a stop`() {
        val moving = stream(0, 10_000_000)
        // Parked 70 s, east positions alternating +0.3 and -0.3 m: 1501 fixes in the first 60 s.
        val parked = (0..1_750).map { i ->
            fix(10_040_000L + i * 40_000L, x = if (i % 2 == 0) 0.3 else -0.3, kmh = 0.2)
        }
        val r = sessionQuality(session(moving + parked))
        val mean = 0.3 / 1_501
        assertEquals(kotlin.math.sqrt(0.09 - mean * mean), r.value("gnss.stationary")!!, 1e-7)
        assertEquals(Verdict.PASS, r.verdict("gnss.stationary"))
        val noisy = (0..1_750).map { i -> fix(10_040_000L + i * 40_000L, x = if (i % 2 == 0) 0.6 else -0.6, kmh = 0.2) }
        assertEquals(Verdict.FAIL, sessionQuality(session(moving + noisy)).verdict("gnss.stationary"))
        // A stop under 60 s measures nothing.
        val short = parked.take(1_000)
        assertEquals(Verdict.NOT_MEASURED, sessionQuality(session(moving + short)).verdict("gnss.stationary"))
    }

    @Test
    fun `laps far from the median are flagged and the predicted lap time is checked at the line`() {
        val laps = listOf(
            "lap=1 kind=Out time_ms=120000 predicted_ms=none",
            "lap=2 kind=Timed time_ms=90000 predicted_ms=none",
            "lap=3 kind=Timed time_ms=91000 predicted_ms=91050",
            "lap=4 kind=Timed time_ms=45000 predicted_ms=90980",
            "lap=5 kind=Timed time_ms=180500 predicted_ms=180400",
            "lap=6 kind=Timed time_ms=92000 predicted_ms=92150",
        ).mapIndexed { i, text -> RecorderEvent((i + 1) * 100_000_000L, EventKinds.LAP_LINE, text) }
        val r = sessionQuality(session(stream(0, 1_000_000), laps))
        assertEquals(5.0, r.value("timing.laps"))
        assertEquals(2.0, r.value("timing.suspect"))
        assertEquals(Verdict.FAIL, r.verdict("timing.suspect"))
        assertEquals(Basis.DERIVED, r.metric("timing.suspect").basis)
        val notes = r.sections.first { it.title == "Timing" }.notes.map { it.text }
        assertEquals(
            listOf(
                "lap 4: possible phantom lap (49 % of the median)",
                "lap 5: possible missed line (198 % of the median)",
            ),
            notes,
        )
        // |90980 - 45000| is the worst: the phantom lap ended long before the prediction.
        assertEquals(45_980.0, r.value("timing.predicted"))
        assertEquals(Verdict.FAIL, r.verdict("timing.predicted"))

        val twoAndThree = laps.filter { "lap=3" in it.text || "lap=2" in it.text }
        val good = sessionQuality(session(stream(0, 1_000_000), twoAndThree))
        assertEquals(50.0, good.value("timing.predicted"))
        assertEquals(Verdict.PASS, good.verdict("timing.predicted"))
        assertEquals(Verdict.NOT_MEASURED, good.verdict("timing.suspect"))
    }

    @Test
    fun `data lost at a recorder restart is measured on the receiver's clock, else the phone's`() {
        val restart = RecorderEvent(0, EventKinds.RECORDER_RESTART, "previous=A")
        // This session starts 5 s after A did; its first fix's receiver time is 1.5 s after A's last.
        val sets = (0..100).map { i -> fix(t = 200_000L + i * 40_000L, utc = start + 1_000 + 1_500 + i * 40L) }
        val previous = PreviousSession("A", start - 5_000, lastSampleMicros = 6_000_000, lastUtcMillis = start + 1_000)
        val r = sessionQuality(session(sets, listOf(restart)), previous = previous)
        assertEquals(1.5, r.value("recording.restart_loss"))
        assertEquals(Verdict.PASS, r.verdict("recording.restart_loss"))
        assertEquals(Basis.MEASURED, r.metric("recording.restart_loss").basis)
        // Without fix times, on the phone clock: 5 s between the starts, plus 0.2 s, less A's last sample at 2 s.
        val wall = sessionQuality(
            session(
                sets.map { s -> s.copy(values = s.values.filter { it.channelId != GnssChannels.UTC_MS.id }) },
                listOf(restart),
            ),
            previous = previous.copy(lastSampleMicros = 2_000_000, lastUtcMillis = null),
        )
        assertEquals(3.2, wall.value("recording.restart_loss"))
        assertEquals(Verdict.FAIL, wall.verdict("recording.restart_loss"))
        assertEquals(Basis.DERIVED, wall.metric("recording.restart_loss").basis)
        val gone = sessionQuality(session(sets, listOf(restart)))
        assertEquals(Verdict.NOT_MEASURED, gone.verdict("recording.restart_loss"))
    }

    @Test
    fun `samples the recorder dropped fail, and a recovered session says it was cut off`() {
        val drops = listOf(
            RecorderEvent(1_000, EventKinds.DROPPED_SAMPLES, "3"),
            RecorderEvent(2_000, EventKinds.DROPPED_SAMPLES, "12"),
        )
        val r = sessionQuality(session(stream(0, 1_000_000), drops, SessionState.RECOVERED))
        assertEquals(12.0, r.value("recording.queue_drops"))
        assertEquals(Verdict.FAIL, r.verdict("recording.queue_drops"))
        assertTrue(r.metric("recording.end").how.startsWith("interrupted, then recovered"))
        assertEquals(0.0, r.value("recording.restarts"))
    }

    @Test
    fun `video drift comes from the lowest sync offsets of the first and last minute, coverage from the files`() {
        val file = "camera=phone file=phone_0001.mp4"
        val events = mutableListOf(RecorderEvent(1_000_000, EventKinds.VIDEO_START, file))
        for (s in 2..601) {
            val t = s * 1_000_000L
            // Offset 1.01 s plus a drift of 1e-4 (59.9 ms at 601 s); every fifth report 30 ms late.
            val offset = 1_010_000L + (t - 2_000_000L) / 10_000 + if (s % 5 == 0) 30_000 else 0
            events += RecorderEvent(t, EventKinds.VIDEO_SYNC, "$file media_us=${t - offset}")
        }
        events += RecorderEvent(601_000_000, EventKinds.VIDEO_STOP, "$file reason=user")
        val r = sessionQuality(session(stream(0, 600_000_000), events))
        assertEquals(1_010.0, r.value("video.phone.offset"))
        assertEquals(Basis.DERIVED, r.metric("video.phone.offset").basis)
        // The last minute starts at 541 s: 539 s of drift at 1e-4.
        assertEquals(53.9, r.value("video.phone.drift"))
        assertEquals(Verdict.PASS, r.verdict("video.phone.drift"))
        assertEquals(598_990_000 * 100.0 / 600_000_000, r.value("video.phone.coverage"))
        assertEquals(Verdict.PASS, r.verdict("video.phone.coverage"))
        assertEquals(Verdict.NOT_MEASURED, r.verdict("video.phone.sync_error"))
    }

    @Test
    fun `the drift of a file longer than 30 min is judged per 30 min`() {
        val file = "camera=phone file=phone_0001.mp4"
        val events = mutableListOf(RecorderEvent(1_000_000, EventKinds.VIDEO_START, file))
        for (s in 2..3602) {
            val t = s * 1_000_000L
            // A drift of 5e-5: 90 ms per 30 min, 177 ms from the first report to the last minute's.
            val offset = 1_010_000L + (t - 2_000_000L) / 20_000
            events += RecorderEvent(t, EventKinds.VIDEO_SYNC, "$file media_us=${t - offset}")
        }
        events += RecorderEvent(3_602_000_000, EventKinds.VIDEO_STOP, "$file reason=user")
        val r = sessionQuality(session(stream(0, 1_000_000), events))
        // 177 ms over the 3540 s between the two lowest offsets (2 s and 3542 s), as 30 min of it.
        assertEquals(90.0, r.value("video.phone.drift"))
        assertEquals(Verdict.PASS, r.verdict("video.phone.drift"))
    }

    @Test
    fun `a drift just over the limit on a 31 min file is scaled over the reports used, and fails`() {
        val file = "camera=phone file=phone_0001.mp4"
        val events = mutableListOf(RecorderEvent(1_000_000, EventKinds.VIDEO_START, file))
        for (s in 2..1862) {
            val t = s * 1_000_000L
            // 103 ms per 30 min; the lowest offsets are at 2 s and 1802 s, 30 min apart.
            val offset = 1_010_000L + (t - 2_000_000L) * 103 / 1_800_000
            events += RecorderEvent(t, EventKinds.VIDEO_SYNC, "$file media_us=${t - offset}")
        }
        events += RecorderEvent(1_862_000_000, EventKinds.VIDEO_STOP, "$file reason=user")
        val r = sessionQuality(session(stream(0, 1_000_000), events))
        assertEquals(103.0, r.value("video.phone.drift"))
        assertEquals(Verdict.FAIL, r.verdict("video.phone.drift"))
    }

    @Test
    fun `each cue's delay, margin to the braking zone and route are measured`() {
        val sets = stream(0, 30_000_000, kmh = 180.0)
        val events = listOf(
            RecorderEvent(
                10_000_000, "coach_cue",
                "seq=1 lap=4 kind=Corner corner=2 zone_m=100.0 text=Turn 2, brake 5 metres later",
            ),
            RecorderEvent(10_300_000, EventKinds.COACH_VOICE, "seq=1 path=live asked_us=10010000 route=headset"),
            RecorderEvent(
                20_000_000, "coach_cue", "seq=2 lap=5 kind=Corner corner=3 zone_m=60.0 text=Turn 3, carry 4 more",
            ),
            RecorderEvent(20_500_000, EventKinds.COACH_VOICE, "seq=2 path=clip asked_us=20010000 route=speaker"),
            RecorderEvent(25_000_000, "coach_problem", "late seq=3 text=1 32 4"),
        )
        val r = sessionQuality(session(sets, events))
        assertEquals(2.0, r.value("coach.cues"))
        assertEquals(300.0, r.value("coach.delay_live"))
        assertEquals(Verdict.PASS, r.verdict("coach.delay_live"))
        assertEquals(500.0, r.value("coach.delay_clip"))
        assertEquals(Verdict.FAIL, r.verdict("coach.delay_clip"))
        // At 50 m/s: 100 - 15 = 85 m (1.7 s) in time; 60 - 25 = 35 m (0.7 s) late.
        assertEquals(50.0, r.value("coach.margin"))
        assertEquals(Verdict.FAIL, r.verdict("coach.margin"))
        assertEquals(0.0, r.value("coach.in_zone"))
        assertEquals(50.0, r.value("coach.headset"))
        assertEquals(Verdict.FAIL, r.verdict("coach.headset"))
        assertEquals(Verdict.NOT_MEASURED, r.verdict("coach.overlap"))
        val notes = r.sections.first { it.title == "Coach" }.notes.map { it.text }
        assertEquals(
            listOf(
                "cue 1 (T2): 300 ms to sound, live, headset, 85 m to the braking zone",
                "cue 2 (T3): 500 ms to sound, clip, speaker, 35 m to the braking zone",
            ),
            notes,
        )
    }

    @Test
    fun `cues from before sound starts were logged are not measured`() {
        val events = listOf(RecorderEvent(10_000_000, "coach_cue", "seq=1 lap=4 kind=LapTime text=1 31 2"))
        val r = sessionQuality(session(stream(0, 20_000_000), events))
        assertEquals(Verdict.NOT_MEASURED, r.verdict("coach.delay"))
        assertEquals(Verdict.NOT_MEASURED, r.verdict("coach.headset"))
        assertEquals(Verdict.NOT_MEASURED, r.verdict("coach.margin"))
    }

    @Test
    fun `severe heat passes, and a 60 degree battery passes only with video stopped`() {
        // 2026-10-17: Android said critical at a 45 °C battery; that alone no longer fails a session.
        val statuses = listOf(1_000_000L to 0, 100_000_000L to 3, 300_000_000L to 4).map { (t, s) ->
            RecorderEvent(t, EventKinds.THERMAL_STATUS, "status=$s headroom=1.1")
        }
        val warm = RecorderEvent(200_000_000, EventKinds.DEVICE, "battery_c=45.0 thermal=4 headroom=1.1")
        val hot = RecorderEvent(500_000_000, EventKinds.DEVICE, "battery_c=60.2 thermal=4 headroom=1.2")
        val start = RecorderEvent(1_000_000, EventKinds.VIDEO_START, "camera=phone file=phone_0001.mp4")
        val stop =
            RecorderEvent(503_000_000, EventKinds.VIDEO_STOP, "camera=phone file=phone_0001.mp4 reason=thermal")
        val sets = stream(0, 1_000_000)
        val r = sessionQuality(session(sets, statuses + warm + start))
        assertEquals(4.0, r.value("thermal.max"))
        assertEquals(Verdict.PASS, r.verdict("thermal.status"))
        val stopped = sessionQuality(session(sets, statuses + warm + start + hot + stop))
        assertEquals(Verdict.PASS, stopped.verdict("thermal.status"))
        assertEquals(1.0, sessionQuality(session(sets, statuses + hot + stop)).value("thermal.step_downs"))
        assertEquals(Verdict.FAIL, sessionQuality(session(sets, statuses + start + hot)).verdict("thermal.status"))
        // Already hot at the start: the camera never opened a file, which passes (Codex review).
        assertEquals(Verdict.PASS, sessionQuality(session(sets, statuses + hot)).verdict("thermal.status"))
        assertEquals(Verdict.NOT_MEASURED, sessionQuality(session(sets)).verdict("thermal.status"))
        // Reaching shutdown fails even with video stopped.
        val shutdown = RecorderEvent(600_000_000, EventKinds.THERMAL_STATUS, "status=6 headroom=1.3")
        val worst = sessionQuality(session(sets, statuses + hot + stop + shutdown))
        assertEquals(6.0, worst.value("thermal.max"))
        assertEquals(Verdict.FAIL, worst.verdict("thermal.status"))
    }

    @Test
    fun `with the phone temperature ignored severe heat without a step-down passes and shutdown fails`() {
        // FBBD-231: Arek's heat stress test, the camera keeps its mode however hot the phone gets.
        val statuses = listOf(1_000_000L to 0, 100_000_000L to 2, 300_000_000L to 4).map { (t, s) ->
            RecorderEvent(t, EventKinds.THERMAL_STATUS, thermalText(s, 0.9f, ignored = true))
        }
        val sets = stream(0, 1_000_000)
        val r = sessionQuality(session(sets, statuses))
        assertEquals(4.0, r.value("thermal.max"))
        assertEquals(Verdict.PASS, r.verdict("thermal.status"))
        assertEquals(0.0, r.value("thermal.step_downs"))
        val shutdown = RecorderEvent(400_000_000, EventKinds.THERMAL_STATUS, thermalText(6, 1.2f, ignored = true))
        assertEquals(Verdict.FAIL, sessionQuality(session(sets, statuses + shutdown)).verdict("thermal.status"))
    }

    @Test
    fun `each channel is listed with its provenance, rate, longest gap and accuracy`() {
        val hacc = ChannelDef(101, "gnss_hacc", "m", "racebox", 2)
        val speed = GnssChannels.SPEED_KMH.copy(provenance = Provenance.CALCULATED)
        val channels = listOf(GnssChannels.UTC_MS, GnssChannels.LAT, GnssChannels.LON, speed, hacc)
        val sets = stream(0, 10_000_000).map { it.copy(values = it.values + ChannelValue(hacc.id, 0.5)) }
        val origin = ImportOrigin("GPX", "a.gpx", "GPSLogger 131", 25.0)
        val data = session(sets).let {
            it.copy(info = it.info.copy(imported = origin), channels = channels.associateBy { c -> c.id })
        }
        val report = sessionQuality(data)
        val section = report.sections.single { it.title == "Channels" }
        assertEquals(channels.map { it.name }.sorted(), section.metrics.map { it.label }.sorted())
        val lat = report.metric("channel.${GnssChannels.LAT.id}")
        assertEquals(Verdict.INFO, lat.verdict)
        assertEquals(Basis.MEASURED, lat.basis)
        assertEquals(25.0, lat.value!!, 1e-9)
        assertTrue("MEASURED_GNSS_RECEIVER" in lat.how && "longest gap 0.04 s" in lat.how, lat.how)
        assertTrue("median horizontal accuracy 0.50 m" in lat.how, lat.how)
        val calculated = report.metric("channel.${speed.id}")
        assertEquals(Basis.DERIVED, calculated.basis)
        assertTrue(calculated.how.startsWith("Calculated (CALCULATED)"), calculated.how)
        assertTrue("accuracy" !in calculated.how)
        assertTrue(section.notes.single().text.contains("GPX file a.gpx, made by GPSLogger 131, 25.0 Hz"))
    }

    @Test
    fun `the report reads back from its JSON`() {
        val r = sessionQuality(session(stream(0, 10_000_000)), computedAtUtcMillis = 42)
        assertEquals(r, QualityReport.fromJson(r.toJson()))
        assertNull(QualityReport.fromJson("{\"format\": 0}"))
        assertNull(QualityReport.fromJson("not json"))
    }
}
