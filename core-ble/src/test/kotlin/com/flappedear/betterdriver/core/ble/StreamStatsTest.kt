package com.flappedear.betterdriver.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamStatsTest {
    @Test
    fun countsMissingMessagesAndThoseOnAWholeSecond() {
        val stats = StreamStats()
        var line: String? = null
        var t = 1_000_000L
        var sent = 0
        while (line == null) {
            t += 40
            // Skip every whole-second message, and one more in the middle of each second.
            if (t % 1_000 == 0L || t % 1_000 == 520L) continue
            sent++
            line = stats.add(t)
        }
        assertEquals(1_500, sent)
        // 65 seconds, each missing two messages.
        assertEquals("1500 messages, 130 missing (65 on a whole second)", line)
    }

    @Test
    fun aSteadyStreamMissesNothing() {
        val stats = StreamStats()
        var line: String? = null
        for (i in 1..1_500) line = stats.add(i * 40L)
        assertEquals("1500 messages, 0 missing (0 on a whole second)", line)
        assertNull(stats.add(60_040L))
    }
}
