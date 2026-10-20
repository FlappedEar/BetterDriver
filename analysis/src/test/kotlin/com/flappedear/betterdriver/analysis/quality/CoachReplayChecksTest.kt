package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.RecorderEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CoachReplayChecksTest {
    private fun pitOut(vararg quiet: Pair<Int, String>): QualityMetric = coachReplayChecks(
        quiet.mapIndexed { i, (lap, reason) ->
            RecorderEvent(i * 1_000_000L, "coach_quiet", "lap=$lap reason=$reason")
        },
        "pl",
    ).single { it.id == "coach.pit_out" }

    // The lap counter moves on at the line before the coach logs pit_lane, so pit_lane carries the out-lap.
    @Test
    fun `the out-lap logged as pit lane and then cooling down fails`() {
        assertEquals(Verdict.FAIL, pitOut(5 to "pit_lane", 5 to "cooling_down").verdict)
    }

    @Test
    fun `cooling down on the lap after the out-lap passes`() {
        assertEquals(Verdict.PASS, pitOut(5 to "pit_lane", 6 to "cooling_down").verdict)
    }

    @Test
    fun `no pit lane is not measured`() {
        assertEquals(Verdict.NOT_MEASURED, pitOut(5 to "cooling_down").verdict)
    }

    private fun confidence(vararg cues: String): QualityMetric = coachReplayChecks(
        cues.mapIndexed { i, fields ->
            RecorderEvent(i * 1_000_000L, "coach_cue", "seq=$i lap=5 kind=Corner $fields text=x")
        },
        "pl",
    ).single { it.id == "coach.confidence" }

    @Test
    fun `numbers at High and words at Medium pass`() {
        assertEquals(
            Verdict.PASS,
            confidence(
                "advice=brake_later:10:at=100 conf=HIGH",
                "advice=brake_later:10:vague:at=100 conf=MEDIUM",
            ).verdict,
        )
    }

    @Test
    fun `numbers at Medium fail`() {
        assertEquals(Verdict.FAIL, confidence("advice=brake_later:10:at=100 conf=MEDIUM").verdict)
    }

    @Test
    fun `a cue said at Low fails`() {
        assertEquals(Verdict.FAIL, confidence("advice=brake_later:10:vague:at=100 conf=LOW").verdict)
    }

    @Test
    fun `cues logged without a confidence are not measured`() {
        assertEquals(Verdict.NOT_MEASURED, confidence("advice=brake_later:10").verdict)
    }
}
