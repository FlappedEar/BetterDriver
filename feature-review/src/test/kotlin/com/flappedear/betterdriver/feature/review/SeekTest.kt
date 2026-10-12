package com.flappedear.betterdriver.feature.review

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a lap video seeks (FBBD-93). */
class SeekTest {
    @Test
    fun firstFrameIsSoughtWhenTheExactFrameIsWanted() {
        // Media time 0: the start of a clip, as when the finger lifts at the start line.
        assertTrue(seekDue(0, null))
        assertTrue(seekDue(12_345, null))
    }

    @Test
    fun smallMovesAreLeftToThePlayer() {
        assertFalse(seekDue(0, 0))
        assertFalse(seekDue(1_020, 1_000))
        assertTrue(seekDue(1_021, 1_000))
        assertTrue(seekDue(979, 1_000))
    }
}
