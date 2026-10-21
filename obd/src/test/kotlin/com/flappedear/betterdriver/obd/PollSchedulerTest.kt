@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-53: fast channels get most of the adapter, slow ones a fair minimum. */
class PollSchedulerTest {
    private val frs = CarProfiles.ScionFrs2013
    private val fast = listOf(StandardPids.Rpm, StandardPids.Speed, StandardPids.Throttle) +
        frs.channels.filter { it.id == "brake" }
    private val slow = listOf(StandardPids.Coolant, StandardPids.FuelLevel, StandardPids.ModuleVoltage) +
        frs.channels.filter { it.id == "oil_temp" }

    /** Runs the scheduler for [durationMs] against an adapter that takes [latencyMs] per request. */
    private fun run(
        scheduler: PollScheduler,
        latencyMs: Long,
        durationMs: Long,
        answers: (Request) -> Boolean = { true },
    ): Long {
        var now = 0L
        while (now < durationMs) {
            val next = assertNotNull(scheduler.next(now))
            now = maxOf(now, next.notBeforeMs)
            scheduler.completed(next.request, now, latencyMs, answers(next.request))
            now += latencyMs
        }
        return now
    }

    @Test
    fun `temperatures due together go one at a time between the fast requests`() {
        // FBBD-284: four slow requests due at once on a single-PID adapter.
        val scheduler = PollScheduler(fast + slow, fastWeight = 10, slowMaxHz = 0.5, rateWindowMs = 30_000)
        val sent = ArrayList<Request>()
        var now = 0L
        while (now < 30_000) {
            val next = assertNotNull(scheduler.next(now))
            now = maxOf(now, next.notBeforeMs)
            scheduler.completed(next.request, now, 90, answered = true)
            sent += next.request
            now += 90
        }
        val isSlow = slow.map { it.request }.toSet()
        val runs = sent.indices.filter { i -> sent[i] in isSlow && i > 0 && sent[i - 1] in isSlow }
        assertTrue(runs.isEmpty(), "slow requests back to back at ${runs.take(5)}")
        // Every fast request between two slow ones, so engine speed waits for one temperature at most.
        val rpm = sent.indices.filter { sent[it] == StandardPids.Rpm.request }
        assertTrue(rpm.zipWithNext { x, y -> y - x }.max() <= fast.size + 1, sent.take(40).toString())
        // Their share is still set by the weight: one slow request for ten of each fast one.
        for (channel in slow) assertTrue(scheduler.achievedHz(channel, now) >= 0.25, channel.id)
    }

    @Test
    fun `fast channels are polled fastWeight times as often as slow ones`() {
        // Slow cap out of the way: pure weights.
        val scheduler = PollScheduler(fast + slow, fastWeight = 5, slowMaxHz = 100.0, rateWindowMs = 60_000)
        val end = run(scheduler, latencyMs = 50, durationMs = 60_000)
        // 20 requests/s shared 5:1 by 4 fast and 4 slow: fast 20 × 5 ÷ 24 = 4.17 Hz, slow 20 ÷ 24 = 0.83 Hz.
        val fastHz = fast.map { scheduler.achievedHz(it, end) }
        val slowHz = slow.map { scheduler.achievedHz(it, end) }
        fastHz.forEach { assertEquals(100.0 / 24, it, 0.1) }
        slowHz.forEach { assertEquals(20.0 / 24, it, 0.05) }
        assertEquals(5.0, fastHz.average() / slowHz.average(), 0.25)
        val predicted = scheduler.predictedHz(50.0)
        assertEquals(100.0 / 24, predicted.getValue(StandardPids.Rpm.request), 1e-9)
        assertEquals(20.0 / 24, predicted.getValue(StandardPids.Coolant.request), 1e-9)
        assertEquals(50.0, assertNotNull(scheduler.latencyMs), 1e-9)
    }

