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
        val clock = ReceiverClock(windowMessages = 5)
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
}
