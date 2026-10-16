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
    fun sessionWithVideoOffersVboPlusVideoAndPlainVbo() {
        assertEquals(listOf("VBO + video", "VBO", "CSV", "GPX"), exportChoices(video = true).map { it.label })
        assertEquals(
            listOf(ExportFormat.VboVideo, ExportFormat.Vbo, ExportFormat.Csv, ExportFormat.Gpx),
            exportChoices(video = true).map { it.format },
        )
    }
}
