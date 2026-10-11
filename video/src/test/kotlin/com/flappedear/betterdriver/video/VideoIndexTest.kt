package com.flappedear.betterdriver.video

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoIndexTest {
    @TempDir
    lateinit var dir: File

    private fun clip(name: String, offsetUs: Long, kind: VideoSourceKind = VideoSourceKind.GOPRO_HERO11,
        method: AlignmentMethod = AlignmentMethod.GPS_MATCH, durationUs: Long? = 60_000_000) =
        VideoClip(name, kind, "src", offsetUs, Alignment(method, 20_000), durationUs)

    @Test
    fun `writers on separate instances and threads lose no clip`() {
        // The phone camera (its own process) and GoPro import (UI process) both write the index.
        val threads = (0 until 4).map { t ->
            Thread {
                val index = VideoIndex(dir)
                repeat(10) { i -> index.put(clip("c${t}_$i.mp4", (t * 10 + i).toLong())) }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(40, VideoIndex(dir).clips().size)
        assertTrue(File(dir, "video/clips.json.lock").isFile)
    }

    @Test
    fun `empty when there is no index`() {
        assertEquals(emptyList(), VideoIndex(dir).clips())
    }

    @Test
    fun `put replaces a clip with the same file name and survives a new instance`() {
        val index = VideoIndex(dir)
        index.put(clip("GX010001.MP4", 0))
        index.put(clip("GX020001.MP4", 60_000_000))
        index.put(clip("GX010001.MP4", -1_500_000))
        val read = VideoIndex(dir).clips()
        assertEquals(listOf("GX010001.MP4" to -1_500_000L, "GX020001.MP4" to 60_000_000L),
            read.map { it.fileName to it.sessionOffsetUs })
        assertTrue(File(dir, "video/clips.json").isFile)
    }

    @Test
    fun `clipAt picks the clip covering the time with the best alignment`() {
        val index = VideoIndex(dir)
        index.putAll(listOf(
            clip("a.mp4", 0, VideoSourceKind.PHONE, AlignmentMethod.CAPTURE_CLOCK),
            clip("b.mp4", 10_000_000, method = AlignmentMethod.CAMERA_CLOCK),
        ))
        assertEquals("a.mp4", index.clipAt(30_000_000)?.fileName)
        assertEquals("b.mp4", index.clipAt(65_000_000)?.fileName)
        assertEquals("b.mp4", index.clipAt(30_000_000, VideoSourceKind.GOPRO_HERO11)?.fileName)
        assertNull(index.clipAt(-1))
        assertNull(index.clipAt(71_000_001))
    }

    @Test
    fun `clips still on the camera are listed but not played`() {
        val index = VideoIndex(dir)
        index.put(clip("GX010002.MP4", 0).copy(status = ClipStatus.ON_CAMERA, cameraPath = "100GOPRO/GX010002.MP4"))
        assertEquals(1, index.clips().size)
        assertNull(index.clipAt(1_000_000))
    }

    @Test
    fun `an unreadable index is set aside, not overwritten`() {
        val videoDir = File(dir, "video").apply { mkdirs() }
        File(videoDir, "clips.json").writeText("{not json")
        val index = VideoIndex(dir)
        assertEquals(emptyList(), index.clips())
        index.put(clip("x.mp4", 0))
        assertEquals(1, index.clips().size)
        assertTrue(videoDir.listFiles()!!.any { it.name.startsWith("clips.json.unreadable-") })
    }

    @Test
    fun `remove drops one clip`() {
        val index = VideoIndex(dir)
        index.putAll(listOf(clip("a.mp4", 0), clip("b.mp4", 1)))
        index.remove("a.mp4")
        assertEquals(listOf("b.mp4"), index.clips().map { it.fileName })
    }

    @Test
    fun `clip file names cannot escape the video directory`() {
        assertFailsWith<IllegalArgumentException> { clip("../meta.properties", 0) }
        assertFailsWith<IllegalArgumentException> { clip("..", 0) }
    }

    @Test
    fun `session and video time convert both ways`() {
        val c = clip("a.mp4", -2_000_000)
        assertEquals(3_000_000, c.sessionTimeUs(5_000_000))
        assertEquals(5_000_000, c.videoTimeUs(3_000_000))
        assertTrue(c.covers(-2_000_000))
        assertTrue(!c.covers(-2_000_001))
    }
}
