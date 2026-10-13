package com.flappedear.betterdriver.camera

import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.setup.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalPolicyTest {
    private val minute = 60_000_000L

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
    fun coolPhoneKeepsQuality() {
        val p = ThermalPolicy(4)
        assertEquals(ThermalAction.Keep, p.onReading(0, Thermal.LIGHT, 0.5f))
        assertEquals(0, p.current)
    }

    @Test
    fun theDefaultIntervalLetsThePhoneCoolBeforeTheNextStep() {
        // Arek's drive 2026-10-12 (FBBD-147): hot for three minutes after the first step at 60 s
        // intervals dropped 4K · 60 to 720p · 60. Now the second step waits three minutes.
        val p = ThermalPolicy(6)
        assertEquals(ThermalAction.StepDown(1), p.onReading(0, Thermal.MODERATE, null))
        assertEquals(ThermalAction.Keep, p.onReading(2 * minute, Thermal.MODERATE, null))
        assertEquals(ThermalAction.StepDown(2), p.onReading(3 * minute, Thermal.MODERATE, null))
    }

    @Test
    fun stepsBackUpAfterFiveCoolMinutes() {
        val p = ThermalPolicy(6, settleMicros = minute, recoverMicros = 5 * minute)
        p.onReading(0, Thermal.MODERATE, null)
        p.onReading(minute, Thermal.MODERATE, null)
        assertEquals(2, p.current)
        // Still warm (light, but headroom high) until minute 3: the five cool minutes start there.
        assertEquals(ThermalAction.Keep, p.onReading(3 * minute, Thermal.LIGHT, 0.8f))
        assertEquals(ThermalAction.Keep, p.onReading(7 * minute, Thermal.LIGHT, 0.5f))
        assertEquals(ThermalAction.StepUp(1), p.onReading(8 * minute, Thermal.LIGHT, 0.5f))
        assertEquals(ThermalAction.Keep, p.onReading(12 * minute, Thermal.NONE, null))
        assertEquals(ThermalAction.StepUp(0), p.onReading(13 * minute, Thermal.NONE, null))
        assertEquals(ThermalAction.Keep, p.onReading(30 * minute, Thermal.NONE, null))
    }

    @Test
    fun aStepThatHeatsUpAgainIsNotTriedTwice() {
        val p = ThermalPolicy(6, settleMicros = minute, recoverMicros = 5 * minute)
        p.onReading(0, Thermal.MODERATE, null)
        assertEquals(ThermalAction.StepUp(0), p.onReading(5 * minute, Thermal.NONE, null))
        assertEquals(ThermalAction.StepDown(1), p.onReading(6 * minute, Thermal.MODERATE, null))
        assertEquals(1, p.ceiling)
        assertEquals(ThermalAction.Keep, p.onReading(20 * minute, Thermal.NONE, null))
        assertEquals(1, p.current)
    }

    @Test
    fun aStepThatFailedToBindIsNeverSteppedUpTo() {
        val p = ThermalPolicy(6, settleMicros = minute, recoverMicros = 5 * minute)
        p.failed(0)
        p.moveTo(1)
        p.onReading(0, Thermal.MODERATE, null)
        assertEquals(ThermalAction.StepUp(1), p.onReading(5 * minute, Thermal.NONE, null))
        assertEquals(ThermalAction.Keep, p.onReading(20 * minute, Thermal.NONE, null))
    }

    @Test
    fun moderateStepsDownOncePerInterval() {
        val p = ThermalPolicy(4, settleMicros = minute)
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
        val p = ThermalPolicy(4, settleMicros = minute)
        p.onReading(0, Thermal.MODERATE, null)
        assertEquals(ThermalAction.StepDown(2), p.onReading(1, Thermal.SEVERE, null))
    }

    @Test
    fun criticalOrHotAtTheLastStepStopsVideo() {
        assertEquals(ThermalAction.StopVideo, ThermalPolicy(4).onReading(0, Thermal.CRITICAL, null))
        val p = ThermalPolicy(2, settleMicros = 0)
        assertEquals(ThermalAction.StepDown(1), p.onReading(0, Thermal.MODERATE, null))
        assertEquals(ThermalAction.StopVideo, p.onReading(1, Thermal.MODERATE, null))
    }

    @Test
    fun hotAtTheLastStepStopsWithoutWaitingForTheInterval() {
        val p = ThermalPolicy(2, settleMicros = minute)
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
        val p = ThermalPolicy(4, settleMicros = minute)
        assertEquals(ThermalAction.StepDown(1), p.onReading(0, Thermal.MODERATE, null))
        // The camera rebinds at the new step (PhoneCamera.begin) before the next 5 s check.
        p.moveTo(1)
        assertEquals(ThermalAction.Keep, p.onReading(minute / 12, Thermal.MODERATE, null))
        assertEquals(ThermalAction.StepDown(2), p.onReading(minute, Thermal.MODERATE, null))
    }
}
