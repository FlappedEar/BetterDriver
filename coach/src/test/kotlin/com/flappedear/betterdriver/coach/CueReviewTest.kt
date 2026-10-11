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
    fun `a restarted coach numbering cues from 1 again keeps each result with its own cue`() {
        val once = coachedEvents()
        val later = 600_000_000L
        // The restarted coach said the same cues again, but the session stopped before any pass after them.
        val again = once.filter { it.kind == CoachEvents.CUE }.map { it.copy(tMicros = it.tMicros + later) }
        val review = CueReview.of(once + again)
        assertEquals(2 * again.size, review.cues.size)
        val (first, second) = review.cues.partition { it.cue.tMicros < later }
        assertEquals(once.count { it.kind == CoachEvents.RESULT }, first.count { it.result != null })
        assertTrue(second.all { it.result == null }, "no result of the first run goes to a later cue")
    }

    @Test
    fun `no coach events is an empty review`() {
        val review = CueReview.of(
            listOf(
                RecorderEvent(0, CoachEvents.PROBLEM, "late seq=1 text=x"),
                RecorderEvent(1, CoachEvents.PROBLEM, "error java.lang.IllegalStateException"),
            ),
        )
        assertEquals("No cues", review.summary)
        assertEquals(1, review.problems, "an error is not an unsaid cue")
    }

    @Test
    fun `the result of a cue that was not said goes to no earlier cue`() {
        val once = coachedEvents()
        val cue = once.first { it.kind == CoachEvents.CUE && "kind=Corner" in it.text }
        val result = once.first { it.kind == CoachEvents.RESULT }
        val later = 600_000_000L
        // After a restart the same number was not said, and its pass was still judged.
        val events = listOf(
            cue,
            RecorderEvent(cue.tMicros + later, CoachEvents.PROBLEM, "no voice ${cue.text}"),
            result.copy(tMicros = result.tMicros + later),
        )
        val review = CueReview.of(events)
        assertEquals(1, review.cues.size)
        assertNull(review.cues.single().result)
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
