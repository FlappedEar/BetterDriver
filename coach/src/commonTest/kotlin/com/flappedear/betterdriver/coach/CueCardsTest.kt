@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.RecorderEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The coach review's words, common to Android and the iPhone (FBBD-99). */
class CueCardsTest {
    private val before = CornerPass(2, 4, 480.0, 560.0, 152.0, 70.0, 8_800_000)
    private val corner = Cue(
        5, 0, 5, CueKind.Corner, "Turn 2, brake at 65, 15 later", 2, Advice.BrakeLater(15, at = 65), before,
        before.copy(brakeAt = 495.0, minKmh = 71.0, timeMicros = 8_500_000),
    )

    private fun result(improved: Boolean) = CoachEvents.Result(
        5, before.copy(lap = 5, brakeAt = 491.0, timeMicros = 8_620_000), improved = improved,
        gainMicros = if (improved) 180_000 else -50_000, confidence = Confidence.HIGH,
    )

    @Test
    fun aCornerCueIsBetterOrNotYetOnceTheCornerWasDrivenAgain() {
        val better = ReviewedCue(corner, result(improved = true)).card()
        assertEquals("Lap 5 · Turn 2", better.place)
        assertEquals("Better", better.verdict)
        assertEquals(CueVerdictTone.Gain, better.tone)
        assertEquals("“Turn 2, brake at 65, 15 later”", better.quote)
        assertEquals("High confidence", better.confidence)
        val outcome = assertNotNull(better.outcome)
        assertTrue(outcome.startsWith("Braked at ") && "gained" in outcome, outcome)

        val notYet = ReviewedCue(corner, result(improved = false)).card()
        assertEquals("Not yet" to CueVerdictTone.Loss, notYet.verdict to notYet.tone)

        val waiting = ReviewedCue(corner, null).card()
        assertEquals("No pass after" to CueVerdictTone.Plain, waiting.verdict to waiting.tone)
        assertNull(waiting.outcome)
    }

    @Test
    fun aLapTimeCueHasNoTurnConfidenceOrOutcome() {
        val card = ReviewedCue(Cue(4, 0, 4, CueKind.LapTime, "1 32 point 4"), null).card()
        assertEquals("Lap 4", card.place)
        assertEquals("Lap time" to CueVerdictTone.Plain, card.verdict to card.tone)
        assertNull(card.confidence)
        assertNull(card.outcome)
    }

    @Test
    fun cuesNotSaidAreCountedInWords() {
        assertNull(CueReview(emptyList(), 0).notSaid)
        assertEquals("1 cue not said (too late or no voice)", CueReview(emptyList(), 1).notSaid)
        assertEquals("3 cues not said (too late or no voice)", CueReview(emptyList(), 3).notSaid)
    }

    @Test
    fun theSessionListCountsEveryCueEvent() {
        val events = listOf(
            RecorderEvent(1, CoachEvents.CUE, "seq=1"),
            RecorderEvent(2, CoachEvents.RESULT, "seq=1"),
            RecorderEvent(3, CoachEvents.CUE, "seq=2"),
            RecorderEvent(4, "track", "saved id=x"),
        )
        assertEquals(2, CueReview.count(events))
    }

    private data class Listed(val id: String, val micros: Long?)

    @Test
    fun cueCountsAreReadOnceAndAgainWhenASessionChanged() {
        val known = mutableMapOf("gone" to "5:2")
        val read = mutableListOf<String>()
        val cues = mapOf("a" to 3, "b" to 0)
        fun counts(list: List<Listed>) = rememberCueCounts(list, { it.id }, { it.micros }, known) {
            read += it.id
            cues.getValue(it.id)
        }

        val first = counts(listOf(Listed("a", 100), Listed("b", null)))
        assertEquals(mapOf("a" to 3), first.counts)
        assertTrue(first.changed)
        assertEquals(mapOf("a" to "100:3", "b" to "-1:0"), known)

        val again = counts(listOf(Listed("a", 100), Listed("b", null)))
        assertEquals(mapOf("a" to 3), again.counts)
        assertEquals(false, again.changed)
        assertEquals(listOf("a", "b"), read)

        // An imported session replaced "a": it is longer now, so it is read again.
        counts(listOf(Listed("a", 200)))
        assertEquals(listOf("a", "b", "a"), read)
        assertEquals(mapOf("a" to "200:3"), known)
    }

    @Test
    fun rememberedCountsReadBackFromTheirText() {
        val known = mapOf("20261029-101500" to "3600000000:12", "x" to "-1:0")
        assertEquals(known, CueCountsText.decode(CueCountsText.encode(known)))
        assertEquals(mapOf("a" to "1:2"), CueCountsText.decode("a=1:2\nno equals sign\n=3\n"))
    }

    @Test
    fun aReplayedDriveIsCoachedWithCornerCuesAndTheirPasses() {
        val sim = SimCircuit()
        val replay = CoachReplay(sim.track(), CoachSettings(Verbosity.Full))
        val early = SimCircuit.Pace(earlierM = listOf(0.0, 15.0, 0.0, 10.0))
        val paces = List(12) { if (it % 3 == 2) SimCircuit.Pace() else early }
        val events = sim.drive(paces).fixes.flatMap(replay::onFix)
        val review = CueReview.of(events)
        assertTrue(CueReview.count(events) > 0, "no cues: ${events.map { it.kind }.toSet()}")
        assertTrue(review.cues.any { it.cue.kind == CueKind.Corner }, review.cues.map { it.cue.kind }.toString())
        assertTrue(review.judged > 0, review.summary)
    }
}
