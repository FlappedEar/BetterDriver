package com.flappedear.betterdriver.coach

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CoachEventsTest {
    @Test
    fun `a voice start says how and where it went, and the headset's delay when the tap test was run`() {
        assertEquals(
            "seq=3 path=live asked_us=1000 route=headset ready_us=1120 device=bt_a2dp focus=granted out_delay_ms=180",
            CoachEvents.voiceText(3, "live", 1_000, "headset", 1_120, "bt_a2dp", focus = true, outDelayMillis = 180),
        )
        assertEquals(
            "seq=4 path=clip asked_us=5 route=speaker focus=refused out_delay_ms=none",
            CoachEvents.voiceText(4, "clip", 5, "speaker", focus = false, outDelayMillis = 0),
        )
        assertEquals("seq=4 end=stopped", CoachEvents.voiceEndText(4, "stopped"))
    }
}