    @Test
    fun `on a quick bus slow channels stay at their cap and fast ones get the rest`() {
        val scheduler = PollScheduler(fast + slow, fastWeight = 5, slowMaxHz = 2.0, rateWindowMs = 20_000)
        val end = run(scheduler, latencyMs = 10, durationMs = 60_000)
        // 100 requests/s; by weight slow would get 100 ÷ 24 = 4.2 Hz, capped at 2 Hz.
        // The 4 fast ones share what is left: (100 − 4 × 2) ÷ 4 = 23 Hz each.
        slow.forEach {
            val hz = scheduler.achievedHz(it, end)
            assertTrue(hz in 1.8..2.05, "${it.id} $hz Hz")
        }
        fast.forEach { assertEquals(23.0, scheduler.achievedHz(it, end), 0.5) }
        val predicted = scheduler.predictedHz(10.0)
        assertEquals(2.0, predicted.getValue(StandardPids.Coolant.request))
        assertEquals(23.0, predicted.getValue(StandardPids.Rpm.request), 1e-9)
    }

    @Test
    fun `channels on the same request share it`() {
        val sameRequest = StandardPids.Rpm.copy(id = "rpm_raw", equation = "A*256+B")
        val scheduler = PollScheduler(listOf(StandardPids.Rpm, sameRequest, StandardPids.Coolant))
        assertEquals(listOf(StandardPids.Rpm.request, StandardPids.Coolant.request), scheduler.active)
        // The shared request is fast when any of its channels is.
        val slowCopy = sameRequest.copy(pollClass = PollClass.SLOW)
        val mixed = PollScheduler(listOf(slowCopy, StandardPids.Rpm), slowMaxHz = 1.0)
        run(mixed, latencyMs = 50, durationMs = 2_000)
        assertTrue(mixed.achievedHz(StandardPids.Rpm, 2_000) > 1.0)
    }

    @Test
    fun `a group is one request, and each member's rate is its own`() {
        val group = listOf(StandardPids.Rpm, StandardPids.Speed, StandardPids.Throttle)
        val groups = listOf(group, listOf(StandardPids.Coolant))
        val scheduler = PollScheduler(group + StandardPids.Coolant, groups = groups)
        assertEquals(listOf(StandardPids.Rpm.request, StandardPids.Coolant.request), scheduler.active)
        var now = 0L
        while (now < 10_000) {
            val next = assertNotNull(scheduler.next(now))
            now = maxOf(now, next.notBeforeMs)
            // The ECU leaves throttle out of every answer.
            val answered = if (next.request == StandardPids.Rpm.request) {
                listOf(StandardPids.Rpm.request, StandardPids.Speed.request)
            } else {
                null
            }
            scheduler.completed(next.request, now, 50, answered = true, answeredRequests = answered)
            now += 50
        }
        assertTrue(scheduler.achievedHz(StandardPids.Speed, now) > 10.0)
        assertEquals(0.0, scheduler.achievedHz(StandardPids.Throttle, now))
    }

    @Test
    fun `a request that never answers is dropped`() {
        val brake = frs.channels.single { it.id == "brake" }
        val scheduler = PollScheduler(listOf(StandardPids.Rpm, brake), dropAfterMisses = 5)
        run(scheduler, latencyMs = 50, durationMs = 5_000) { it != brake.request }
        assertEquals(listOf(StandardPids.Rpm.request), scheduler.active)
        assertEquals(0.0, scheduler.achievedHz(brake, 5_000))
        val none = PollScheduler(listOf(brake), dropAfterMisses = 1)
        none.completed(brake.request, 0, 50, answered = false)
        assertNull(none.next(100))
    }

    @Test
    fun `only slow channels wait for their interval`() {
        val scheduler = PollScheduler(listOf(StandardPids.Coolant), slowMaxHz = 2.0)
        assertEquals(PollScheduler.Next(StandardPids.Coolant.request, 0), scheduler.next(0))
        scheduler.completed(StandardPids.Coolant.request, 0, 40, answered = true)
        assertEquals(PollScheduler.Next(StandardPids.Coolant.request, 500), scheduler.next(40))
    }
}
