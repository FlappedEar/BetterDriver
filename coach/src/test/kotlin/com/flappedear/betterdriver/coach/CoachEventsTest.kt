package com.flappedear.betterdriver.coach

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CoachEventsTest {
    @Test
    fun `a voice start says how and where it went, and the output's delay, measured or set`() {
        assertEquals(
            "seq=3 path=live asked_us=1000 route=headset ready_us=1120 device=bt_a2dp focus=granted out_delay_ms=180",
            CoachEvents.voiceText(3, "live", 1_000, "headset", 1_120, "bt_a2dp", focus = true, outDelayMillis = 180),
        )
        assertEquals(
            "seq=4 path=clip asked_us=5 route=speaker focus=refused out_delay_ms=none",
            CoachEvents.voiceText(4, "clip", 5, "speaker", focus = false, outDelayMillis = 0),
        )
        assertEquals(
            "seq=5 path=clip asked_us=5 route=headset out_delay_ms=0 out_delay_src=measured",
            CoachEvents.voiceText(5, "clip", 5, "headset", outDelayMillis = 0, outDelaySource = "measured"),
        )
        assertEquals(
            "seq=6 path=clip asked_us=5 route=headset out_delay_ms=300 out_delay_src=set",
            CoachEvents.voiceText(6, "clip", 5, "headset", outDelayMillis = 300, outDelaySource = "set"),
        )
        assertEquals(
            "seq=7 path=live asked_us=5 route=headset out_delay_ms=250 out_delay_src=measured timed_src=set",
            CoachEvents.voiceText(
                7, "live", 5, "headset", outDelayMillis = 250, outDelaySource = "measured", timedSource = "set",
            ),
        )
        assertEquals("seq=4 end=stopped", CoachEvents.voiceEndText(4, "stopped"))
    }
}
