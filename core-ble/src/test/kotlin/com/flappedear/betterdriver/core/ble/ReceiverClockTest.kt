package com.flappedear.betterdriver.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverClockTest {
    /** Arrival delays in microseconds, repeating: Bluetooth delivers in bursts, but in order. */
    private val delays = longArrayOf(30_000, 65_000, 62_000, 31_000, 66_000, 45_000, 33_000, 60_000)

    @Test
    fun `jittered arrivals get evenly spaced stamps at the lowest delay`() {
        val clock = ReceiverClock()
        val stamps = (0 until 100).map { i ->
            val tow = 1_000_000L + i * 40
            clock.stamp(tow * 1_000 + delays[i % delays.size], tow)
        }
        // From the first message with the lowest delay (30 ms) on, steps are exactly 40 ms.
        stamps.drop(1).zipWithNext().forEach { (a, b) -> assertEquals(40_000L, b - a) }
        assertEquals((1_000_000L + 99 * 40) * 1_000 + 30_000, stamps.last())
    }

    @Test
    fun `messages delivered in one notification keep their spacing`() {
        val clock = ReceiverClock()
        // The first notification of a link carries two messages 40 ms apart.
        val stamps = clock.stamp(5_000_080_000L, listOf(5_000_000L, 5_000_040L))
        assertEquals(listOf(5_000_040_000L, 5_000_080_000L), stamps)
    }

    @Test
    fun `a stamp is never after the arrival and never before the previous one`() {
        val clock = ReceiverClock()
        var last = Long.MIN_VALUE
        var drift = 0L
        for (i in 0 until 500) {
            if (i % 25 == 0) continue // The whole-second message is missing.
            val tow = 2_000_000L + i * 40
            drift += 2 // The phone's clock runs fast against the receiver's.
            val arrival = tow * 1_000 + drift + delays[i % delays.size]
            val t = clock.stamp(arrival, tow)
            assertTrue(t <= arrival)
            assertTrue(t > last)
            last = t
        }
    }

    /**
     * Arek's 2026-10-13 drive (FBBD-218): the link carried about 24 messages a second, so they queued
     * in the RaceBox and the delay grew by about 275 ms over two minutes, then stayed. The stamps stay
     * at the receiver's time plus the link's real latency; the backlog is reported, not stamped in.
     */
    @Test
    fun `a backlog building up in the link does not delay the stamps`() {
        val clock = ReceiverClock()
        val latency = 30_000L
        var backlog = 0L
        var worstLate = 0L
        var lastBacklog = 0L
        for (i in 0 until 30_000) { // 20 min at 25 Hz
            val tow = 3_000_000L + i * 40
            if (i in 3_000 until 6_000) backlog = minOf(275_000L, backlog + 92) // 2 min to 275 ms
            val arrival = tow * 1_000 + latency + backlog + delays[i % delays.size] - 30_000
            val t = clock.stamp(arrival, tow)
            worstLate = maxOf(worstLate, t - (tow * 1_000 + latency))
            lastBacklog = clock.backlogMicros(arrival, tow)
        }
        // Rising 100 ppm over the 16 min after the backlog built up is at most 96 ms, not 275 ms.
        assertTrue("stamps up to $worstLate µs late", worstLate < 100_000)
        assertTrue("backlog $lastBacklog µs", lastBacklog > 175_000)
    }

    @Test
    fun `follows a phone clock drifting by 50 ppm either way`() {
        for (ppm in listOf(-50L, 50L)) {
            val clock = ReceiverClock()
            var worst = 0L
            for (i in 0 until 45_000) { // 30 min
                val tow = 4_000_000L + i * 40
                val drift = i * 40L * ppm / 1_000 // µs
                val real = tow * 1_000 + drift + 30_000
                val t = clock.stamp(real + delays[i % delays.size] - 30_000, tow)
                if (i > 250) worst = maxOf(worst, kotlin.math.abs(t - real))
            }
            assertTrue("$ppm ppm: stamps off by up to $worst µs", worst < 1_000)
        }
    }

    @Test
    fun `starts over from the arrival after a reconnect or a step back`() {
        val clock = ReceiverClock()
        clock.stamp(1_030_000, 1_000)
        assertEquals(1_070_000L, clock.stamp(1_100_000, 1_040))
        // 5 s without data, then the receiver's time continues: the arrival time is used again.
        assertEquals(6_200_000L, clock.stamp(6_200_000, 6_000))
        // The week wrapped: time of week went back.
        assertEquals(6_300_000L, clock.stamp(6_300_000, 0))
        clock.reset()
        assertEquals(6_400_000L, clock.stamp(6_400_000, 40))
    }

    @Test
    fun `starts over when the receiver's time jumps forward`() {
        val clock = ReceiverClock()
        clock.stamp(1_030_000, 1_000)
        clock.stamp(1_070_000, 1_040)
        // Time of week moved on 160 ms in 40 ms: the delay would fall by 120 ms.
        assertEquals(1_110_000L, clock.stamp(1_110_000, 1_200))
    }

    @Test
    fun `a notification spanning a week wrap stamps each side with its own delay`() {
        val clock = ReceiverClock()
        clock.stamp(604_799_950_000, 604_799_920)
        // The last message of the week and the first of the next arrive together, 50 ms after the wrap.
        val stamps = clock.stamp(604_800_050_000, listOf(604_799_960L, 0L))
        assertEquals(listOf(604_799_990_000L, 604_800_050_000L), stamps)
        assertEquals(604_800_090_000L, clock.stamp(604_800_100_000, 40))
    }
}
