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

    /**
     * Lap 3 of [lapMillis] went quiet cooling down, and lap 4's advice was measured from its turn 2;
     * [resumed] when the coach came back on lap 3 (FBBD-254).
     */
    private fun adviceFromLap3(
        resumed: Boolean,
        lapMillis: List<Int> = listOf(100_000, 100_000, 120_000, 100_000, 100_000),
    ): QualityMetric {
        val events = mutableListOf<RecorderEvent>()
        lapMillis.forEachIndexed { i, ms ->
            events += RecorderEvent(i * 200_000_000L, "lap_line", "lap=${i + 1} kind=Timed time_ms=$ms")
        }
        events += RecorderEvent(450_000_000L, "coach_quiet", "lap=3 reason=cooling_down")
        if (resumed) events += RecorderEvent(470_000_000L, "coach_resumed", "lap=3 after=cooling_down")
        events += RecorderEvent(
            650_000_000L,
            "coach_cue",
            "seq=1 lap=4 kind=Corner corner=2 before=3,100.0,120.0,90.0,60.0,5000000 lang=pl " +
                "text=Dwójka, hamuj później",
        )
        return coachReplayChecks(events, "pl").single { it.id == "coach.advice_from_cool_down" }
    }

    @Test
    fun `advice from a slow lap the coach stayed quiet on fails`() {
        assertEquals(Verdict.FAIL, adviceFromLap3(resumed = false).verdict)
    }

    @Test
    fun `advice from a lap the coach came back on passes`() {
        assertEquals(Verdict.PASS, adviceFromLap3(resumed = true).verdict)
    }

    // As at Jastrząb on 29 Aug 2026: 145 s laps in the morning, 110 s by the afternoon.
    @Test
    fun `a lap at the pace of the laps around it is not a cool-down lap however fast the day got`() {
        val day = listOf(150_000, 150_000, 150_000, 150_000, 110_000, 110_000, 110_000, 110_000, 110_000)
        assertEquals(Verdict.PASS, adviceFromLap3(resumed = false, lapMillis = day).verdict)
    }
}
