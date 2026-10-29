package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each hardware check of the session report (FBBD-183) on its own, with synthetic sessions. */
class HardwareChecksTest {
    private val start = 1_760_000_000_000L

    /** 25 Hz fixes from [from] to [to] µs at [kmh], except where [gap] says none came. */
    private fun stream(from: Long, to: Long, kmh: (Long) -> Double = { 100.0 }, gap: LongRange? = null) =
        (from..to step 40_000L).filter { gap == null || it !in gap }.map { t ->
            SampleSet(
                t,
                listOf(
                    ChannelValue(GnssChannels.UTC_MS.id, (start + t / 1_000).toDouble()),
                    ChannelValue(GnssChannels.LAT.id, 50.0 + t * 1e-12),
                    ChannelValue(GnssChannels.LON.id, 19.0),
                    ChannelValue(GnssChannels.SPEED_KMH.id, kmh(t)),
                ),
            )
        }

    private fun report(sets: List<SampleSet>, events: List<RecorderEvent>, clips: List<QualityClip> = emptyList()) =
        sessionQuality(
            SessionData(
                "s", SessionInfo(start), SessionState.FINISHED, emptyMap(), sets, events.sortedBy { it.tMicros },
                Record.End(sets.last().tMicros, "user"), truncatedBytes = 0, skippedBytes = 0,
            ),
            clips = clips,
        )

    private fun QualityReport.metric(id: String) = metrics.single { it.id == id }

    @Test
    fun `cues that overlap, share a straight or lack the headset's delay are caught`() {
        val sets = stream(0, 60_000_000)
        fun cue(seq: Int, at: Long) = listOf(
            RecorderEvent(at, "coach_cue", "seq=$seq lap=1 kind=Corner corner=1 zone_m=300.0 text=Turn 1"),
            RecorderEvent(
                at + 200_000, EventKinds.COACH_VOICE, "seq=$seq path=clip asked_us=$at route=headset focus=refused",
            ),
        )
        val events = cue(1, 10_000_000) + cue(2, 11_000_000) + listOf(
            // Cue 1 was still playing when cue 2 began, and was cut off.
            RecorderEvent(11_200_000, EventKinds.COACH_VOICE_END, "seq=1 end=stopped"),
            RecorderEvent(12_500_000, EventKinds.COACH_VOICE_END, "seq=2 end=done"),
        )
        val r = report(sets, events)
        assertEquals(Verdict.FAIL, r.metric("coach.overlap").verdict)
        assertEquals(1.0, r.metric("coach.per_straight").value)
        assertEquals(Verdict.FAIL, r.metric("coach.per_straight").verdict)
        assertEquals(Verdict.NOT_MEASURED, r.metric("coach.total_clip").verdict)
        assertTrue("headset's own delay is not known" in r.metric("coach.total_clip").how)
        assertEquals(0.0, r.metric("coach.focus").value)
        assertEquals(100.0, r.metric("coach.clips").value)
    }

    /**
     * Arek's 2026-10-20 07:18 drive (FBBD-255): the delay was measured on the phone speaker at the start,
     * the headset connected later, and its cues went out with only the set delay. Once the new output is
     * measured, the check passes.
     */
    @Test
    fun `cues to an output whose delay was never measured fail`() {
        val sets = stream(0, 60_000_000)
        fun cue(seq: Int, at: Long, delay: String) = listOf(
            RecorderEvent(at, "coach_cue", "seq=$seq lap=1 kind=Corner corner=1 zone_m=300.0 text=Turn 1"),
            RecorderEvent(
                at + 20_000, EventKinds.COACH_VOICE,
                "seq=$seq path=live asked_us=$at route=headset device=bt_sco focus=granted $delay",
            ),
        )
        val unmeasured = "out_delay_ms=none out_delay_src=set"
        val before = report(sets, cue(1, 10_000_000, unmeasured) + cue(2, 30_000_000, unmeasured))
        assertEquals(Verdict.FAIL, before.metric("coach.out_delay").verdict)
        assertEquals(0.0, before.metric("coach.out_delay").value)
        assertTrue("not measured on bt_sco" in before.metric("coach.out_delay").how)
        val measured = "out_delay_ms=250 out_delay_src=measured"
        val after = report(sets, cue(1, 10_000_000, measured) + cue(2, 30_000_000, measured))
        assertEquals(Verdict.PASS, after.metric("coach.out_delay").verdict)
        // Timed for the set delay when asked; the headset was measured before its sound started.
        val late = "$measured timed_src=set"
        val raced = report(sets, cue(1, 10_000_000, late) + cue(2, 30_000_000, "$measured timed_src=measured"))
        assertEquals(50.0, raced.metric("coach.out_delay").value)
        assertEquals(Verdict.FAIL, raced.metric("coach.out_delay").verdict)
    }

