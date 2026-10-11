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
class Hero11SourceTest {
    @TempDir
    lateinit var dir: File

    private var sessionUs = 0L
    private fun TestScope.context() = VideoSessionContext("s1", 1_760_000_000_000L, File(dir, "video")) { sessionUs }

    private fun TestScope.source(camera: FakeGoPro) = Hero11Source(
        "gopro-test",
        camera,
        backgroundScope,
        clock = Clock.fixed(Instant.ofEpochMilli(1_760_000_000_000L), ZoneId.of("Europe/Warsaw")),
    )

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
}
