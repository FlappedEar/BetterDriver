package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.VideoIndex
import com.flappedear.betterdriver.video.VideoSourceKind
import com.flappedear.betterdriver.video.gpmf.FakeGoProFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.assertEquals
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
    fun `a file from another day is not indexed`() {
        val session = TestSession.data(200)
        val other = chapter("GH010001.MP4", -86_400_000_000L, 60)
        val result = GoProImporter(zone = ZoneOffset.UTC).import(dir, session, listOf(other), hero8)
        assertEquals(1, result.problems.size)
        assertTrue(VideoIndex(dir).clips().isEmpty())
    }

    @Test
    fun `a file that is not an MP4 is reported, not thrown`() {
        val session = TestSession.data(60)
        val junk = File(videoDir, "GH010002.MP4").apply { writeText("not a video") }
        val result = GoProImporter().import(dir, session, listOf(junk), hero8)
        assertEquals(1, result.problems.size)
    }
}
