@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.RecorderEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The phrase length estimate against the logged sound (FBBD-406). */
class SpeechLengthTest {
    private fun cue(seq: Int, t: Long, sayMs: Long?, lang: String = "en") =
        LoggedCue(t, seq, null, null, "Corner", sayMs?.let { it * 1_000 }, lang)

    private fun voice(seq: Int, t: Long) = Voiced(t, seq, "live", "headset")

    private fun end(seq: Int, t: Long, how: String = "done") = RecorderEvent(t, "coach_voice_end", "seq=$seq end=$how")

    @Test
    fun `the error is measured per language from cues played to their end`() {
        val played = listOf(
            // English 2.0 s expected, heard 2.1 s; Polish 2.0 s expected, heard 2.5 s.
            cue(1, 0, 2_000) to voice(1, 100_000),
            cue(2, 10_000_000, 2_000, "pl") to voice(2, 10_100_000),
            // Cut off: its length says nothing of the estimate.
            cue(3, 20_000_000, 2_000) to voice(3, 20_100_000),
            // Logged before FBBD-406: no estimate.
            cue(4, 30_000_000, null) to voice(4, 30_100_000),
        )
        val ends = listOf(
            end(1, 2_200_000), end(2, 12_600_000), end(3, 20_300_000, "stopped"), end(4, 32_000_000),
        )
        val m = speechLength(played, ends)
        assertEquals(Verdict.INFO, m.verdict)
        assertEquals(500.0, m.value)
        assertTrue("en: p95 100 ms (ok), mean +100 ms, 1 cue(s)" in m.how, m.how)
        assertTrue("pl: p95 500 ms (over), mean +500 ms, 1 cue(s)" in m.how, m.how)
    }

    @Test
    fun `without an estimate or an end it is not measured`() {
        val m = speechLength(listOf(cue(1, 0, null) to voice(1, 100_000)), listOf(end(1, 2_000_000)))
        assertEquals(Verdict.NOT_MEASURED, m.verdict)
    }
}
