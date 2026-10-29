package com.flappedear.betterdriver.recorder.log

import kotlin.test.Test
import kotlin.test.assertEquals

/** The log's decimals read the same on both apps (FBBD-356): as the JVM's "%.<n>f". */
class FixedDecimalsTest {
    @Test
    fun writesAsTheJvmFormatDoes() {
        assertEquals("50.0647310", fixedDecimals(50.064731, 7))
        assertEquals("-19.9452180", fixedDecimals(-19.945218, 7))
        assertEquals("0.0000", fixedDecimals(0.0, 4))
        assertEquals("123.46", fixedDecimals(123.456, 2))
        assertEquals("7.00", fixedDecimals(7.0, 2))
        assertEquals("0.05", fixedDecimals(0.049999, 2))
    }
}
