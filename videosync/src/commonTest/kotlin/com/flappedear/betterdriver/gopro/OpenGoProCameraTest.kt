package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.video.VideoSourceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Open GoPro start and stop the Android app and the iPhone share (FBBD-343), against a fake camera. */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenGoProCameraTest {
    private var sessionUs = 0L
    private val acks = mutableListOf<GoProAck>()
    private val saved = mutableListOf<GoProRecording>()

    /** 2025-10-09 10:53:20 in Warsaw, UTC+2 with summer time. */
    private val clock = object : PhoneClock {
        override fun nowMillis() = 1_760_000_000_000L

        override fun local(utcMillis: Long) = CameraDateTime(2025, 10, 9, 10, 53, 20)

        override fun utcOffsetMinutes(utcMillis: Long) = 120

        override fun daylightSaving(utcMillis: Long) = true
    }

    private fun TestScope.camera(link: FakeGoPro, video: GoProVideo? = null) =
        OpenGoProCamera("gopro-test", link, backgroundScope, clock, video, acks = { acks += it })

    private fun OpenGoProCamera.startNow() = start({ sessionUs }) { saved += it }

    @Test
    fun startsWithTheSessionKeepsAliveAndStops() = runTest {
        val link = FakeGoPro().apply { busyPolls = 2 }
        val camera = camera(link)
        sessionUs = 1_500_000
        camera.startNow()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(link.recording)
        assertEquals(VideoSourceState.Recording(1_500_000), camera.state.value)
        // Set Local Date Time, status polls until not busy, then Set Shutter on.
        assertEquals(listOf(0x0F, 0x13, 0x13, 0x13, 0x01), link.ids)
        // 10:53:20 at UTC+02:00 (0x0078) with summer time.
        val dateTime = link.requests[0].second.map { it.toInt() and 0xFF }
        assertEquals(listOf(0x0F, 10, 0x07, 0xE9, 10, 9, 10, 53, 20, 0x00, 0x78, 1), dateTime)

        advanceTimeBy(10_000)
        assertTrue(link.ids.count { it == 0x5B } >= 3)

        sessionUs = 600_000_000
        camera.stop()
        assertTrue(!link.recording)
        assertEquals(VideoSourceState.Idle, camera.state.value)
        val r = saved.last()
        assertEquals(1_500_000, r.startAckSessionUs)
        assertEquals(600_000_000, r.stopAckSessionUs)
        assertEquals(120, r.cameraUtcOffsetMinutes)
        assertEquals(listOf(GoProAck("start", true, 1_500_000), GoProAck("stop", true, 600_000_000)), acks)
    }

    @Test
    fun aCameraWithoutSetLocalDateTimeGetsSetDateTime() = runTest {
        val link = FakeGoPro().apply { refusedCommands = setOf(0x0F) }
        val camera = camera(link)
        camera.startNow()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(link.recording)
        assertEquals(listOf(0x0F, 0x0D, 0x13, 0x01), link.ids)
        val setDateTime = link.requests[1].second.map { it.toInt() and 0xFF }
        assertEquals(listOf(0x0D, 7, 0x07, 0xE9, 10, 9, 10, 53, 20), setDateTime)
        camera.stop()
        assertEquals(120, saved.last().cameraUtcOffsetMinutes)
    }

    @Test
    fun noCameraFailsWithoutThrowing() = runTest {
        val link = FakeGoPro().apply { reachable = false }
        val camera = camera(link)
        camera.startNow()
        runCurrent()
        assertIs<VideoSourceState.Failed>(camera.state.value)
        camera.stop()
        assertIs<VideoSourceState.Failed>(camera.state.value)
        assertEquals(listOf(GoProAck("start", false, 0)), acks)
        assertEquals(emptyList(), saved)
    }

    @Test
    fun aDroppedLinkIsReconnectedSoStopStillReachesTheCamera() = runTest {
        val link = FakeGoPro()
        val camera = camera(link)
        camera.startNow()
        runCurrent()
        link.reachable = false
        advanceTimeBy(7_000)
        link.reachable = true
        advanceTimeBy(4_000)
        assertTrue(link.connects >= 2)
        camera.stop()
        assertTrue(!link.recording)
        assertEquals(VideoSourceState.Idle, camera.state.value)
    }

    @Test
    fun setsResolutionThenFrameRateBeforeTheShutter() = runTest {
        val link = FakeGoPro()
        val video = GoProVideo(OpenGoPro.Setting.RES_1080, OpenGoPro.Setting.FPS_60, "1080p · 60", "1080p")
        val camera = camera(link, video)
        camera.startNow()
        runCurrent()
        assertEquals(listOf(0x0F, 0x13, 0x02, 0x03, 0x13, 0x01), link.ids)
        camera.stop()
        assertEquals("1080p · 60", saved.last().videoMode)
    }

    @Test
    fun recordingsKeepOnePerCameraAndStart() {
        val a = GoProRecording("gopro-a", startAckSessionUs = 1, startAckUtcMillis = 10)
        val b = GoProRecording("gopro-b", startAckSessionUs = 1, startAckUtcMillis = 10)
        val stopped = a.copy(stopAckSessionUs = 5)
        val all = GoProRecordingsJson.put(GoProRecordingsJson.put(listOf(a), b), stopped)
        assertEquals(listOf(b, stopped), GoProRecordingsJson.decode(GoProRecordingsJson.encode(all)))
        assertEquals(emptyList(), GoProRecordingsJson.decode("not json"))
    }
}
