package com.flappedear.betterdriver.core.ble

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The common UTC conversion gives what java.time gave the Android app before it moved (FBBD-332). */
class UtcTimeParityTest {
    @Suppress("LongParameterList")
    private fun javaTime(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long? = try {
        LocalDateTime.of(y, mo, d, h, mi, s).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: DateTimeException) {
        null
    }

    @Test
    fun `matches java time for valid and impossible dates`() {
        val r = Random(332)
        repeat(200_000) {
            // Wider than real values, so impossible dates and times are covered too.
            val y = r.nextInt(0, 65_536)
            val mo = r.nextInt(0, 14)
            val d = r.nextInt(0, 33)
            val h = r.nextInt(0, 26)
            val mi = r.nextInt(0, 62)
            val s = r.nextInt(0, 62)
            assertEquals(javaTime(y, mo, d, h, mi, s), utcEpochMillis(y, mo, d, h, mi, s), "$y-$mo-$d $h:$mi:$s")
        }
    }

    @Test
    fun `leap days and the epoch`() {
        assertEquals(0L, utcEpochMillis(1970, 1, 1, 0, 0, 0))
        for (y in listOf(1900, 2000, 2024, 2025, 2100)) {
            assertEquals(javaTime(y, 2, 29, 12, 0, 0), utcEpochMillis(y, 2, 29, 12, 0, 0), "$y")
        }
    }
}
