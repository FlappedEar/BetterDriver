package com.flappedear.betterdriver.recorder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The share of a written export, the same on Android and iOS (FBBD-443). */
class ExportShareTest {
    private val id = "20261103-081329-4dc6a889"

    @Test
    fun aSingleFileIsSharedWithItsOwnTypeAndName() {
        val csv = ExportShare.of(id, ExportFormat.Csv, "/out/$id/BetterDriver-$id.csv")
        assertEquals(listOf("/out/$id/BetterDriver-$id.csv"), csv.files)
        assertEquals("text/csv", csv.mimeType)
        assertEquals("BetterDriver-$id.csv", csv.subject)
        assertEquals("Share export", csv.title)
        assertEquals("application/gpx+xml", ExportShare.of(id, ExportFormat.Gpx, "/a.gpx").mimeType)
        assertEquals("application/octet-stream", ExportShare.of(id, ExportFormat.Vbo, "/a.vbo").mimeType)
    }

    @Test
    fun vboWithVideoSharesTheVboFirstThenEachVideoFile() {
        val share = ExportShare.of(
            id,
            ExportFormat.VboVideo,
            "/o/BetterDriver-$id.vbo",
            listOf("/o/BetterDriver-${id}_0001.MP4", "/o/BetterDriver-${id}_0002.MP4"),
        )
        assertEquals(
            listOf("/o/BetterDriver-$id.vbo", "/o/BetterDriver-${id}_0001.MP4", "/o/BetterDriver-${id}_0002.MP4"),
            share.files,
        )
        assertEquals("*/*", share.mimeType)
        assertEquals("BetterDriver-$id VBO + video", share.subject)
    }

    @Test
    fun vboWithVideoWithoutVideoFilesIsTheVboAlone() {
        val share = ExportShare.of(id, ExportFormat.VboVideo, "/o/BetterDriver-$id.vbo")
        assertEquals(listOf("/o/BetterDriver-$id.vbo"), share.files)
        assertEquals("application/octet-stream", share.mimeType)
    }

    @Test
    fun onlyVboWithVideoCarriesVideo() {
        assertFailsWith<IllegalArgumentException> { ExportShare.of(id, ExportFormat.Csv, "/a.csv", listOf("/a.MP4")) }
    }

    @Test
    fun namesAreTheExportNamesOfBothPlatforms() {
        assertEquals("BetterDriver-$id", ExportShare.baseName(id))
        assertEquals("BetterDriver-$id.vbo", ExportShare.fileName(id, ExportFormat.VboVideo))
        assertEquals("BetterDriver-${id}_", ExportShare.videoPrefix(id))
    }

    @Test
    fun theLogLineSaysWhatIsShared() {
        assertEquals(
            "export $id as Csv: 1 file, 2379 KB, opening the share sheet",
            ExportShare.of(id, ExportFormat.Csv, "/a.csv").logLine(2_379_260),
        )
        assertEquals(
            "export $id as VboVideo: 2 files, 0 KB, opening the share sheet",
            ExportShare.of(id, ExportFormat.VboVideo, "/a.vbo", listOf("/a.MP4")).logLine(10),
        )
    }
}
