package com.flappedear.betterdriver.analysis.quality

import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The running figures of the report's Channels section (FBBD-174): no list per sample. */
class ChannelQualityTest {
    @Test
    fun `a day of 25 Hz samples is summed up in a few numbers`() {
        val times = ChannelTimes()
        val samples = 2_160_000
        // Every 40 ms, with one 3 s gap halfway.
        for (i in 0 until samples) times.add(i * 40_000L + if (i >= samples / 2) 3_000_000L else 0L)
        assertEquals(samples, times.count)
        assertEquals(3_040_000L, times.maxGap)
        val span = (samples - 1) * 40_000L + 3_000_000L
        assertEquals((samples - 1) / (span / 1e6), times.rateHz!!, 1e-9)
    }

    @Test
    fun `one sample or none has no rate and no gap`() {
        val times = ChannelTimes()
        assertNull(times.rateHz)
        times.add(5)
        assertNull(times.rateHz)
        assertEquals(0L, times.maxGap)
        assertEquals(1, times.count)
    }

    @Test
    fun `the accuracy median by centimetre matches the exact nearest-rank median`() {
        val values = List(100_001) { 0.3 + (it * 7_919 % 1_000) / 997.0 }
        val cm = Centimetres().apply { values.forEach { add(it) } }
        val exact = nearestRank(values, 0.5)!!
        fun twoDecimals(value: Double) = String.format(Locale.ROOT, "%.2f", value)
        assertEquals(twoDecimals(exact), twoDecimals(cm.median()!!))
        assertNull(Centimetres().median())
        assertEquals(0.5, Centimetres().apply { add(0.5) }.median()!!, 1e-12)
    }
}
