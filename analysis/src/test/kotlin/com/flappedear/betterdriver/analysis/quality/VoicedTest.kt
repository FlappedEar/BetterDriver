package com.flappedear.betterdriver.analysis.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class VoicedTest {
    private fun voiced(route: String, ms: Long?, source: String?) =
        Voiced(1_000_000, 1, "clip", route, outDelayMillis = ms, outDelaySource = source)

    @Test
    fun `a measured delay counts on the speaker too, a set or tapped one only on a headset`() {
        assertEquals(1_030_000, voiced("speaker", 30, "measured").heardMicros)
        assertEquals(1_000_000, voiced("speaker", 300, "set").heardMicros)
        assertEquals(1_000_000, voiced("speaker", 300, null).heardMicros)
        assertEquals(1_190_000, voiced("headset", 190, "measured").heardMicros)
        assertEquals(1_300_000, voiced("headset", 300, null).heardMicros)
    }
}
