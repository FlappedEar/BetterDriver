package com.flappedear.betterdriver.video.gpmf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** GPSU times read without java.time, the same on Android and iOS (FBBD-343). */
class GpsuTest {
    @Test
    fun readsUtcTimes() {
        assertEquals(1_760_169_600_000L, gpsuMillis("251011080000.000"))
        assertEquals(4_102_444_799_999L, gpsuMillis("991231235959.999"))
        // As java.time's SMART resolver: a day past the month's end is its last day, 24:00 the next midnight.
        assertEquals(1_709_164_800_000L, gpsuMillis("240231000000.000"))
        assertEquals(1_709_251_200_000L, gpsuMillis("240229240000.000"))
    }

    @Test
    fun refusesWhatIsNotATime() {
        listOf("", "251011080000.00", "251011080000,000", "251311080000.000", "251000080000.000", "251011080060.000",
            "2510110800a0.000", "251011240001.000", "-51011080000.000").forEach { assertNull(gpsuMillis(it), it) }
    }
}
