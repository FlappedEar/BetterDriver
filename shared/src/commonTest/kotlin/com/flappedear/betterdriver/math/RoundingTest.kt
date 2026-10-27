package com.flappedear.betterdriver.math

import kotlin.test.Test
import kotlin.test.assertEquals

class RoundingTest {
    @Test
    fun roundsLikeTheJdk() {
        assertEquals(3L, roundHalfUp(2.5))
        assertEquals(-2L, roundHalfUp(-2.5))
        assertEquals(0L, roundHalfUp(0.49999999999999994))
        assertEquals(0L, roundHalfUp(Double.NaN))
        assertEquals(Long.MAX_VALUE, roundHalfUp(Double.POSITIVE_INFINITY))
        assertEquals(Long.MIN_VALUE, roundHalfUp(Double.NEGATIVE_INFINITY))
    }
}
