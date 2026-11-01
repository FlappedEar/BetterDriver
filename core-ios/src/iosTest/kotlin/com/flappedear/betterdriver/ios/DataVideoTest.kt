@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.optimal.OptimalOverlayText
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
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // No saved track: the whole video, no laps; the composite laps are listed but cannot be made (FBBD-370).
        assertEquals(3, parts.size)
        val composites = listOf(DataVideoSession.RealisticOptimal, DataVideoSession.RawTheoretical)
        assertEquals(composites, parts.drop(1).map { it.part })
        assertTrue(parts.drop(1).all { it.composite && !it.enabled })
        assertEquals("No raw theoretical best: the session has no counted laps at a saved track.", parts[2].note)
        assertEquals("Whole session", parts[0].name)
        assertEquals("8 s of video", parts[0].note)
        assertTrue(parts[0].enabled)
        val cuts = session.cuts(DataVideoSession.WholeVideo, durations, 6)
        assertEquals(1, cuts.size)
        assertEquals(session.paths[0], cuts[0].path)
        assertEquals(0L, cuts[0].fromMicros)
        assertEquals(8_000_000L, cuts[0].toMicros)
        assertEquals(4_000_000L, cuts[0].sessionStartMicros)
        val now = session.moment(6_000_000, DataVideoSession.WholeVideo)
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
        val frame = OverlayFrame(123.6, lap, null, GForce(0.3, -1.2), car)
        val m = HudMoment(frame, emptyList(), emptyList(), 83_456_400, false)
        assertEquals("124", m.speed)
        assertFalse(m.gears, "no gearbox in this frame: the speed alone, no RPM number (UI-310 A)")
        assertEquals("—", m.gear)
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
        val inGear = frame.copy(car = car.copy(gear = 2, gears = true))
        val geared = HudMoment(inGear, emptyList(), emptyList(), null, false)
        assertTrue(geared.gears)
        assertEquals("2", geared.gear)
        assertTrue(geared.gearKnown)
        val finished = HudMoment(
            OverlayFrame(null, lap.copy(phase = LapPhase.Finished), null, null), emptyList(), emptyList(), null, true,
        )
        assertTrue(finished.lapFinished)
        assertEquals("RUN 3", finished.mapLap)
        assertEquals("18 min of video", wholeVideoText(18 * 60_000_000L))
    }

    /**
     * The composite laps on the iPhone (FBBD-370), as Android's Video tab offers them: the built-in
     * Jastrząb demo, all of it on video, offers the realistic optimal and the raw theoretical best after
     * the whole session; each is cut from the laps its parts come from, its tile names the lap.
     */
    @Test
    fun theCompositeLapsAreOfferedAndCutAsOnAndroid() {
        val library = ReviewLibrary(base)
        val id = library.addDemo(
            "$DemoAssetsDir/jastrzab.replay", "$DemoAssetsDir/jastrzab.track.json", DemoStartUtcMillis,
        )
        val dir = "${library.sessionsRoot}/$id/video"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        write("$dir/phone_all.mp4", box("ftyp", 12) + box("mdat", 100) + box("moov", 20))
        val index = """{"version":1,"clips":[{"fileName":"phone_all.mp4","kind":"PHONE","sourceId":"phone",""" +
            """"sessionOffsetUs":0,"alignment":{"method":"CAPTURE_CLOCK","uncertaintyUs":0},""" +
            """"durationUs":$AllDayMicros,"status":"LOCAL"}]}"""
        NSString.create(string = index).writeToFile("$dir/clips.json", true, NSUTF8StringEncoding, null)

        val session = assertNotNull(DataVideo(library.sessionsRoot, "$base/tracks").open(id))
        val durations = mapOf(session.paths.single() to AllDayMicros)
        val parts = session.parts(durations)
        assertEquals(
            listOf(DataVideoSession.WholeVideo, DataVideoSession.RealisticOptimal, DataVideoSession.RawTheoretical),
            parts.take(3).map { it.part },
        )
        val (realistic, raw) = parts[1] to parts[2]
        assertTrue(realistic.composite && raw.composite)
        assertFalse(parts[0].composite || parts[3].composite)
        assertTrue(realistic.name.startsWith("Realistic optimal"), realistic.name)
        assertTrue(raw.name.startsWith("Raw theoretical best · 1:"), raw.name)
        assertTrue(raw.enabled, raw.note)
        assertTrue(raw.note.startsWith("Fastest of each corner from laps "), raw.note)
        assertTrue(DataVideoSession.isComposite(DataVideoSession.RawTheoretical))
        assertFalse(DataVideoSession.isComposite(DataVideoSession.WholeVideo) || DataVideoSession.isComposite(3))
        assertEquals("BetterDriver-$id-realistic-optimal.mp4", session.fileName(DataVideoSession.RealisticOptimal))
        assertEquals("BetterDriver-$id-raw-theoretical.mp4", session.fileName(DataVideoSession.RawTheoretical))

        // The pieces follow each other in the film and come from the laps the parts were driven on.
        val cuts = session.cuts(DataVideoSession.RawTheoretical, durations, 6)
        assertTrue(cuts.size > 1)
        var at = 0L
        for (c in cuts) {
            assertEquals(at, c.outputStartMicros)
            at += c.toMicros - c.fromMicros
        }
        // A composite lap's film is about one lap long (the raw theoretical best, 108.2 s), whatever the handle.
        assertTrue(at in 100_000_000L..115_000_000L, "film $at µs")
        val first = session.compositeMoment(cuts[0].sessionStartMicros, DataVideoSession.RawTheoretical, 0)
        assertTrue(first.composite)
        assertTrue(first.captions.first().startsWith("RAW THEORETICAL · LAP "), first.captions.first())
        assertTrue(first.captions.last().startsWith("LAP "), first.captions.last())
        assertEquals("0:00.000", first.lapTimeText)
        assertFalse(first.lapFinished)
        // The whole session's tile is still the plain lap's.
        val plain = session.moment(cuts[0].sessionStartMicros, DataVideoSession.WholeVideo)
        assertFalse(plain.composite)
        assertEquals(listOf("LAP"), plain.captions)
    }

    @Test
    fun aCompositeTileShowsTheTheoreticalTimeAndItsGain() {
        val lap = OverlayLap(5, LapKind.Timed, 30_000_000, 250_000, isBest = false)
        val frame = OverlayFrame(100.0, lap, null, null)
        val captions = listOf("REALISTIC OPTIMAL · LAP 5 · CORNER 3", "LAP 5 · CORNER 3", "LAP 5")
        val text = OptimalOverlayText(captions, 42_500_000, -0.31)
        val m = HudMoment(frame, emptyList(), emptyList(), 110_000_000, false).asComposite(text)
        assertTrue(m.composite)
        assertEquals(text.captions, m.captions)
        assertEquals("0:42.500", m.lapTimeText)
        assertEquals("-0.310", m.deltaText)
        assertTrue(m.deltaAhead)
        assertEquals("1:50.000", m.bestText)
        assertEquals("LAP 5", m.mapLap)
        val behind = HudMoment(frame, emptyList(), emptyList(), null, false).asComposite(text.copy(deltaSeconds = 0.2))
        assertEquals("+0.200", behind.deltaText)
        assertFalse(behind.deltaAhead)
        val none = HudMoment(frame, emptyList(), emptyList(), null, false).asComposite(text.copy(deltaSeconds = null))
        assertNull(none.deltaText)
    }

    private companion object {
        const val DemoStartUtcMillis = 1_790_000_000_000L
        const val AllDayMicros = 4L * 3_600_000_000L
    }
}
