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
            stats.add(t)
            line = stats.line()
        }
        assertEquals(1_500, sent)
        // 65 seconds, each missing two messages.
        assertEquals(
            "1500 messages in 0 notifications, 130 missing (65 on a whole second), " +
                "up to 0 ms behind, 0 other frames",
            line,
        )
    }

    @Test
    fun aSteadyStreamMissesNothing() {
        val stats = StreamStats()
        var line: String? = null
        for (i in 1..1_500) {
            stats.add(i * 40L)
            line = stats.line()
        }
        assertEquals(
            "1500 messages in 0 notifications, 0 missing (0 on a whole second), " +
                "up to 0 ms behind, 0 other frames",
            line,
        )
        stats.add(60_040L)
        assertNull(stats.line())
    }

    @Test
    fun aTenHertzStreamMissesNothingButItsOwnGaps() {
        val stats = StreamStats()
        var line: String? = null
        var t = 0L
        var sent = 0
        while (line == null) {
            t += 100
            // A RaceBox set to 10 Hz for standalone recording, missing one message in each 10 s.
            if (t % 10_000 == 5_300L) continue
            sent++
            stats.add(t)
            line = stats.line()
        }
        // 1500 messages over about 151.5 s, each 10 s missing one: 15 missing, none on a whole second.
        assertEquals(
            "1500 messages in 0 notifications, 15 missing (0 on a whole second), " +
                "up to 0 ms behind, 0 other frames",
            line,
        )
    }

    @Test
    fun theClockJumpingWhenTheFixComesIsNoMissingMessage() {
        val stats = StreamStats()
        var line: String? = null
        // No fix: the time of week counts from a guess, then jumps 3.2 days ahead to GPS time.
        for (i in 1..700) stats.add(i * 40L)
        val gps = 275_896_000L
        for (i in 0 until 800) {
            stats.add(gps + i * 40L)
            line = stats.line()
        }
        assertEquals(
            "1500 messages in 0 notifications, 0 missing (0 on a whole second), " +
                "up to 0 ms behind, 0 other frames",
            line,
        )
    }

    @Test
    fun countsNotificationsAndOtherFramesThenStartsOver() {
        val stats = StreamStats()
        repeat(3) { stats.notification() }
        stats.other(UbxFrame(0xFF, 0x26, ByteArray(4)))
        stats.other(UbxFrame(0xFF, 0x01, ByteArray(76)))
        stats.other(UbxFrame(0xFF, 0x01, ByteArray(76)))
        var line: String? = null
        for (i in 1..1_500) {
            stats.add(i * 40L)
            line = stats.line()
        }
        assertEquals(
            "1500 messages in 3 notifications, 0 missing (0 on a whole second), up to 0 ms behind, " +
                "3 other frames (ff 01 76 B ×2, ff 26 4 B ×1)",
            line,
        )
        for (i in 1..1_500) {
            stats.add(60_000L + i * 40L)
            line = stats.line()
        }
        assertEquals(
            "1500 messages in 0 notifications, 0 missing (0 on a whole second), " +
                "up to 0 ms behind, 0 other frames",
            line,
        )
    }

    @Test
    fun aNotificationIsNeverSplitBetweenLines() {
        val stats = StreamStats()
        for (i in 1..1_499) {
            stats.notification()
            stats.add(i * 40L)
            assertNull(stats.line())
        }
        // One notification carries messages 1500 and 1501: both go in this line, with it.
        stats.notification()
        stats.add(60_000L)
        stats.add(60_040L)
        assertEquals(
            "1501 messages in 1500 notifications, 0 missing (0 on a whole second), up to 0 ms behind, 0 other frames",
            stats.line(),
        )
        assertNull(stats.line())
    }
}
