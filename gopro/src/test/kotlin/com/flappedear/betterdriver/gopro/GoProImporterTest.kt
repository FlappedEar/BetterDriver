package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.log.CheckLog
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.log.LogFile
import com.flappedear.betterdriver.recorder.log.LogLine
import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoIndex
import com.flappedear.betterdriver.video.VideoSourceKind
import com.flappedear.betterdriver.video.gpmf.FakeGoProFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoProImporterTest {
    @TempDir
    lateinit var dir: File

    private val videoDir get() = File(dir, "video").apply { mkdirs() }
    private val hero8 = GoProImporter.Source(VideoSourceKind.GOPRO_FILES, "hero8")

    /**
     * A HERO8-style chapter: 1 Hz GPS5 payloads of 18 samples whose video time 0 is at session
     * time [offsetUs]. GPSU is late by [gpsuErrorMs], as GoPro's 1 Hz GPSU can be.
     */
    private fun chapter(name: String, offsetUs: Long, seconds: Int, gpsuErrorMs: Long = 400): File {
        val file = File(videoDir, name)
        val payloads = List(seconds) { s ->
            val firstUtc = TestSession.StartUtcMillis + offsetUs / 1000 + s * 1000L
            val speeds = List(18) { i -> TestSession.speedAt(firstUtc + i * 1000L / 18) }
            FakeGoProFile.gps5Payload(firstUtc + gpsuErrorMs, speeds)
        }
        FakeGoProFile.writeMp4(file, payloads)
        return file
    }

    @Test
    fun `HERO8 chapters are aligned by GPS and indexed`() {
        val session = TestSession.data(400)
        val c1 = chapter("GH010007.MP4", 5_300_000, 120)
        val c2 = chapter("GH020007.MP4", 125_300_000, 120)
        val result = GoProImporter(zone = ZoneOffset.UTC).import(dir, session, listOf(c1, c2), hero8)
        assertEquals(emptyList(), result.problems)
        val clips = VideoIndex(dir).clips()
        assertEquals(listOf("GH010007.MP4", "GH020007.MP4"), clips.map { it.fileName })
        assertTrue(clips.all { it.alignment.method == AlignmentMethod.GPS_MATCH })
        assertTrue(abs(clips[0].sessionOffsetUs - 5_300_000) <= 60_000, "${clips[0].sessionOffsetUs}")
        assertTrue(abs(clips[1].sessionOffsetUs - 125_300_000) <= 60_000, "${clips[1].sessionOffsetUs}")
        assertEquals(2, clips[1].chapter)
        assertEquals(120_000_000, clips[0].durationUs)
    }

    @Test
    fun `each import and its stretches go in the drive's log`() {
        val session = TestSession.data(400)
        val c1 = chapter("GH010007.MP4", 5_300_000, 120)
        val c2 = chapter("GH020007.MP4", 125_300_000, 120)
        val other = chapter("GH010001.MP4", -86_400_000_000L, 60)
        val log = File(dir, "betterdriver.log")
        DebugLog.install(LogFile(log, "main", background = false))
        try {
            GoProImporter(zone = ZoneOffset.UTC).import(dir, session, listOf(c1, c2), hero8)
            GoProImporter(zone = ZoneOffset.UTC).import(dir, session, listOf(other), hero8)
        } finally {
            DebugLog.install(null)
        }
        val events = log.readLines().mapNotNull { LogLine.parse(it) }
            .filter { it.tag == CheckLog.EVENT && it.session == session.id }
            .mapNotNull { CheckLog.parseEvent(it.message) }
        val imports = events.filter { it.kind == EventKinds.VIDEO_IMPORT }.map { CheckLog.fields(it.text) }
        assertEquals(listOf("aligned", "aligned", "rejected"), imports.map { it["result"] })
        assertEquals(listOf("GPS_MATCH", "GPS_MATCH"), imports.take(2).map { it["method"] })
        assertEquals("120000000", imports[0]["duration_us"])
        // One two-minute stretch per chapter, each placed on its own where the whole file is.
        val probes = events.filter { it.kind == EventKinds.VIDEO_PROBE }
        assertEquals(2, probes.size)
        probes.zip(listOf(5_300_000L, 125_300_000L)).forEach { (p, at) ->
            assertTrue(abs(CheckLog.fields(p.text)["offset_us"]!!.toLong() - at) <= 60_000, p.text)
            assertTrue(abs(p.tMicros - (at + 60_000_000)) <= 60_000, "${p.tMicros}")
        }
    }

    @Test
    fun `a file from another day is not indexed`() {
        val session = TestSession.data(200)
        val other = chapter("GH010001.MP4", -86_400_000_000L, 60)
        val result = GoProImporter(zone = ZoneOffset.UTC).import(dir, session, listOf(other), hero8)
        assertEquals(1, result.problems.size)
        assertTrue(VideoIndex(dir).clips().isEmpty())
    }

    @Test
    fun `another camera's creation time is read as UTC when only that fits the session`() {
        val session = TestSession.data(400)
        val dji = GoProImporter.Source(VideoSourceKind.CAMERA_FILES, "dji")
        // Written as UTC, 60 s into the session; read as local time on a UTC+2 phone it is 2 h off.
        val createdSince1904 = TestSession.StartUtcMillis / 1000 + 60 + 2_082_844_800L
        val file = File(videoDir, "dji-DJI_0001.MP4")
        FakeGoProFile.writeMp4(file, emptyList(), createdSince1904)
        val result = GoProImporter(zone = ZoneOffset.ofHours(2)).import(dir, session, listOf(file), dji)
        assertEquals(emptyList(), result.problems)
        assertEquals(60_000_000L, VideoIndex(dir).clips().single().sessionOffsetUs)
    }

    /** A HERO8 file with no GPS fix, [seconds] long, created at session time [createdAtS] on a UTC camera clock. */
    private fun noFixFile(name: String, seconds: Int, createdAtS: Long): File {
        val file = File(videoDir, name)
        val payloads = List(seconds) {
            FakeGoProFile.gps5Payload(TestSession.StartUtcMillis, List(18) { 0.0 }, fix = 0)
        }
        FakeGoProFile.writeMp4(file, payloads, TestSession.StartUtcMillis / 1000 + createdAtS + Since1904)
        return file
    }

    @Test
    fun `a short session keeps a clip whose rough camera clock puts it just past the end`() {
        // FBBD-201: a 40 s session; a hand-started GoPro without GPS fix whose clock runs 90 s
        // ahead. The clock is only good to half an hour, so the clip belongs to the session.
        val session = TestSession.data(40)
        val file = noFixFile("GX010099.MP4", 30, createdAtS = 90)
        val result = GoProImporter(zone = ZoneOffset.UTC).import(dir, session, listOf(file), hero8)
        assertEquals(emptyList(), result.problems)
        val clip = VideoIndex(dir).clips().single()
        assertEquals(AlignmentMethod.CAMERA_CLOCK, clip.alignment.method)
        assertTrue(clip.sessionOffsetUs in 0..10_000_000, "${clip.sessionOffsetUs}")
    }

    @Test
    fun `an Open GoPro clip acknowledged after the last sample of a short session is kept`() {
        val session = TestSession.data(20)
        val gopro = GoProImporter.Source(VideoSourceKind.GOPRO, "hero11")
        val file = noFixFile("GX010099.MP4", 15, createdAtS = 21)
        val recording = GoProRecording("hero11", startAckSessionUs = 21_000_000, startAckUtcMillis = 0)
        val result = GoProImporter(zone = ZoneOffset.UTC).import(dir, session, listOf(file), gopro, recording)
        assertEquals(emptyList(), result.problems)
        val clip = VideoIndex(dir).clips().single()
        assertEquals(AlignmentMethod.COMMAND_ACK, clip.alignment.method)
        assertTrue(clip.sessionOffsetUs <= 20_000_000, "${clip.sessionOffsetUs}")
    }

    @Test
    fun `a GoPro file whose clock is written as UTC is read as UTC when only that fits`() {
        val session = TestSession.data(400)
        val file = noFixFile("GX010100.MP4", 30, createdAtS = 60)
        // Read as local time on a UTC+2 phone it would be 2 h off.
        val result = GoProImporter(zone = ZoneOffset.ofHours(2)).import(dir, session, listOf(file), hero8)
        assertEquals(emptyList(), result.problems)
        assertEquals(60_000_000L, VideoIndex(dir).clips().single().sessionOffsetUs)
    }

    @Test
    fun `a GPS-placed clip outside the session is not moved in`() {
        val clip = VideoClip(
            "GX.MP4", VideoSourceKind.GOPRO_FILES, "hero8", sessionOffsetUs = 50_000_000,
            alignment = Alignment(AlignmentMethod.GPS_UTC, 1_000_000), durationUs = 10_000_000,
        )
        assertNull(fitIntoSession(clip, 40_000_000))
        assertEquals(clip, fitIntoSession(clip, 60_000_000))
        assertEquals(39_500_000, fitIntoSession(clip.copy(sessionOffsetUs = 40_500_000), 40_000_000)?.sessionOffsetUs)
    }

    @Test
    fun `a file that is not an MP4 is reported, not thrown`() {
        val session = TestSession.data(60)
        val junk = File(videoDir, "GH010002.MP4").apply { writeText("not a video") }
        val result = GoProImporter().import(dir, session, listOf(junk), hero8)
        assertEquals(1, result.problems.size)
    }

    private companion object {
        const val Since1904 = 2_082_844_800L
    }
}