    @Test
    fun `praise then the straight's corner cue is not an extra cue`() {
        // Arek's 2026-10-19 evening replay: "Jedynka, to zadziałało", then the T2 cue on the same straight (UI-94 A).
        val sets = stream(0, 60_000_000)
        fun cue(seq: Int, at: Long, kind: String) = listOf(
            RecorderEvent(at, "coach_cue", "seq=$seq lap=7 kind=$kind corner=1 zone_m=300.0 text=Turn 1"),
            RecorderEvent(at + 150_000, EventKinds.COACH_VOICE, "seq=$seq path=live asked_us=$at route=speaker"),
        )
        val r = report(sets, cue(1, 10_000_000, "Praise") + cue(2, 12_000_000, "Corner"))
        assertEquals(0.0, r.metric("coach.per_straight").value)
        assertEquals(Verdict.PASS, r.metric("coach.per_straight").verdict)
        // Praise followed by anything but a corner cue on the same straight still counts.
        val lapTime = report(sets, cue(1, 10_000_000, "Praise") + cue(2, 12_000_000, "LapTime"))
        assertEquals(1.0, lapTime.metric("coach.per_straight").value)
    }

    @Test
    fun `a banner with fixes arriving is false, one after an outage is timed, the screen going off fails`() {
        val sets = stream(0, 120_000_000, gap = 50_000_000L..53_000_000L)
        val events = listOf(
            RecorderEvent(51_100_000, EventKinds.GNSS_BANNER, "state=shown"),
            RecorderEvent(53_500_000, EventKinds.GNSS_BANNER, "state=cleared"),
            RecorderEvent(80_000_000, EventKinds.GNSS_BANNER, "state=shown"),
            RecorderEvent(80_300_000, EventKinds.GNSS_BANNER, "state=cleared"),
            RecorderEvent(90_000_000, EventKinds.SCREEN, "state=off"),
            RecorderEvent(95_000_000, EventKinds.SCREEN, "state=on"),
            RecorderEvent(
                30_000_000,
                EventKinds.UI_FRAMES,
                "frames=100 janky=10 frozen=1 worst_ms=900 hist=50,20,10,5,5,5,1,1,1,1,1",
            ),
        )
        val r = report(sets, events)
        assertEquals(1.0, r.metric("ui.false_banners").value)
        assertEquals(1.14, r.metric("ui.banner_show").value!!, 1e-9)
        assertEquals(Verdict.PASS, r.metric("ui.banner_show").verdict)
        assertEquals(0.46, r.metric("ui.banner_clear").value!!, 1e-9)
        assertEquals(Verdict.FAIL, r.metric("ui.screen_off").verdict)
        assertEquals(Verdict.FAIL, r.metric("ui.jank").verdict)
        assertEquals(Verdict.FAIL, r.metric("ui.frozen").verdict)
        assertEquals(33.0, r.metric("ui.frame_p95").value)
    }

    @Test
    fun `the frame time p95 is judged after the start-up, which is shown on its own`() {
        // The CI replay drive of 2026-10-29 08:35 (FBBD-266): the first 60 s slow while the app warms up,
        // then steady. Together the p95 is 33 ms; after the first 90 s it is 12 ms.
        val stretches = listOf(
            33_569_640L to "frames=119 janky=18 frozen=0 worst_ms=280 hist=6,37,25,15,7,9,14,5,0,1,0",
            63_802_972L to "frames=120 janky=3 frozen=0 worst_ms=37 hist=50,37,12,7,5,6,3,0,0,0,0",
            93_919_637L to "frames=120 janky=0 frozen=0 worst_ms=18 hist=109,9,1,1,0,0,0,0,0,0,0",
            124_052_969L to "frames=120 janky=0 frozen=0 worst_ms=19 hist=101,14,2,3,0,0,0,0,0,0,0",
            154_152_968L to "frames=120 janky=0 frozen=0 worst_ms=13 hist=117,2,1,0,0,0,0,0,0,0,0",
            184_252_967L to "frames=120 janky=0 frozen=0 worst_ms=13 hist=116,3,1,0,0,0,0,0,0,0,0",
        ).map { (t, text) -> RecorderEvent(t, EventKinds.UI_FRAMES, text) }
        val r = report(stream(0, 190_000_000), stretches)
        assertEquals(12.0, r.metric("ui.frame_p95").value)
        assertEquals(Verdict.PASS, r.metric("ui.frame_p95").verdict)
        assertTrue("480 frames drawn after the first 90 s" in r.metric("ui.frame_p95").how)
        assertEquals(50.0, r.metric("ui.frame_p95_start").value)
        assertEquals(Verdict.INFO, r.metric("ui.frame_p95_start").verdict)
        // Jank counts every frame.
        assertEquals(21 * 100.0 / 719, r.metric("ui.jank").value!!, 1e-9)
    }

