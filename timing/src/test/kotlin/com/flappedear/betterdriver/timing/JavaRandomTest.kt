package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.demo.javaRandomDoubles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Random

/** The common generator gives java.util.Random's numbers, so the demo circuit's laps do not change (FBBD-373). */
class JavaRandomTest {
    @Test
    fun sameNumbersAsJavaUtilRandom() {
        for (seed in listOf(0L, 1L, 1_000_003L, 1_000_007L, -5L, Long.MAX_VALUE)) {
            val java = Random(seed)
            val expected = List(200) { if (it % 3 == 2) (if (java.nextBoolean()) 1.0 else 0.0) else java.nextDouble() }
            assertEquals(expected, javaRandomDoubles(seed, 200), "seed $seed")
        }
    }
}
