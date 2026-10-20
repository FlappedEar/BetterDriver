package com.flappedear.betterdriver.camera

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WideModeTest {
    @Test
    fun pixel6SizesGive2688x1512() {
        // Arek's Pixel 6, log of 2026-10-19 (FBBD-239): the back camera's encoder sizes at 60 fps.
        val pixel6 = listOf(
            4032 to 2268, 3840 to 2160, 3264 to 1836, 2688 to 1512, 1920 to 1080, 1792 to 1008, 1280 to 720,
        )
        assertEquals(2688 to 1512, wideSize(pixel6))
    }

    @Test
    fun onlySixteenByNineBetween1080pAnd4K() {
        assertNull(wideSize(listOf(3840 to 2160, 1920 to 1080, 2560 to 1920, 2688 to 1500)))
        assertEquals(2560 to 1440, wideSize(listOf(2560 to 1440)))
    }

    @Test
    fun nearestToGoPro27kWins() {
        assertEquals(2688 to 1512, wideSize(listOf(3264 to 1836, 2560 to 1440, 2688 to 1512)))
    }

    @Test
    fun landscapeBackCameraNeedsNoRotation() {
        assertEquals(0, orientationHint(sensorDegrees = 90, displayDegrees = 90))
        assertEquals(90, orientationHint(sensorDegrees = 90, displayDegrees = 0))
        assertEquals(180, orientationHint(sensorDegrees = 90, displayDegrees = 270))
    }

    @Test
    fun bitrateScalesByPixelsAndFrameRate() {
        // A 4K · 30 profile at 48 Mbit/s: 2688×1512 is 49 % of the pixels, at twice the rate.
        assertEquals(47_040_000, scaledBitrate(48_000_000, 3840, 2160, 30, 2688, 1512, 60))
        assertEquals(1_000_000, scaledBitrate(10, 3840, 2160, 30, 1280, 720, 30))
    }

    @Test
    fun aeRangeIsOneTheCameraAdvertises() {
        assertEquals(60 to 60, aeRange(listOf(15 to 30, 30 to 30, 15 to 60, 60 to 60), 60))
        assertEquals(30 to 60, aeRange(listOf(15 to 30, 15 to 60, 30 to 60), 60))
        assertNull(aeRange(listOf(15 to 30, 30 to 30), 60))
    }

    @Test
    fun mediaTimeRunsFromTheFirstFrame() {
        assertNull(mediaOf(0, 5_000_000))
        assertEquals(1_000_000L, mediaOf(2_000_000_000, 3_000_000_000))
    }

    @Test
    fun fpsLineSaysTheRateRecorded() {
        assertEquals("61 frames in 1.0 s = 60.0 fps", fpsLine(61, 1_000_000))
        assertEquals("0 frames in 0.0 s = 0.0 fps", fpsLine(0, 0))
    }

    @Test
    fun theEncoderIsAskedForTwiceTheFrameRateFirst() {
        // FBBD-246: room above 60 fps for bursts, then the rate itself, then the codec's own choice.
        assertEquals(listOf(120, 60, null), operatingRates(60))
    }

    @Test
    fun hevcOnlyWhenH264IsKnownNotToKeepUp() {
        val avc = MediaFormat.MIMETYPE_VIDEO_AVC
        val hevc = MediaFormat.MIMETYPE_VIDEO_HEVC
        assertEquals(hevc, pickMime(avc = false, hevc = true))
        assertEquals(avc, pickMime(avc = true, hevc = true))
        assertEquals(avc, pickMime(avc = null, hevc = true))
        assertEquals(avc, pickMime(avc = false, hevc = false))
        assertEquals(avc, pickMime(avc = false, hevc = null))
    }

    @Test
    fun relayedFramesGoToTheEncoderOnTheMonotonicClock() {
        // The phone slept 5 h since boot: boot time is that far ahead of the monotonic clock.
        val mono = 300_000_000_000L
        val boot = mono + 18_000_000_000_000L
        assertEquals("a boot-time frame 120 ms old", mono - 120_000_000, monotonic(boot - 120_000_000, boot, mono))
        assertEquals("a monotonic frame stays", mono - 120_000_000, monotonic(mono - 120_000_000, boot, mono))
        assertEquals("never slept: either clock", mono - 5, monotonic(mono - 5, mono, mono))
    }

    @Test
    fun aRelayedFrameShowsItsClockByMatchingTheCamerasOwnTimes() {
        val shift = 100_000_000L
        val sensor = setOf(5_000_000_000L)
        // Monotonic sensor: nothing to move.
        assertEquals(0L, frameShift(5_000_000_000L, 0, sensor::contains))
        // The reader got the sensor's boot time as it is: take the shift off.
        assertEquals(shift, frameShift(5_000_000_000L, shift, sensor::contains))
        // The camera already moved it to monotonic, even with the clocks only 100 ms apart.
        assertEquals(0L, frameShift(5_000_000_000L - shift, shift, sensor::contains))
        // A frame whose capture result has not come yet decides nothing.
        assertNull(frameShift(7_000_000_000L, shift, sensor::contains))
    }

    @Test
    fun skippedSensorFramesAreCountedFromTheGap() {
        // Arek's Pixel 6 at 2.7K·60 (2026-10-19): a 16.814 ms sensor frame time.
        val frame = 16_814_000L
        assertEquals(0L, framesSkipped(frame, frame))
        assertEquals(1L, framesSkipped(2 * frame + 300_000, frame))
        assertEquals(3L, framesSkipped(4 * frame, frame))
        assertEquals(0L, framesSkipped(frame - 2_000_000, frame))
        assertEquals(0L, framesSkipped(2 * frame, 0))
    }
}
