package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.log.GnssSecond
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The drive checks from the log after a drive that ended under cover (the 2026-10-18 evening drive). */
class UnderCoverTest {
    private val second = 1_000_000L

    /** Second [k] of the log: 24 fixes at up to [kmh], or, with [kmh] null, 25 samples without a fix. */
    private fun sec(k: Long, kmh: Double?, afterDrop: Boolean = false) = GnssSecond(
        second = k, tMicros = k * second, samples = if (kmh == null) 25 else 24, fixes = if (kmh == null) 0 else 24,
        missing = if (kmh == null) 0 else 1, stepMillis = 40, longGaps = 0, maxRxGapMillis = 80,
        maxGapMicros = 80_000, afterDrop = afterDrop, utcFirst = null, utcLast = null, lagMicros = null,
        lat = kmh?.let { 50.0 }, lon = kmh?.let { 19.0 }, varianceM2 = kmh?.let { 0.0 }, kmh = kmh, kmhMin = kmh,
        kmhMax = kmh, missingOnSecond = if (kmh == null) 0 else 1,
    )

    /** 20 s at 60 km/h, 5 s at 4 km/h, then no fix to 200 s, without data from 150 s to 170 s and after 180 s. */
    private fun seconds(lastKmh: Double = 4.0) = (0L..180L).filterNot { it in 150L..169L }.map { k ->
        when {
            k < 20 -> sec(k, 60.0)
            k < 25 -> sec(k, lastKmh)
            else -> sec(k, null, afterDrop = k == 170L)
        }
    }

    private val events = listOf(
        RecorderEvent(140 * second, EventKinds.SCREEN, "state=off"),
        RecorderEvent(150 * second + 100_000, "racebox_disconnected", "no data for 3399 ms"),
        RecorderEvent(180 * second + 100_000, "racebox_disconnected", "no data for 3236 ms"),
    )

    private fun QualitySection.metric(id: String) = metrics.single { it.id == id }

    @Test
    fun `drops and the screen going off after the last slow fix are left out`() {
        val secs = seconds()
        assertEquals(24 * second, logUnderCoverFrom(secs))
        val gnss = logGnssSection(secs, events, 240 * second, raceBox = true)
        assertEquals(Verdict.NOT_MEASURED, gnss.metric("gnss.reconnect").verdict)
        assertTrue("under cover" in gnss.metric("gnss.reconnect").how)
        val screen = screenSection(events, emptyList(), 240 * second, logUnderCoverFrom(secs))
        assertEquals(Verdict.PASS, screen.metric("ui.screen_off").verdict)
    }

    @Test
    fun `a last fix at speed is no drive ending under cover`() {
        val secs = seconds(lastKmh = 60.0)
        assertNull(logUnderCoverFrom(secs))
        val gnss = logGnssSection(secs, events, 240 * second, raceBox = true)
        assertEquals(Verdict.FAIL, gnss.metric("gnss.reconnect").verdict)
        val screen = screenSection(events, emptyList(), 240 * second, null)
        assertEquals(Verdict.FAIL, screen.metric("ui.screen_off").verdict)
    }

    @Test
    fun `a drop right after the last fix is no drive ending under cover`() {
        val secs = (0L..24L).map { sec(it, 4.0) } + sec(90, null, afterDrop = true)
        assertNull(logUnderCoverFrom(secs))
    }
}
