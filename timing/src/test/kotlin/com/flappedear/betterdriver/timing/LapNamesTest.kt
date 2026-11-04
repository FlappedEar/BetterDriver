package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.LiveTiming
import com.flappedear.betterdriver.timing.live.LiveTimingState
import com.flappedear.betterdriver.timing.live.lapFileWord
import com.flappedear.betterdriver.timing.live.lapMapCaption
import com.flappedear.betterdriver.timing.live.lapMark
import com.flappedear.betterdriver.timing.live.lapName
import com.flappedear.betterdriver.timing.live.shownLapNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** FBBD-450: the out lap from session start is no lap 1; laps are counted from the line, as the analysis. */
class LapNamesTest {
    @Test
    fun `a circuit's laps count from the first full lap and the out lap has no number`() {
        assertEquals(0, shownLapNumber(1))
        assertEquals(1, shownLapNumber(2))
        assertEquals(17, shownLapNumber(18))
        assertEquals("OUT", lapMark(1, LapKind.Out))
        assertEquals("1", lapMark(2, LapKind.Timed))
        assertEquals("IN", lapMark(9, LapKind.In))
        // An out lap after a pit stop, and the lap still being driven when the session stopped.
        assertEquals("OUT", lapMark(6, LapKind.Out))
        assertEquals("9", lapMark(10, null))
        assertEquals("OUT LAP", lapMapCaption(1, LapKind.Out))
        assertEquals("IN LAP", lapMapCaption(9, LapKind.In))
        assertEquals("LAP 6", lapMapCaption(7, LapKind.Timed))
        assertEquals("Out lap", lapName(1))
        assertEquals("Lap 6", lapName(7))
        assertEquals("outlap", lapFileWord(1))
        assertEquals("lap6", lapFileWord(7))
    }

    @Test
    fun `a point-to-point track's runs keep their numbers`() {
        assertEquals(1, shownLapNumber(1, pointToPoint = true))
        assertEquals("1", lapMark(1, LapKind.Timed, pointToPoint = true))
        assertEquals("RUN 3", lapMapCaption(3, LapKind.Timed, pointToPoint = true))
        assertEquals("Run 1", lapName(1, pointToPoint = true))
        assertEquals("run2", lapFileWord(2, pointToPoint = true))
    }

    @Test
    fun `the track screen shows no number on the out lap and the first full lap as lap 1`() {
        val circuit = FakeCircuit()
        val drive = circuit.drive(listOf(1.0, 1.05, 0.95))
        val live = LiveTiming(circuit.track())
        val shown = mutableListOf<Int?>()
        for (fix in drive.fixes) {
            val n = live.update(fix).shownLapNumber
            if (shown.isEmpty() || shown.last() != n) shown += n
        }
        // The out lap, then the three full laps (the timer's laps 2 to 4).
        assertEquals(listOf(null, 1, 2, 3), shown.take(4))
        assertNull(LiveTimingState.empty(3).shownLapNumber)
    }
}
