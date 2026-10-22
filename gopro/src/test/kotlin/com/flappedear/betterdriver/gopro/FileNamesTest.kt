package com.flappedear.betterdriver.gopro

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class FileNamesTest {
    private val camera = "gopro-1234"

    @Test
    fun keepsPlainGoProNamesAfterTheCamera() {
        assertEquals("gopro-1234-GH010031.MP4", plainName(camera, "GH010031.MP4", 0))
        assertEquals("gopro-1234-GH010031.MP4", plainName(camera, "DCIM/100GOPRO/GH010031.MP4", 0))
    }

    @Test
    fun replacesUnsafeOrMissingNames() {
        assertEquals("gopro-1234_0002.MP4", plainName(camera, "my clip.mp4", 1))
        assertEquals("gopro-1234_0001.MP4", plainName(camera, null, 0))
        assertEquals("gopro-1234_0003.MP4", plainName(camera, "notes.txt", 2))
    }

    @Test
    fun groupsChaptersByRecordingInOrder() {
        val files = listOf("GH020031.MP4", "GH010032.MP4", "GH010031.MP4", "other.MP4").map(::File)
        val groups = byRecording(files).map { g -> g.map { it.name } }
        assertEquals(
            listOf(listOf("GH010031.MP4", "GH020031.MP4"), listOf("GH010032.MP4"), listOf("other.MP4")),
            groups,
        )
    }

    @Test
    fun groupsChaptersWithTheCameraPrefix() {
        val names = listOf("gopro-1234-GX020007.MP4", "gopro-1234-GX010007.MP4", "gopro-1234_0001.MP4")
        val groups = byRecording(names.map(::File)).map { g -> g.map { it.name } }
        assertEquals(
            listOf(listOf("gopro-1234-GX010007.MP4", "gopro-1234-GX020007.MP4"), listOf("gopro-1234_0001.MP4")),
            groups,
        )
    }
}
