package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.video.VideoSessionContext
import com.flappedear.betterdriver.video.VideoSourceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OpenGoProSourceTest {
    @TempDir
    lateinit var dir: File

    private var sessionUs = 0L
    private fun TestScope.context() = VideoSessionContext("s1", 1_760_000_000_000L, File(dir, "video")) { sessionUs }

    private fun TestScope.source(camera: FakeGoPro, video: GoProVideo? = null) = OpenGoProSource(
        "gopro-test",
        camera,
        backgroundScope,
        clock = Clock.fixed(Instant.ofEpochMilli(1_760_000_000_000L), ZoneId.of("Europe/Warsaw")),
        video = video,
    )

    private val fhd60 = GoProVideo(OpenGoPro.Setting.RES_1080, OpenGoPro.Setting.FPS_60, "1080p · 60", "1080p")

    @Test
    fun `a camera without Set Local Date Time gets Set Date Time`() = runTest {
        val camera = FakeGoPro().apply { refusedCommands = setOf(0x0F) }
        val source = source(camera)
        source.start(context())
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(camera.recording)
        assertEquals(listOf(0x0F, 0x0D, 0x13, 0x01), camera.ids)
        // 2025-10-09 10:53:20 in Warsaw (UTC+2), without the zone.
        val dateTime = camera.requests[1].second.map { it.toInt() and 0xFF }
        assertEquals(listOf(0x0D, 7, 0x07, 0xE9, 10, 9, 10, 53, 20), dateTime)
        source.stop()
        assertEquals(120, GoProRecordings(File(dir, "video")).all().single().cameraUtcOffsetMinutes)
    }

    @Test
    fun `starts with the session, keeps alive and stops`() = runTest {
        val camera = FakeGoPro().apply { busyPolls = 2 }
        val source = source(camera)
        sessionUs = 1_500_000
        source.start(context())
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(camera.recording)
        assertEquals(VideoSourceState.Recording(1_500_000), source.state.value)
        // Set Local Date Time, status polls until not busy, then Set Shutter on.
        assertEquals(listOf(0x0F, 0x13, 0x13, 0x13, 0x01), camera.ids)

        advanceTimeBy(10_000)
        assertTrue(camera.ids.count { it == 0x5B } >= 3)

        sessionUs = 600_000_000
        assertEquals(emptyList(), source.stop())
        assertTrue(!camera.recording)
        assertEquals(VideoSourceState.Idle, source.state.value)
        val r = GoProRecordings(File(dir, "video")).all().single()
        assertEquals(1_500_000, r.startAckSessionUs)
        assertEquals(600_000_000, r.stopAckSessionUs)
        assertEquals(120, r.cameraUtcOffsetMinutes) // Warsaw in October: UTC+2.
    }

    @Test
    fun `no camera fails the source without throwing`() = runTest {
        val camera = FakeGoPro().apply { reachable = false }
        val source = source(camera)
        source.start(context())
        runCurrent()
        assertIs<VideoSourceState.Failed>(source.state.value)
        assertEquals(emptyList(), source.stop())
        assertIs<VideoSourceState.Failed>(source.state.value)
    }

    @Test
    fun `a refused shutter fails the source`() = runTest {
        val camera = FakeGoPro().apply { failShutter = true }
        val source = source(camera)
        source.start(context())
        runCurrent()
        assertIs<VideoSourceState.Failed>(source.state.value)
        assertTrue(GoProRecordings(File(dir, "video")).all().isEmpty())
    }

    @Test
    fun `a dropped link is reconnected so stop still reaches the camera`() = runTest {
        val camera = FakeGoPro()
        val source = source(camera)
        source.start(context())
        runCurrent()
        camera.reachable = false
        advanceTimeBy(7_000)
        camera.reachable = true
        advanceTimeBy(4_000)
        assertTrue(camera.connects >= 2)
        source.stop()
        assertTrue(!camera.recording)
        assertEquals(VideoSourceState.Idle, source.state.value)
    }

    @Test
    fun `a camera that cannot be reached at stop is reported`() = runTest {
        val camera = FakeGoPro()
        val source = source(camera)
        source.start(context())
        runCurrent()
        camera.reachable = false
        source.stop()
        assertIs<VideoSourceState.Failed>(source.state.value)
    }

    @Test
    fun `sets resolution then frame rate before the shutter`() = runTest {
        val camera = FakeGoPro()
        val source = source(camera, fhd60)
        source.start(context())
        runCurrent()
        assertTrue(camera.recording)
        // Clock, ready, resolution (2), frame rate (3), ready again, shutter.
        assertEquals(listOf(0x0F, 0x13, 0x02, 0x03, 0x13, 0x01), camera.ids)
        assertEquals(mapOf(2 to 9, 3 to 5), camera.settings)
        source.stop()
        assertEquals("1080p · 60", GoProRecordings(File(dir, "video")).all().single().videoMode)
    }

    @Test
    fun `a refused resolution still records, in the camera's own mode`() = runTest {
        val camera = FakeGoPro().apply { refusedSettings = setOf(OpenGoPro.Setting.VIDEO_RESOLUTION) }
        val source = source(camera, fhd60)
        source.start(context())
        runCurrent()
        assertTrue(camera.recording)
        assertIs<VideoSourceState.Recording>(source.state.value)
        source.stop()
        assertEquals(null, GoProRecordings(File(dir, "video")).all().single().videoMode)
    }

    @Test
    fun `a refused frame rate records the size that was taken`() = runTest {
        val camera = FakeGoPro().apply { refusedSettings = setOf(OpenGoPro.Setting.FRAMES_PER_SECOND) }
        val source = source(camera, fhd60)
        source.start(context())
        runCurrent()
        assertTrue(camera.recording)
        source.stop()
        assertEquals("1080p · camera's frame rate", GoProRecordings(File(dir, "video")).all().single().videoMode)
    }
}
