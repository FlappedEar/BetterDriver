package com.flappedear.betterdriver.analysis.heartrate

import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.recorder.log.HeartRateLogIds
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Heart rate after a session (FBBD-225, UI-107 A): the session's and each lap's, only what was recorded. */
class HeartRateStatsTest {
    private fun session(vararg samples: Pair<Double, Double>) = TelemetrySession(
        mapOf(
            HeartRateLogIds.NAME to TelemetryChannel(
                HeartRateLogIds.NAME,
                samples.map { it.first }.toDoubleArray(),
                samples.map { it.second }.toDoubleArray(),
                "bpm",
            ),
        ),
        emptyMap(),
    )

    private fun lap(number: Int, start: Double, end: Double) = TimedLap(number, start, end, end - start)

    @Test
    fun `the session's heart rate is the average, highest and lowest recorded`() {
        val s = heartRateStats(session(10.0 to 100.0, 11.0 to 120.0, 12.0 to 131.0))!!
        assertEquals(HeartRateStats(117, 131, 100, 3, 10.0, 12.0, 2.0), s)
    }

    @Test
    fun `each lap gets the beats between its gates, and a lap without any gets none`() {
        val s = session(1.0 to 90.0, 2.0 to 110.0, 3.0 to 130.0, 9.0 to 150.0)
        val laps = listOf(lap(1, 0.0, 2.5), lap(2, 2.5, 5.0), lap(3, 5.0, 8.0))
        val stats = lapHeartRates(s, laps)
        assertEquals(100, stats[0]!!.averageBpm)
        assertEquals(110, stats[0]!!.maxBpm)
        assertEquals(130, stats[1]!!.averageBpm)
        assertNull(stats[2])
    }

    @Test
    fun `a strap that dropped out is covered only while it was sending`() {
        // A minute at the start, then nothing for 58 minutes, then a minute at the end.
        val start = (0..60).map { it.toDouble() to 120.0 }
        val end = (3540..3600).map { it.toDouble() to 130.0 }
        val s = heartRateStats(session(*(start + end).toTypedArray()))!!
        assertEquals(120.0, s.coveredSeconds, 1e-9)
        assertEquals(3600.0, s.lastSeconds - s.firstSeconds, 1e-9)
    }

    @Test
    fun `no strap, or nothing but zeros and gaps, is no heart rate, never 0 bpm`() {
        assertNull(heartRateStats(TelemetrySession(emptyMap(), emptyMap())))
        assertNull(heartRateStats(session(1.0 to 0.0, 2.0 to Double.NaN)))
    }
}
