@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A simulated BLE link (FBBD-272): each simulated device answers a connection attempt the way the
 * Android stack did in a drive log, and the attempts follow [GattLink]'s timing: [ReconnectPolicy]
 * delays between attempts and the plan's setup timeout. The stuck RaceBox replays Arek's log of
 * 20 Oct 2026 15:19-15:21: connected, no MTU answer, dropped with status 147 about 8.5 s after the
 * attempt began, nine times in 97 s.
 */
class SetupLadderTest {
    /** What one attempt brings: data after [ms], or a drop after [ms]. */
    private data class Outcome(val streams: Boolean, val ms: Long)

    private fun interface SimulatedDevice {
        fun attempt(plan: ConnectPlan): Outcome
    }

    /** One attempt as [GattLink] saw it. */
    private data class Attempt(val startMs: Long, val plan: ConnectPlan, val streamed: Boolean)

    private class Run(val attempts: List<Attempt>, val streamingAtMs: Long?)

    /**
     * Connects to [device] until data flows or [untilMs] has passed: with [ladder] as [GattLink]
     * now does, or without it (every attempt as before FBBD-272: direct, MTU asked for, 10 s).
     */
    private fun run(device: SimulatedDevice, ladder: SetupLadder?, untilMs: Long = 97_000L): Run {
        val before = ConnectPlan(autoConnect = false, askMtu = true, setupTimeoutMs = 10_000L)
        val policy = ReconnectPolicy()
        val attempts = mutableListOf<Attempt>()
        var t = 0L
        while (t < untilMs) {
            val plan = ladder?.plan() ?: before
            val outcome = device.attempt(plan)
            // The watchdog gives up on a setup that brings no data within the plan's timeout.
            val streamed = outcome.streams && outcome.ms <= plan.setupTimeoutMs
            attempts += Attempt(t, plan, streamed)
            if (streamed) {
                ladder?.streaming()
                return Run(attempts, t + outcome.ms)
            }
            t += minOf(outcome.ms, plan.setupTimeoutMs)
            ladder?.failed()
            t += policy.nextDelayMs()
        }
        return Run(attempts, null)
    }

    private val drop147 = Outcome(streams = false, ms = 8_500L)

    /** The RaceBox of 20 Oct: the MTU request never answered, the link dropped with 147. */
    private val stuckMtu = SimulatedDevice { plan ->
        when {
            plan.askMtu -> drop147
            plan.autoConnect -> Outcome(streams = true, ms = 3_000L)
            else -> Outcome(streams = true, ms = 1_500L)
        }
    }

    /** The same RaceBox at 15:22, after the app restarted: data 2 s after connecting. */
    private val healthy = SimulatedDevice { Outcome(streams = true, ms = 2_000L) }

    /** A device whose direct connections all time out (status 147) but which Android reaches in the background. */
    private val directTimesOut = SimulatedDevice { plan ->
        if (plan.autoConnect) Outcome(streams = true, ms = 12_000L) else drop147
    }

    /** A RaceBox switched off: nothing ever answers. */
    private val off = SimulatedDevice { drop147 }

    @Test
    fun `the log of 20 Oct replays, without the ladder the stuck RaceBox never connects in 97 s`() {
        val run = run(stuckMtu, ladder = null)
        assertNull(run.streamingAtMs)
        assertEquals("nine attempts in 97 s, as in the log", 9, run.attempts.size)
        assertTrue(run.attempts.all { it.plan.askMtu && !it.plan.autoConnect })
    }

    @Test
    fun `with the ladder the stuck RaceBox streams on the third attempt, 20 s in`() {
        val run = run(stuckMtu, SetupLadder())
        assertEquals(3, run.attempts.size)
        assertFalse(run.attempts.last().plan.askMtu)
        assertEquals(20_000L, run.streamingAtMs)
    }

    @Test
    fun `a healthy RaceBox connects on the first attempt with the MTU asked for, as before`() {
        val run = run(healthy, SetupLadder())
        assertEquals(1, run.attempts.size)
        assertTrue(run.attempts.single().plan.askMtu)
        assertEquals(2_000L, run.streamingAtMs)
    }

    @Test
    fun `a device only Android's background connection reaches streams on the fourth attempt`() {
        val run = run(directTimesOut, SetupLadder())
        assertEquals(4, run.attempts.size)
        assertTrue(run.attempts.last().plan.autoConnect)
        assertEquals(30_000L, run.attempts.last().plan.setupTimeoutMs)
        assertTrue(requireNotNull(run.streamingAtMs) < 97_000L)
    }

    @Test
    fun `a device that never answers keeps being tried, alternating direct and background connections`() {
        val run = run(off, SetupLadder(), untilMs = 300_000L)
        assertNull(run.streamingAtMs)
        val plans = run.attempts.map { it.plan }
        assertEquals(listOf(true, true, false, false), plans.take(4).map { it.askMtu })
        assertEquals(listOf(false, false, false, true, false, true), plans.take(6).map { it.autoConnect })
    }

    @Test
    fun `after a drop the next setup starts from the step that worked`() {
        val ladder = SetupLadder()
        run(stuckMtu, ladder)
        assertFalse("still no MTU request after a later drop", ladder.plan().askMtu)
        val fresh = SetupLadder()
        fresh.failed()
        fresh.streaming()
        assertEquals("a first-step success clears the count", 0, fresh.failures)
    }
}
