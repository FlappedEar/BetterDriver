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
        assertEquals(listOf("4K · 60", "1080p · 60", "720p · 60", "720p · 30"), ladder(VideoSize.UHD, 60))
    }

    @Test
    fun ladderStartsAtTheChosenModeAndNeverGoesAbove() {
        assertEquals(listOf("1080p · 60", "720p · 60", "720p · 30"), ladder(VideoSize.FHD, 60))
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
    fun aGoProOnlySizeStartsThePhoneAt1080p() {
        assertEquals(listOf("1080p · 60", "720p · 60", "720p · 30"), ladder(VideoSize.QHD, 60))
    }

    @Test
    fun ignoredHeatNeverStops() {
        // FBBD-231: Arek's heat stress test.
        val p = ThermalPolicy(ignoreHeat = true)
        for (status in listOf(Thermal.MODERATE, Thermal.SEVERE, Thermal.CRITICAL, Thermal.EMERGENCY, 6)) {
            assertEquals(ThermalAction.Keep, p.onReading(status, 70f))
        }
    }

    @Test
    fun arekHeatTestOf17OctoberKeepsTheChosenMode() {
        // 2026-10-17: Android said severe, then critical, with the battery at 40 to 45 °C, and the phone
        // recorded 4K · 60 for 55 min without a fault. Android's label alone no longer touches video.
        val p = ThermalPolicy()
        val readings = listOf(Thermal.MODERATE to 40.4f, Thermal.SEVERE to 40.7f, Thermal.CRITICAL to 44.9f)
        for ((status, battery) in readings) {
            assertEquals(ThermalAction.Keep, p.onReading(status, battery))
        }
        assertEquals(0, p.current)
    }

    @Test
    fun aBatteryAt60StopsVideo() {
        val p = ThermalPolicy()
        assertEquals(ThermalAction.Keep, p.onReading(Thermal.CRITICAL, 59.9f))
        assertEquals(ThermalAction.StopVideo, p.onReading(Thermal.NONE, StopBatteryC))
    }

    @Test
    fun androidsEmergencyOrShutdownStopsVideoWhateverTheBattery() {
        assertEquals(ThermalAction.StopVideo, ThermalPolicy().onReading(Thermal.EMERGENCY, 40f))
        assertEquals(ThermalAction.StopVideo, ThermalPolicy().onReading(6, null))
    }

    @Test
    fun withoutABatteryTemperatureCriticalStopsVideo() {
        assertEquals(ThermalAction.Keep, ThermalPolicy().onReading(Thermal.SEVERE, null))
        assertEquals(ThermalAction.StopVideo, ThermalPolicy().onReading(Thermal.CRITICAL, null))
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
