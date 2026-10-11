@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CueReviewTest {
    @TempDir
    lateinit var tmp: File

    private val sim = SimCircuit()
    private val track = sim.track()

    /** A coached outing: 3 laps learned, then turn 2 braked 15 m early until the cue, then at the best point. */
    private fun coachedEvents(): List<RecorderEvent> {
        val laps = tracedLaps(track, sim.drive(List(3) { SimCircuit.Pace() }).fixes.asSequence())
        val reference = assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
        val session = CoachSession(CoachSettings(Verbosity.Full), track, reference)
        val early2 = SimCircuit.Pace(earlierM = listOf(0.0, 15.0, 0.0, 0.0))
        val paces = List(3) { early2 } + List(2) { SimCircuit.Pace() }
        return sim.drive(paces).fixes.flatMap { f -> session.onFix(f, f.tMicros).events }
            .map { RecorderEvent(it.tMicros, it.kind, it.text) }
    }

    @Test
    fun `each corner cue is paired with the pass after it`() {
        val review = CueReview.of(coachedEvents() + RecorderEvent(0, "track", "saved id=x"))
        val corner = review.cues.filter { it.cue.kind == CueKind.Corner }
        assertTrue(corner.isNotEmpty())
        val last = corner.last()
        val result = assertNotNull(last.result)
        assertEquals(last.cue.seq, result.seq)
        assertTrue(result.improved, "turn 2 at the best point after the cue")
        val outcome = assertNotNull(last.outcome())
        assertTrue(outcome.startsWith("Braked 1") && "later" in outcome && "gained" in outcome, outcome)
        // Lap-time cues have no outcome.
        assertNull(review.cues.first { it.cue.kind == CueKind.LapTime }.outcome())
        assertEquals(0, review.problems)
        assertTrue(review.summary.matches(Regex("\\d+ cues · \\d+ of \\d+ corners better")), review.summary)
    }

    @Test
    fun `no coach events is an empty review`() {
        val review = CueReview.of(listOf(RecorderEvent(0, CoachEvents.PROBLEM, "late seq=1 text=x")))
        assertEquals("No cues", review.summary)
        assertEquals(1, review.problems)
    }

    @Test
    fun `cue counts are read once per session and cached`() {
        val store = SessionStore(File(tmp, "sessions"))
        val events = coachedEvents()
        val coached = Recorder(store).start(SessionInfo(startUtcMillis = 1_000L, channels = GnssChannels.ALL))
        events.forEach { coached.eventAt(it.tMicros, it.kind, it.text) }
        coached.stop()
        val plain = Recorder(store).start(SessionInfo(startUtcMillis = 2_000L, channels = GnssChannels.ALL))
        plain.stop()
        val index = File(tmp, "coach/cues.properties")
        val counts = CoachedSessions(store, index)
        val cues = events.count { it.kind == CoachEvents.CUE }
        assertEquals(mapOf(coached.id to cues), counts.counts())
        assertTrue(index.isFile)
        // From the cache: a damaged session would now read as 0, but it is not read again.
        assertEquals(mapOf(coached.id to cues), counts.counts())
    }
}
