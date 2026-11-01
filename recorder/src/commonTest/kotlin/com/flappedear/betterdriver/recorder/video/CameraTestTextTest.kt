package com.flappedear.betterdriver.recorder.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The camera test's text and log lines, shared by Android and iOS (FBBD-278, FBBD-371). */
class CameraTestTextTest {
    @Test
    fun `a part reads as the test screen shows it`() {
        assertEquals("camera 57.2 fps · skipped 3.1 %", CameraTestPart(57.24, 3.06).text)
        val part = CameraTestPart.of(frames = 3_594, dropped = 6, notWritten = 0, seconds = 60.0)!!
        assertEquals("camera 59.9 fps · skipped 0.2 %", part.text)
        // Frames the file refused were delivered: counted once, not added to the total again.
        val refused = CameraTestPart.of(frames = 600, dropped = 0, notWritten = 300, seconds = 10.0)!!
        assertEquals("camera 60.0 fps · skipped 50.0 %", refused.text)
        assertNull(CameraTestPart.of(frames = 0, dropped = 0, notWritten = 0, seconds = 0.0))
    }

    @Test
    fun `log lines are the Android camera test's`() {
        assertEquals(
            "camera test part 1 of 2: 1080p · 60 with MP4, 19000000 bit/s, sound on, 12 min",
            CameraTestText.partStarted(1, "1080p · 60", 19_000_000, sound = true),
        )
        assertEquals(
            "camera test part 2 of 2 without MP4: no frames error=camera error",
            CameraTestText.partEnded(2, null, "camera error"),
        )
        assertEquals(
            "camera test stopped: part 1 camera 60.0 fps · skipped 0.0 %, part 2 n/a, stopped on the test page",
            CameraTestText.ended("Stopped", CameraTestPart(60.0, 0.0), null, "stopped on the test page"),
        )
        val reason = "the app went to the background"
        assertEquals("camera test: stopping, $reason", CameraTestText.stopping(reason))
    }

    @Test
    fun `time left of a part`() {
        assertEquals("12:00 left", CameraTestText.left(720_000))
        assertEquals("0:05 left", CameraTestText.left(5_400))
        assertEquals("0:00 left", CameraTestText.left(-10))
    }
}
