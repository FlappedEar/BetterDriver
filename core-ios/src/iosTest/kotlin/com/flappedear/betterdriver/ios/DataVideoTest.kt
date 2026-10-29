@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.overlay.CarFrame
import com.flappedear.betterdriver.timing.overlay.GForce
import com.flappedear.betterdriver.timing.overlay.HudWidget
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
        val cuts = session.cuts(DataVideoSession.WholeVideo, durations)
        assertEquals(1, cuts.size)
        assertEquals(session.paths[0], cuts[0].path)
        assertEquals(0L, cuts[0].fromMicros)
        assertEquals(8_000_000L, cuts[0].toMicros)
        assertEquals(4_000_000L, cuts[0].sessionStartMicros)
        val now = session.moment(6_000_000)
        assertTrue(now.speed.all { it.isDigit() }, now.speed)
        assertEquals("LAP", now.lapCaption)
        assertEquals("—", now.lapNumber)
        // No laps: no delta.
        assertNull(now.deltaText)
        assertNotNull(now.position)
        assertTrue(now.trail.isNotEmpty(), "the last 30 s on the map")
        assertTrue(now.radar.isEmpty() || now.radar.all { it.fade in 0f..1f })
        // The Tech HUD on a 1080p picture (FBBD-347): a phone-only drive has the gauge, the lap, both G
        // widgets and the map, in two strips.
        val hud = session.hud(1920, 1080)
        assertEquals(1f, hud.scale)
        assertEquals(
            listOf(HudWidget.Tachometer, HudWidget.Lap, HudWidget.GRadar, HudWidget.GReadout, HudWidget.TrackMap),
            hud.places.map { it.widget },
        )
        assertEquals(2, hud.strips.size)
        assertTrue(session.outline.size > 1)
        assertEquals("BetterDriver-$id-video.mp4", session.fileName(DataVideoSession.WholeVideo))
    }

    @Test
    fun aSessionWithoutVideoHasNone() {
        assertNull(DataVideo("$base/sessions", "$base/tracks").open(record(video = false)))
    }

    @Test
    fun theLapTileReadsAsOverlays() {
        val lap = OverlayLap(3, LapKind.Timed, 65_432_000, -120_000, isBest = false)
        val car = CarFrame(rpm = 6123.4, throttlePct = 87.6, oilC = 131.0, tyreTemps = listOf(80.0, null, null, null))
        val m = HudMoment(OverlayFrame(123.6, lap, null, GForce(0.3, -1.2), car), emptyList(), emptyList(), 83_456_400, false)
        assertEquals("124", m.speed)
        assertEquals("6123 RPM", m.rpmText)
        assertEquals("3", m.lapNumber)
        assertEquals("1:05.432", m.lapTimeText)
        assertEquals("1:23.456", m.bestText)
        assertEquals("-0.120", m.deltaText)
        assertTrue(m.deltaAhead)
        assertEquals("LAP 3", m.mapLap)
        assertEquals("88%", m.throttleText)
        assertEquals("—", m.brakeText)
        assertEquals("1.24", m.gText)
        assertEquals("131", m.rowText(HudWidget.Oil))
        assertTrue(m.rowHot(HudWidget.Oil), "over the template's 128 °C warning")
        assertEquals("80", m.tyreTempText(0))
        assertEquals("—", m.tyreTempText(1))
        val finished = HudMoment(
            OverlayFrame(null, lap.copy(phase = LapPhase.Finished), null, null), emptyList(), emptyList(), null, true,
        )
        assertTrue(finished.lapFinished)
        assertEquals("RUN 3", finished.mapLap)
        assertEquals("18 min of video", wholeVideoText(18 * 60_000_000L))
    }
}
