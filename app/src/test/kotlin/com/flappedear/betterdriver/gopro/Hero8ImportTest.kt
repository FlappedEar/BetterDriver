package com.flappedear.betterdriver.gopro

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class Hero8ImportTest {
    @Test
    fun keepsPlainGoProNames() {
        assertEquals("GH010031.MP4", plainName("GH010031.MP4", 0))
        assertEquals("GH010031.MP4", plainName("DCIM/100GOPRO/GH010031.MP4", 0))
    }

    @Test
    fun replacesUnsafeOrMissingNames() {
        assertEquals("gopro_hero8_0002.MP4", plainName("my clip.mp4", 1))
        assertEquals("gopro_hero8_0001.MP4", plainName(null, 0))
        assertEquals("gopro_hero8_0003.MP4", plainName("notes.txt", 2))
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
}
