@file:Suppress("MagicNumber") // Session lengths, delays and limits of the drive-log checks.

package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.coach.CoachSession
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.toCoachFix
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.log.CheckLog
import com.flappedear.betterdriver.recorder.log.LogLine
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.demo.DemoCircuit
import com.flappedear.betterdriver.timing.live.LiveTiming
import com.flappedear.betterdriver.timing.live.lapLineText
import com.flappedear.betterdriver.timing.live.toFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A session on the Demo track receiver (FBBD-233) as the app runs it: the demo circuit's fixes through
 * live lap timing (each lap logged at its line with the predicted time shown before it) and the live
 * coach, its cues voiced on a headset. The session's validation report must then measure and pass what
 * a drive at a saved track measures: laps (FBBD-12, FBBD-61) and cues (FBBD-27, FBBD-49), and mark the
 * made-up positions as no receiver evidence.
 */
class DemoTrackReplayTest {
    private val track = DemoCircuit.track
    private val start = 1_790_000_000_000L

    /** Sound starts this long after the coach asks for a cue: live text-to-speech on a phone. */
    private val voiceMicros = 250_000L

    /** The headset's own delay, as the app measures it at the session's start (FBBD-49). */
    private val headsetMillis = 150

    /** [minutes] on the demo track, with what timing and the coach log, as the app records it. */
    private fun session(minutes: Int): SessionData {
        val gnss = DemoCircuit(start)
        val sets = (0L until minutes * 60L * gnss.rateHz).map { n ->
            SampleSet(n * 1_000_000L / gnss.rateHz, gnss.fix(n))
        }
        val events = mutableListOf<RecorderEvent>()
        val timing = LiveTiming(track)
        var predicted: Long? = null
        val coach = CoachSession(CoachSettings(audioDelayMillis = headsetMillis), track, reference = null)
        for (set in sets) {
            val fix = checkNotNull(set.toFix())
            val before = timing.book.count
            val state = timing.update(fix)
            timing.book.laps.drop(before).forEach {
                events += RecorderEvent(set.tMicros, EventKinds.LAP_LINE, lapLineText(it, predicted))
            }
            predicted = state.predictedMillis
            coach.onFix(checkNotNull(set.toCoachFix()), set.tMicros) { true }.events.forEach { e ->
                events += RecorderEvent(e.tMicros, e.kind, e.text)
                if (e.kind == CoachEvents.CUE) {
                    val seq = e.text.substringAfter("seq=").substringBefore(' ').toInt()
                    val sound = e.tMicros + voiceMicros
                    val text = CoachEvents.voiceText(
                        seq, "live", e.tMicros, "headset", readyMicros = sound - 20_000,
                        outDelayMillis = headsetMillis, outDelaySource = "measured",
                    )
                    events += RecorderEvent(sound, EventKinds.COACH_VOICE, text)
                }
            }
        }
        val end = sets.last().tMicros
        val info = SessionInfo(start, devices = listOf(Provenance.DemoDevice))
        return SessionData(
            "demo", info, SessionState.FINISHED, emptyMap(), sets, events.sortedBy { it.tMicros },
            Record.End(end, "user"), truncatedBytes = 0, skippedBytes = 0,
        )
    }

    private fun QualityReport.metric(id: String) = sections.flatMap { it.metrics }.single { it.id == id }

    @Test
    fun `a demo track session passes the lap and cue checks a saved track needs`() {
        val report = sessionQuality(session(15), track)
        val laps = report.metric("timing.laps")
        assertTrue(laps.value!! >= 10, "timed laps ${laps.value}")
        listOf("timing.suspect", "timing.predicted", "coach.margin", "coach.in_zone", "coach.headset").forEach {
            val m = report.metric(it)
            assertEquals(Verdict.PASS, m.verdict, "$it: ${m.value} ${m.unit}, ${m.how}")
        }
        val cues = report.metric("coach.cues")
        assertTrue(cues.value!! >= 5, "cues said ${cues.value}")
        // The live voice's delay from trigger to sound is judged too.
        assertEquals(Verdict.PASS, report.metric("coach.delay_live").verdict)
    }

    @Test
    fun `corner cues start within the first 6 minutes and keep coming`() {
        val corners = session(15).events.filter { it.kind == CoachEvents.CUE && "kind=Corner" in it.text }
        assertTrue(corners.size >= 6, "${corners.size} corner cues in 15 min")
        val first = corners.first().tMicros / 1_000_000
        assertTrue(first < 6 * 60, "first corner cue at $first s")
    }

    @Test
    fun `its made-up positions are never receiver evidence`() {
        val report = sessionQuality(session(2), track)
        val gnss = report.sections.single { it.title == "GNSS" }
        assertEquals(listOf(Verdict.NOT_MEASURED), gnss.metrics.map { it.verdict })
        assertTrue(gnss.metrics.single().how.startsWith("demo receiver"))
    }

    @Test
    fun `the drive log report marks a demo session the same way`() {
        val text = "start utc_ms=$start source=Demo_track"
        val line = LogLine(start, null, "recorder", 1, "main", 'I', CheckLog.SESSION, "demo", text)
        val report = driveChecks(listOf(line)).single()
        val gnss = report.sections.single { it.title == "GNSS" }
        assertEquals(Verdict.NOT_MEASURED, gnss.metrics.single().verdict)
    }

    @Test
    fun `a real receiver whose name starts with Demo is still judged`() {
        val text = "start utc_ms=$start source=Demo_box_2"
        val line = LogLine(start, null, "recorder", 1, "main", 'I', CheckLog.SESSION, "real", text)
        val gnss = driveChecks(listOf(line)).single().sections.single { it.title == "GNSS" }
        assertTrue(gnss.metrics.none { it.how.startsWith("demo receiver") })
    }
}
