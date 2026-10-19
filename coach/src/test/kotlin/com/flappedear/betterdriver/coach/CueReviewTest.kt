@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CueReviewTest {
    @TempDir
    lateinit var tmp: File

    private val sim = SimCircuit()
    private val track = sim.track()

    /** A coached outing: 3 laps learned, then turn 2 braked 15 m early until the cue, then at the best point. */
    private fun coachedEvents(language: CoachLanguage = CoachLanguage.English): List<RecorderEvent> {
        val laps = tracedLaps(track, sim.drive(List(3) { SimCircuit.Pace() }).fixes.asSequence())
        val reference = assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
        val session = CoachSession(CoachSettings(Verbosity.Full, language = language), track, reference)
        val early2 = SimCircuit.Pace(earlierM = listOf(0.0, 15.0, 0.0, 0.0))
        val paces = List(3) { early2 } + List(2) { SimCircuit.Pace() }
        return sim.drive(paces).fixes.flatMap { f -> session.onFix(f, f.tMicros) { true }.events }
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
        assertTrue(outcome.startsWith("Braked at ") && "later than before" in outcome && "gained" in outcome, outcome)
        // Lap-time cues have no outcome.
        assertNull(review.cues.first { it.cue.kind == CueKind.LapTime }.outcome())
        assertEquals(0, review.problems)
        assertTrue(review.summary.matches(Regex("\\d+ cues · \\d+ of \\d+ corners better")), review.summary)
    }

    @Test
    fun `cues said in Polish are reviewed in Polish`() {
        val events = coachedEvents(CoachLanguage.Polish)
        assertTrue(events.filter { it.kind == CoachEvents.CUE }.all { " lang=pl " in it.text })
        val review = CueReview.of(events)
        assertTrue(review.cues.all { it.cue.language == CoachLanguage.Polish })
        val last = review.cues.last { it.cue.kind == CueKind.Corner }
        val outcome = assertNotNull(last.outcome())
        assertTrue(outcome.startsWith("Hamowanie na ") && "później niż poprzednio" in outcome, outcome)
        assertTrue(Regex("""zysk \d+,\d\d s""").containsMatchIn(outcome), outcome)
        assertEquals("Pewność wysoka", last.cue.confidenceText())
        assertTrue(review.summary.matches(Regex("""\d+ wskazów\w+ · \d+ z \d+ zakrętów lepiej""")), review.summary)
        // English cues are logged as before, without a language.
        assertTrue(coachedEvents().filter { it.kind == CoachEvents.CUE }.none { "lang=" in it.text })
    }

    @Test
    fun `the Polish review words`() {
        val pl = PolishReview
        assertEquals("1 wskazówka", pl.cues(1))
        assertEquals("3 wskazówki", pl.cues(3))
        assertEquals("12 wskazówek", pl.cues(12))
        assertEquals("22 wskazówki", pl.cues(22))
        assertEquals("Hamowanie 9 m później", pl.braked(true, 9))
        assertEquals("Hamowanie wcześniej", pl.braked(false, null))
        assertEquals("4 m przed najlepszym", pl.toBestPoint(true, 4))
        assertEquals("Prędkość minimalna wyższa o 3 km/h", pl.minSpeed(true, 3))
        assertEquals("Prędkość minimalna niższa", pl.minSpeed(false, null))
        assertEquals("strata 0,05 s", pl.time(false, 0.05))
        assertEquals("zysk czasu", pl.time(true, null))
        assertEquals("0,21 s szybciej niż przed wskazówką", pl.faster(0.21, worked = true))
        assertEquals("Pewność średnia", pl.confidence(Confidence.MEDIUM))
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

    @Test
    fun `the review is read from the session's events, and a missing or damaged session has no cues`() {
        val store = SessionStore(File(tmp, "sessions"))
        val events = coachedEvents()
        val coached = Recorder(store).start(SessionInfo(startUtcMillis = 1_000L, channels = GnssChannels.ALL))
        events.forEach { coached.eventAt(it.tMicros, it.kind, it.text) }
        coached.stop()
        assertEquals(CueReview.of(events), CueReview.read(store, coached.id))
        assertEquals(CueReview(emptyList(), 0), CueReview.read(store, "gone"))
        // Its metadata gone: unreadable.
        assertTrue(File(store.dir(coached.id), "meta.properties").delete())
        assertTrue(CueReview.read(store, coached.id).cues.isEmpty())
    }

    /** Turn 2 cued "brake 10 m later" at [cueConf], and the pass after it at [afterConf] (null: logged without one). */
    private fun reviewed(cueConf: Confidence, afterConf: Confidence?): ReviewedCue {
        val before = CornerPass(2, 4, 120.0, 160.0, 140.0, 80.0, 6_500_000)
        val target = CornerPass(2, 2, 135.0, 160.0, 141.0, 82.0, 6_200_000)
        val after = CornerPass(2, 5, 129.0, 160.0, 140.0, 81.0, 6_380_000)
        val advice = Advice.BrakeLater(10, precise = cueConf.precise)
        val cue = Cue(
            7, 0, 5, CueKind.Corner, "Turn 2, brake 10 metres later", 2, advice, before, target,
            confidence = cueConf,
        )
        val logged = CoachEvents.resultText(CueResult(1, cue, after, true, 120_000, afterConf ?: Confidence.HIGH))
        val text = if (afterConf == null) logged.replace(Regex(" conf=\\w+"), "") else logged
        val events = listOf(
            RecorderEvent(0, CoachEvents.CUE, CoachEvents.cueText(cue)),
            RecorderEvent(1, CoachEvents.RESULT, text),
        )
        return CueReview.of(events).cues.single()
    }

    @Test
    fun `the review gives numbers only when the cue and the pass after it are both High`() {
        val high = assertNotNull(reviewed(Confidence.HIGH, Confidence.HIGH).outcome())
        assertEquals("Braked 9 m later, 6 m short of best · 0.12 s gained", high)
        assertEquals(Confidence.HIGH, reviewed(Confidence.HIGH, Confidence.HIGH).result?.confidence)
        for ((cueConf, afterConf) in listOf(
            Confidence.HIGH to Confidence.MEDIUM,
            Confidence.MEDIUM to Confidence.HIGH,
            Confidence.HIGH to null, // A result logged before FBBD-177 is not taken as High.
        )) {
            val item = reviewed(cueConf, afterConf)
            val outcome = assertNotNull(item.outcome())
            assertEquals("Braked later, short of best · time gained", outcome, "$cueConf / $afterConf")
            assertFalse(outcome.any { it.isDigit() }, outcome)
        }
        assertEquals(Confidence.MEDIUM, reviewed(Confidence.HIGH, null).result?.confidence)
    }

    @Test
    fun `a speed outcome without numbers keeps its words`() {
        val before = CornerPass(3, 4, 120.0, 160.0, 140.0, 80.0, 6_500_000)
        val target = CornerPass(3, 2, 120.0, 160.0, 141.0, 86.0, 6_200_000)
        val after = CornerPass(3, 5, 120.0, 160.0, 140.0, 83.0, 6_560_000)
        val cue = Cue(
            8, 0, 5, CueKind.Corner, "Turn 3, carry a little more speed", 3, Advice.CarrySpeed(6, precise = false),
            before, target, confidence = Confidence.MEDIUM,
        )
        val r = CueResult(1, cue, after, true, -60_000, Confidence.HIGH)
        val item = ReviewedCue(cue, CoachEvents.parseResult(CoachEvents.resultText(r)))
        assertEquals("Minimum speed higher, under best · time lost", item.outcome())
    }
}
