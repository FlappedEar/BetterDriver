@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Each said cue carries the length the coach expected at the set speech rate (FBBD-406), logged and read back. */
class SpeechLengthLogTest {
    private val sim = SimCircuit()
    private val track = sim.track()
    private val steady = SimCircuit.Pace()
    private val early2 = SimCircuit.Pace(earlierM = listOf(0.0, 15.0, 0.0, 0.0))

    private fun firstCue(rate: Float): Cue {
        val laps = tracedLaps(track, sim.drive(List(3) { steady }).fixes.asSequence())
        val reference = assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
        val engine = CueEngine(track, CoachSettings(Verbosity.Full, speech = Speech(rate = rate)), reference)
        return sim.drive(List(5) { early2 }).fixes.asSequence()
            .flatMap { engine.update(it).cues }.first { it.kind == CueKind.Corner }
    }

    @Test
    fun `the expected length follows the speech rate and is logged with the cue`() {
        val normal = firstCue(1f)
        val fast = firstCue(1.5f)
        val book = PhraseBook.of(CoachLanguage.English)
        val words = book.durationMicros(normal.text)
        assertEquals(words, normal.sayMicros)
        assertEquals((book.durationMicros(fast.text) / 1.5f).toLong(), fast.sayMicros)
        val text = CoachEvents.cueText(normal)
        assertTrue(" say_ms=${words / 1_000} text=" in text, text)
        assertEquals(words / 1_000 * 1_000, CoachEvents.parseCue(0, text)?.sayMicros)
    }
}
