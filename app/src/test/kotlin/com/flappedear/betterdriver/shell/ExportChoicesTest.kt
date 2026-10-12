package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.recording.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportChoicesTest {
    @Test
    fun menuListsVboThenCsvAndGpx() {
        assertEquals(listOf("VBO", "CSV", "GPX"), exportChoices(video = false).map { it.label })
        assertEquals(
            listOf(ExportFormat.Vbo, ExportFormat.Csv, ExportFormat.Gpx),
            exportChoices(video = false).map { it.format },
        )
    }

    @Test
    fun sessionWithVideoKeepsVboPlusVideo() {
        assertEquals("VBO + video", exportChoices(video = true).first().label)
        assertEquals(ExportFormat.Vbo, exportChoices(video = true).first().format)
    }
}
