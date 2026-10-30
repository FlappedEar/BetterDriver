package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.review.SessionFacts
import com.flappedear.betterdriver.analysis.review.recordedFacts
import com.flappedear.betterdriver.analysis.review.sessionLength
import com.flappedear.betterdriver.analysis.review.sessionRowDetail
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionFactsTest {
    @Test
    fun `the row names the car and the length and what was recorded`() {
        val facts = SessionFacts(laps = 14, bestMillis = 92_345, obd = true, heartRate = true)
        assertEquals(
            "Corolla · 22 min · 14 laps · Video · OBD · HR",
            sessionRowDetail("Corolla", 22 * 60_000_000L, facts, true),
        )
        assertEquals("<1 min · 1 lap", sessionRowDetail("", 30_000_000L, SessionFacts(laps = 1), false))
        assertEquals("", sessionRowDetail("", null, null, false))
        assertEquals("18 min", sessionLength(18 * 60_000_000L + 59_000_000L))
    }

    @Test
    fun `car data and heart rate count only with a value recorded`() {
        val obd = ChannelDef(300, "obd_rpm", "rpm", "obd")
        val hr = ChannelDef(400, "hr", "bpm", "heart_rate")
        val data = SessionData(
            id = "s",
            info = SessionInfo(startUtcMillis = 0),
            state = SessionState.FINISHED,
            channels = mapOf(obd.id to obd, hr.id to hr),
            sampleSets = listOf(SampleSet(0, listOf(ChannelValue(obd.id, 900.0)))),
            events = emptyList(),
            end = null,
            truncatedBytes = 0,
            skippedBytes = 0,
        )
        assertEquals(SessionFacts(obd = true, heartRate = false), recordedFacts(data))
    }
}
