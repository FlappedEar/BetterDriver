package com.flappedear.betterdriver.coach

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionDelaysTest {
    /** Arek's 20 Oct 07:18 drive: measured on the speaker at the start, headset connected later (FBBD-255). */
    @Test
    fun `a headset connected mid-session uses its own measured delay`() {
        val d = SessionDelays(setMillis = 0)
        d.measured("live", "speaker", 100)
        assertEquals(AudioDelayUsed(0, AudioDelayUsed.Set), d.on("live", "bt_sco"), "not measured yet")
        d.measured("live", "bt_sco", 250)
        assertEquals(AudioDelayUsed(250, AudioDelayUsed.Measured), d.on("live", "bt_sco"))
        assertEquals(AudioDelayUsed(100, AudioDelayUsed.Measured), d.on("live", "speaker"))
        assertEquals(AudioDelayUsed(0, AudioDelayUsed.Set), d.on("clip", "bt_sco"), "clips measured apart")
        d.measured("live", "bt_sco", null)
        assertEquals(AudioDelayUsed(0, AudioDelayUsed.Set), d.on("live", "bt_sco"))
    }
}
