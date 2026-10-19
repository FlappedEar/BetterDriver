package com.flappedear.betterdriver.camera

import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.setup.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalPolicyTest {
    private fun ladder(size: VideoSize, fps: Int) = ladderFrom(VideoMode(size, fps)).map { it.label }

    @Test
    fun ladderKeepsTheFrameRateAndDropsSizeFirst() {
        // Arek 2026-10-13: frame rate before quality when the phone overheats.
        assertEquals(listOf("4K · 60", "2.7K · 60", "1080p · 60", "720p · 60", "720p · 30"), ladder(VideoSize.UHD, 60))
    }

    @Test
    fun ladderStartsAtTheChosenModeAndNeverGoesAbove() {
        assertEquals(listOf("1080p · 60", "720p · 60", "720p · 30"), ladder(VideoSize.FHD, 60))
        // 2.7K is offered at 60 fps only (UI-83 B), so a ladder at 30 fps skips it.
        assertEquals(listOf("4K · 30", "1080p · 30", "720p · 30"), ladder(VideoSize.UHD, 30))
        assertEquals(listOf("720p · 60", "720p · 30"), ladder(VideoSize.HD, 60))
        assertEquals(listOf("720p · 30"), ladder(VideoSize.HD, 30))
    }

    @Test
    fun theFrameRateOnlyDropsAtTheLastStep() {
        for (start in listOf(VideoSize.UHD, VideoSize.FHD, VideoSize.HD)) {
            val steps = ladderFrom(VideoMode(start, 60))
            assertEquals(listOf(60), steps.dropLast(1).map { it.fps }.distinct())
            assertEquals(VideoMode(VideoSize.HD, 30), steps.last())
        }
    }

    @Test
    fun twoPointSevenKFallsTo1080pAtTheSameRate() {
        // FBBD-239: 2.7K records through WideRecorder; a phone that cannot falls to 1080p at 60.
        assertEquals(listOf("2.7K · 60", "1080p · 60", "720p · 60", "720p · 30"), ladder(VideoSize.QHD, 60))
        assertEquals(listOf("1080p · 30", "720p · 30"), ladder(VideoSize.QHD, 30))
    }

    @Test
    fun onlyAndroidsShutdownStopsVideo() {
        // Arek 2026-10-19: "as long as we won't trigger 0x06, we don't care about temperature".
        val p = ThermalPolicy()
        for (status in Thermal.NONE until Thermal.SHUTDOWN) {
            assertEquals(ThermalAction.Keep, p.onReading(status))
        }
        assertEquals(ThermalAction.StopVideo, p.onReading(Thermal.SHUTDOWN))
        assertEquals(0, p.current)
    }

    @Test
    fun aStepThatFailedToBindIsNeverUsedAgain() {
        val p = ThermalPolicy()
        p.failed(0)
        p.moveTo(1)
        assertEquals(1, p.ceiling)
        assertEquals(1, p.current)
    }
}
