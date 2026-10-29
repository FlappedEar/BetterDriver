@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.overlay.LapPhase
import com.flappedear.betterdriver.timing.overlay.OverlayFrame
import com.flappedear.betterdriver.timing.overlay.OverlayLap
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Video with data on the iPhone (FBBD-152, FBBD-333): a session's video with the overlay, also without laps. */
class DataVideoTest {
    private val base = NSTemporaryDirectory() + "data-video-test-" + NSUUID().UUIDString
    private val gnss = FakeGnss()

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    private fun box(type: String, body: Int): ByteArray {
        val size = 8 + body
        return byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) +
            type.encodeToByteArray() + ByteArray(body)
    }

    private fun write(path: String, bytes: ByteArray) {
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        data.writeToFile(path, true)
    }

    /** 20 s at 25 Hz away from any saved track, with [video] phone video from 4 s. */
    private fun record(video: Boolean): String {
        val session = LiveSession.start(
            "$base/sessions", "$base/tracks", CircuitList.Empty,
            SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL), receiverHasG = false,
        )
        if (video) {
            val log = PhoneVideoLog(session)
            NSFileManager.defaultManager.createDirectoryAtPath(log.videoDir, true, null, null)
            write("${log.videoDir}/${log.fileName(1)}", box("ftyp", 12) + box("mdat", 100) + box("moov", 20))
            log.started(log.fileName(1), 4_000_000, 60)
        }
        for (n in 0L..500) session.submit(SampleSet(n * 40_000, gnss.fix(n)))
        session.stop()
        return session.id
    }

    @Test
    fun aSessionWithoutLapsPlaysAllItsVideoWithSpeed() {
        val id = record(video = true)
        val session = assertNotNull(DataVideo("$base/sessions", "$base/tracks").open(id))
        assertEquals(1, session.paths.size)
        val durations = mapOf(session.paths[0] to 8_000_000L)
        val parts = session.parts(durations)
        // No saved track: only the whole video, no laps.
        assertEquals(1, parts.size)
        assertEquals("Whole session", parts[0].name)
        assertEquals("8 s of video", parts[0].note)
        assertTrue(parts[0].enabled)
        val cuts = session.cuts(DataVideoSession.WholeVideo, durations, 6)
        assertEquals(1, cuts.size)
        assertEquals(session.paths[0], cuts[0].path)
        assertEquals(0L, cuts[0].fromMicros)
        assertEquals(8_000_000L, cuts[0].toMicros)
        assertEquals(4_000_000L, cuts[0].sessionStartMicros)
        val text = session.text(6_000_000, DataVideoSession.WholeVideo)
        assertTrue(text.speed.all { it.isDigit() }, text.speed)
        assertEquals("LAP", text.lapCaption)
        assertEquals("—", text.lapValue)
        // No laps: the delta slot stays empty.
        assertNull(text.deltaValue)
        assertNotNull(text.position)
        // The band on a 1080p picture, as Android lays it out: a fifth of the height, the map inside it.
        val band = session.band(1920, 1080)
        assertEquals(1920, band.width)
        assertEquals(216, band.height)
        assertTrue(band.track.isNotEmpty())
        assertTrue(band.track.all { it.x in 0f..1920f && it.y in 0f..216f })
        assertEquals("BetterDriver-$id-video.mp4", session.fileName(DataVideoSession.WholeVideo))
    }

    @Test
    fun aSessionWithoutVideoHasNone() {
        assertNull(DataVideo("$base/sessions", "$base/tracks").open(record(video = false)))
    }

    @Test
    fun aLapAheadOfTheBestIsGreen() {
        val lap = OverlayLap(3, LapKind.Timed, 65_432_000, -120_000, isBest = false)
        val text = overlayText(OverlayFrame(123.6, lap, null, null), pointToPoint = false)
        assertEquals("124", text.speed)
        assertEquals("LAP 3", text.lapCaption)
        assertEquals("1:05.4", text.lapValue)
        assertEquals(OverlayInk.Gain, text.deltaInk)
        val best = overlayText(OverlayFrame(null, lap.copy(isBest = true), null, null), pointToPoint = false)
        assertEquals(OverlayInk.Best, best.lapCaptionInk)
        assertEquals("LAP 3 · BEST", best.lapCaption)
        assertEquals("RUN 3", overlayText(OverlayFrame(null, lap, null, null), pointToPoint = true).lapCaption)
        // A single-lap video after the line keeps the final time in the gain colour (FBBD-346).
        val held = overlayText(OverlayFrame(null, lap.copy(phase = LapPhase.Finished), null, null), pointToPoint = false)
        assertEquals(OverlayInk.Gain, held.lapValueInk)
        assertEquals(OverlayInk.Text, text.lapValueInk)
        assertEquals("18 min of video", wholeVideoText(18 * 60_000_000L))
    }
}
