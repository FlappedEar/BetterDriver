@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.timing.circuits.CircuitList
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The iPhone camera's files are logged so that review and export place them as Android's (FBBD-21). */
class PhoneVideoLogTest {
    private val base = NSTemporaryDirectory() + "phone-video-test-" + NSUUID().UUIDString

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    @Test
    fun aFileIsPlacedByItsFrameTimes() {
        val session = LiveSession.start(
            "$base/sessions", "$base/tracks", CircuitList.Empty,
            SessionInfo(1_700_000_000_000, channels = GnssChannels.ALL), receiverHasG = false,
        )
        val log = PhoneVideoLog(session)
        val file = log.fileName(1)
        assertEquals("phone_0001.mp4", file)
        assertTrue(log.videoDir.endsWith("/${session.id}/video"))
        log.started(file, 1_000_000, 60)
        // Media 0 is at 1.000 s: each frame time less its media time says so.
        log.synced(file, 2_050_000, 1_000_000, 2_000_000)
        log.synced(file, 3_040_000, 2_000_000, 3_000_000)
        log.synced(file, 4_030_000, 3_000_000, 4_000_000)
        log.stopped(file, "user")
        session.stop()

        val events = SessionStore("$base/sessions").read(session.id).events
        assertEquals("camera=phone file=phone_0001.mp4 fps=60", events.first { it.kind == "video_start" }.text)
        val clip = VideoTimeline.from(events).clips(CameraIds.PHONE).single()
        assertEquals(file, clip.file)
        assertEquals(1_000_000L, clip.startMicros)
    }
}
