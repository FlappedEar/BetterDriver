package com.flappedear.betterdriver.video.gpmf

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The common GPSU reader gives what the java.time reader it replaced gave (FBBD-343). */
class GpsuJavaTimeTest {
    private val format = DateTimeFormatter.ofPattern("yyMMddHHmmss.SSS")

    private fun javaTime(text: String): Long? = try {
        LocalDateTime.parse(text, format).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    @Test
    fun agreesWithJavaTime() {
        val random = Random(342)
        fun two(max: Int) = random.nextInt(max).toString().padStart(2, '0')
        repeat(20_000) {
            val text = two(100) + two(14) + two(33) + two(26) + two(61) + two(61) + "." +
                random.nextInt(1000).toString().padStart(3, '0')
            assertEquals(javaTime(text), gpsuMillis(text), text)
        }
    }
}
