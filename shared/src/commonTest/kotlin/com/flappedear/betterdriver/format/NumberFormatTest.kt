package com.flappedear.betterdriver.format

import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormatTest {
    @Test
    fun `rounds to the given decimals`() {
        assertEquals("1.2", fixed(1.24, 1))
        assertEquals("1.3", fixed(1.26, 1))
        assertEquals("12.346", fixed(12.3456, 3))
        assertEquals("3", fixed(2.7, 0))
        assertEquals("0.00", fixed(0.0, 2))
    }

    @Test
    fun `pads the fraction with zeros`() {
        assertEquals("2.05", fixed(2.05001, 2))
        assertEquals("7.000", fixed(7.0, 3))
    }

    @Test
    fun `keeps the sign of negative values`() {
        assertEquals("-1.5", fixed(-1.5, 1))
        assertEquals("-0.0", fixed(-0.01, 1))
        assertEquals("-12", fixed(-12.4, 0))
    }

    @Test
    fun `signed shows plus for positive and zero`() {
        assertEquals("+0.25", signedFixed(0.25, 2))
        assertEquals("-0.25", signedFixed(-0.25, 2))
        assertEquals("+0.00", signedFixed(0.0, 2))
    }

    @Test
    fun `not a number and infinity as on the JVM`() {
        assertEquals("NaN", fixed(Double.NaN, 1))
        assertEquals("Infinity", fixed(Double.POSITIVE_INFINITY, 1))
        assertEquals("-Infinity", fixed(Double.NEGATIVE_INFINITY, 2))
    }
}