    @Test
    fun `a banner that never shows for an outage, or never clears after it, fails`() {
        val frames = RecorderEvent(1_000_000, EventKinds.UI_FRAMES, "frames=100 janky=0 frozen=0 worst_ms=10 hist=100")
        // 5 s without fixes and the screen, drawing all along, never showed the banner.
        val silent = report(stream(0, 60_000_000, gap = 20_000_000L..25_000_000L), listOf(frames))
        assertEquals(Verdict.FAIL, silent.metric("ui.banner_show").verdict)
        assertTrue("1 never reacted to" in silent.metric("ui.banner_show").how)
        // Shown in time, never cleared once the fixes came back.
        val stuck = report(
            stream(0, 60_000_000, gap = 20_000_000L..25_000_000L),
            listOf(frames, RecorderEvent(21_000_000, EventKinds.GNSS_BANNER, "state=shown")),
        )
        assertEquals(Verdict.PASS, stuck.metric("ui.banner_show").verdict)
        assertEquals(Verdict.FAIL, stuck.metric("ui.banner_clear").verdict)
        // An outage shorter than the banner's limit need not show it.
        val short = report(stream(0, 60_000_000, gap = 20_000_000L..21_200_000L), listOf(frames))
        assertEquals(Verdict.NOT_MEASURED, short.metric("ui.banner_show").verdict)
    }

    @Test
    fun `a banner for a gap under 1 s that reached the phone over 1 s late is not false`() {
        // Arek's 2026-10-21 12:52 drive (FBBD-282): the receiver's times skip 0.96 s, but the link
        // stalled ~1.3 s, so the banner showed with the last fix 1.02 s old and cleared on the next.
        val frames = RecorderEvent(1_000_000, EventKinds.UI_FRAMES, "frames=100 janky=0 frozen=0 worst_ms=10 hist=100")
        val sets = stream(0, 60_000_000, gap = 20_040_000L..20_960_000L)
        val late = listOf(
            frames,
            RecorderEvent(21_100_000, EventKinds.GNSS_BANNER, "state=shown fix_age_us=1024080"),
            RecorderEvent(21_250_000, EventKinds.GNSS_BANNER, "state=cleared fix_age_us=42983"),
        )
        val r = report(sets, late)
        assertEquals(0.0, r.metric("ui.false_banners").value)
        assertEquals(Verdict.PASS, r.metric("ui.false_banners").verdict)
        assertTrue("1 for a shorter gap" in r.metric("ui.false_banners").how)
        // The same banner with fixes arriving all along is still false, and one with a short fix age too.
        assertEquals(1.0, report(stream(0, 60_000_000), late).metric("ui.false_banners").value)
        val young = listOf(frames, RecorderEvent(21_100_000, EventKinds.GNSS_BANNER, "state=shown fix_age_us=900000"))
        assertEquals(1.0, report(sets, young).metric("ui.false_banners").value)
    }

    @Test
    fun `a GoPro that started late, and a clip placed by its clock, fail`() {
        val sets = stream(0, 60_000_000)
        val events = listOf(
            RecorderEvent(4_500_000, EventKinds.GOPRO, "camera=gopro-1 action=start result=ok"),
            RecorderEvent(61_000_000, EventKinds.GOPRO, "camera=gopro-1 action=stop result=ok"),
        )
        val clips = listOf(QualityClip("gopro-1", "GX010001.MP4", 4_000_000, 60_000_000, "CAMERA_CLOCK", 5_000_000))
        val r = report(sets, events, clips)
        assertEquals(Verdict.FAIL, r.metric("gopro.gopro-1.start").verdict)
        assertEquals(Verdict.PASS, r.metric("gopro.gopro-1.stop").verdict)
        assertEquals(1.0, r.metric("gopro.gopro-1.stop").value!!, 1e-9)
        assertEquals(Verdict.FAIL, r.metric("video.import").verdict)
    }

    @Test
    fun `battery reports are never judged, and a short drive is no soak`() {
        val sets = stream(0, 120_000_000)
        val events = listOf(0L, 30_000_000L, 100_000_000L).map {
            val celsius = 35 + it / 10_000_000
            val text = "battery_c=$celsius battery_pct=80 plugged=none thermal=0 headroom=n/a"
            RecorderEvent(it, EventKinds.DEVICE, text)
        }
        val r = report(sets, events)
        assertTrue(r.metrics.none { it.id.startsWith("thermal.") })
        val soak = r.metrics.filter { it.id.startsWith("soak.") && it.id != "soak.minutes" }
        assertTrue(soak.all { it.verdict == Verdict.NOT_MEASURED })
    }

    @Test
    fun `the report goes in the log as a summary and one line per check`() {
        val r = report(stream(0, 60_000_000), emptyList())
        val lines = r.logLines()
        assertEquals(
            "summary pass=${r.count(Verdict.PASS)} fail=${r.count(Verdict.FAIL)} " +
                "not_measured=${r.count(Verdict.NOT_MEASURED)}",
            lines.first(),
        )
        assertEquals(r.metrics.size + 1, lines.size)
        val rate = lines.single { it.startsWith("gnss.rate ") }
        assertTrue(Regex("""gnss\.rate PASS value=25(\.0+)? unit=Hz keys=FBBD-\d+.*""").matches(rate), rate)
    }

    @Test
    fun `issue keys come from the criterion`() {
        assertEquals(listOf("FBBD-21", "FBBD-82"), issueKeys("<= 50 ms at 10 min (FBBD-21, FBBD-82, FBBD-21)"))
        assertEquals(emptyList(), issueKeys(null))
    }
}
