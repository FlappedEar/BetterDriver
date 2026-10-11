@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.DeltaReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeltaReferenceTest {
    /** Out along y = 0 to x = 200, round a tight hairpin, back along y = 12. 10 m/s, a point a second. */
    private val hairpin: DeltaReference = run {
        val out = (0..20).map { Xy(it * 10.0, 0.0) }
        val back = (20 downTo 0).map { Xy(it * 10.0, 12.0) }
        val points = out + back
        DeltaReference(points, points.indices.map { it * 1_000_000L })
    }

    @Test
    fun `never jumps to the other leg of a hairpin`() {
        val cursor = hairpin.Cursor()
        // Driving out 7 m off the reference line: closer to the way back (5 m) than to the way out.
        for (x in 0..150 step 10) {
            val ref = assertNotNull(cursor.referenceMicros(Xy(x.toDouble(), 7.0)))
            assertEquals(x * 100_000.0, ref, 1.0, "at x = $x")
        }
    }

    @Test
    fun `no delta far off the path, and it picks up again after`() {
        val cursor = hairpin.Cursor()
        cursor.referenceMicros(Xy(0.0, 0.0))
        assertNull(cursor.referenceMicros(Xy(50.0, -100.0)))
        assertEquals(15_000_000.0, assertNotNull(cursor.referenceMicros(Xy(150.0, 0.0))), 1.0)
    }
}
