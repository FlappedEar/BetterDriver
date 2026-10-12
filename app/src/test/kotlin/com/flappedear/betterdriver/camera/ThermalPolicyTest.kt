package com.flappedear.betterdriver.camera

import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.setup.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalPolicyTest {
    private val minute = 60_000_000L

    private fun ladder(size: VideoSize, fps: Int) = ladderFrom(VideoMode(size, fps)).map { it.label }

    @Test
    fun ladderTriesThirtyAtEachSizeBeforeTheNextSize() {
        assertEquals(
            listOf("4K · 60", "4K · 30", "1080p · 60", "1080p · 30", "720p · 60", "720p · 30"),
            ladder(VideoSize.UHD, 60),
        )
    }

    @Test
    fun ladderStartsAtTheChosenModeAndNeverGoesAbove() {
        assertEquals(listOf("1080p · 60", "1080p · 30", "720p · 60", "720p · 30"), ladder(VideoSize.FHD, 60))
        assertEquals(listOf("4K · 30", "1080p · 30", "720p · 30"), ladder(VideoSize.UHD, 30))
        assertEquals(listOf("720p · 30"), ladder(VideoSize.HD, 30))
    }

    @Test
    fun aGoProOnlySizeStartsThePhoneAt1080p() {
        assertEquals(listOf("1080p · 60", "1080p · 30", "720p · 60", "720p · 30"), ladder(VideoSize.QHD, 60))
    }

    @Test
    fun coolPhoneKeepsQuality() {
        val p = ThermalPolicy(4)
        assertEquals(ThermalAction.Keep, p.onReading(0, Thermal.LIGHT, 0.5f))
        assertEquals(0, p.current)
    }

    @Test
    fun moderateStepsDownOncePerInterval() {
        val p = ThermalPolicy(4, minStepMicros = minute)
        assertEquals(ThermalAction.StepDown(1), p.onReading(0, Thermal.MODERATE, null))
        assertEquals(ThermalAction.Keep, p.onReading(minute / 2, Thermal.MODERATE, null))
        assertEquals(ThermalAction.StepDown(2), p.onReading(minute, Thermal.MODERATE, null))
    }

    @Test
    fun headroomForecastStepsDownBeforeThrottling() {
        val p = ThermalPolicy(4)
        assertEquals(ThermalAction.StepDown(1), p.onReading(0, Thermal.NONE, 0.95f))
    }

    @Test
    fun severeStepsDownAtOnce() {
        val p = ThermalPolicy(4, minStepMicros = minute)
        p.onReading(0, Thermal.MODERATE, null)
        assertEquals(ThermalAction.StepDown(2), p.onReading(1, Thermal.SEVERE, null))
    }

    @Test
    fun criticalOrHotAtTheLastStepStopsVideo() {
        assertEquals(ThermalAction.StopVideo, ThermalPolicy(4).onReading(0, Thermal.CRITICAL, null))
        val p = ThermalPolicy(2, minStepMicros = 0)
        assertEquals(ThermalAction.StepDown(1), p.onReading(0, Thermal.MODERATE, null))
        assertEquals(ThermalAction.StopVideo, p.onReading(1, Thermal.MODERATE, null))
    }

    @Test
    fun hotAtTheLastStepStopsWithoutWaitingForTheInterval() {
        val p = ThermalPolicy(2, minStepMicros = minute)
        assertEquals(ThermalAction.StepDown(1), p.onReading(0, Thermal.MODERATE, null))
        p.moveTo(1)
        assertEquals(ThermalAction.StopVideo, p.onReading(minute / 12, Thermal.MODERATE, null))
    }

    @Test
    fun moveToStartsFromTheGivenStep() {
        val p = ThermalPolicy(4)
        p.moveTo(2)
        assertEquals(ThermalAction.StepDown(3), p.onReading(0, Thermal.SEVERE, null))
    }

    @Test
    fun rebindingAfterAStepDownKeepsTheInterval() {
        val p = ThermalPolicy(4, minStepMicros = minute)
        assertEquals(ThermalAction.StepDown(1), p.onReading(0, Thermal.MODERATE, null))
        // The camera rebinds at the new step (PhoneCamera.begin) before the next 5 s check.
        p.moveTo(1)
        assertEquals(ThermalAction.Keep, p.onReading(minute / 12, Thermal.MODERATE, null))
        assertEquals(ThermalAction.StepDown(2), p.onReading(minute, Thermal.MODERATE, null))
    }
}
