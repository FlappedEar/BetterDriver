package com.flappedear.betterdriver.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraFramesTest {
    private val frameNanos = 16_666_667L

    @Test
    fun nothingDeliveredGivesNoLine() {
        assertNull(CameraFrames(60).line())
    }

    @Test
    fun aSteadyMinuteAt60() {
        val frames = CameraFrames(60)
        repeat(3_601) { frames.completed(1_000L + it * frameNanos, frameNanos, 8_000_000L, 60 to 60) }
        assertEquals(
            "camera frames: 3601 in 60.0 s = 60.0 fps, skipped=0 failed=0 buffer_lost=0 max_gap_ms=16.7 " +
                "frame_ms=16.7-16.7 exposure_max_ms=8.0 ae=[60,60]",
            frames.line(),
        )
    }

    @Test
    fun skippedFramesAndLostBuffersAreCounted() {
        val frames = CameraFrames(60)
        // Every 20th frame is missing from the sensor timestamps: 57 fps.
        var t = 0L
        repeat(1_140) { i ->
            t += if (i > 0 && i % 19 == 0) 2 * frameNanos else frameNanos
            frames.completed(t, frameNanos, null, null)
        }
        frames.bufferLost()
        frames.failed()
        val line = frames.line()!!
        assertEquals("skipped=59", Regex("""skipped=\d+""").find(line)?.value)
        assertEquals("buffer_lost=1", Regex("""buffer_lost=\d+""").find(line)?.value)
        assertEquals("failed=1", Regex("""failed=\d+""").find(line)?.value)
        assertEquals("max_gap_ms=33.3", Regex("""max_gap_ms=[\d.]+""").find(line)?.value)
        assertEquals("57.0 fps", Regex("""[\d.]+ fps""").find(line)?.value)
    }

    @Test
    fun theNextMinuteStartsAtTheLastFrame() {
        val frames = CameraFrames(60)
        repeat(61) { frames.completed(it * frameNanos + 1, frameNanos, null, null) }
        frames.line()
        repeat(60) { frames.completed((61 + it) * frameNanos + 1, frameNanos, null, null) }
        assertEquals("60 in 1.0 s = 60.0 fps", Regex("""\d+ in [\d.]+ s = [\d.]+ fps""").find(frames.line()!!)?.value)
        assertNull("a minute without frames", frames.line())
    }

    @Test
    fun processingIsLoggedOnlyWhenItChanges() {
        val frames = CameraFrames(60)
        assertEquals(
            "camera processing: noise_reduction=HIGH_QUALITY edge=FAST video_stabilization=ON ois=ON",
            frames.processing(2, 1, 1, 1),
        )
        assertNull("same modes", frames.processing(2, 1, 1, 1))
        assertEquals(
            "camera processing: noise_reduction=FAST edge=FAST video_stabilization=OFF ois=n/a",
            frames.processing(1, 1, 0, null),
        )
        assertEquals("unknown value", true, frames.processing(7, 1, 0, null)?.contains("noise_reduction=mode7"))
    }
}
