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
        assertEquals("1500 messages in 0 notifications, 130 missing (65 on a whole second), 0 other frames", line)
    }

    @Test
    fun aSteadyStreamMissesNothing() {
        val stats = StreamStats()
        var line: String? = null
        for (i in 1..1_500) line = stats.add(i * 40L)
        assertEquals("1500 messages in 0 notifications, 0 missing (0 on a whole second), 0 other frames", line)
        assertNull(stats.add(60_040L))
    }

    @Test
    fun countsNotificationsAndOtherFramesThenStartsOver() {
        val stats = StreamStats()
        repeat(3) { stats.notification() }
        stats.other(UbxFrame(0xFF, 0x26, ByteArray(4)))
        stats.other(UbxFrame(0xFF, 0x01, ByteArray(76)))
        stats.other(UbxFrame(0xFF, 0x01, ByteArray(76)))
        var line: String? = null
        for (i in 1..1_500) line = stats.add(i * 40L)
        assertEquals(
            "1500 messages in 3 notifications, 0 missing (0 on a whole second), " +
                "3 other frames (ff 01 76 B ×2, ff 26 4 B ×1)",
            line,
        )
        for (i in 1..1_500) line = stats.add(60_000L + i * 40L)
        assertEquals("1500 messages in 0 notifications, 0 missing (0 on a whole second), 0 other frames", line)
    }
}
