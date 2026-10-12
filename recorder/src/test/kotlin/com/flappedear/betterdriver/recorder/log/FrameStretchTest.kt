package com.flappedear.betterdriver.recorder.log

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameStretchTest {
    private val ms = 1_000_000L

    @Test
    fun `frames are counted into buckets and a stretch closes with the first frame after it`() {
        val stretch = FrameStretch(periodNanos = 1_000 * ms)
        assertNull(stretch.add(0, 7 * ms, jank = false))
        assertNull(stretch.add(16 * ms, 16 * ms, jank = false))
        assertNull(stretch.add(32 * ms, 40 * ms, jank = true))
        assertNull(stretch.add(80 * ms, 900 * ms, jank = true))
        val closed = stretch.add(1_000 * ms, 8 * ms, jank = false)!!
        assertEquals(1_000 * ms, closed.endNanos)
        assertEquals("frames=4 janky=2 frozen=1 worst_ms=900 hist=1,0,1,0,0,0,1,0,0,0,1", closed.text)
        // The frame that closed it starts the next.
        assertEquals("frames=1 janky=0 frozen=0 worst_ms=8 hist=1,0,0,0,0,0,0,0,0,0,0", stretch.flush()!!.text)
        assertNull(stretch.flush())
    }
}
