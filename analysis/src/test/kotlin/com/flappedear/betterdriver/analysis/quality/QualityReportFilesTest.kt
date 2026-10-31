package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared quality.json store both apps read the Data quality card from (FBBD-183, FBBD-365). */
class QualityReportFilesTest {
    @TempDir
    lateinit var root: File

    private var made = 0
    private var now = 1_000L

    private fun files() = QualityReportFiles(root.path, { emptyList() }, { now }) { _, _ ->
        made++
        emptyList()
    }

    private fun recorded(): String {
        val recording = Recorder(SessionStore(root)).start(
            SessionInfo(startUtcMillis = StartUtc, channels = GnssChannels.ALL),
        )
        repeat(Fixes) { i ->
            val t = i * 40_000L
            recording.submit(
                SampleSet(
                    t,
                    listOf(
                        ChannelValue(GnssChannels.UTC_MS.id, StartUtc + t / 1000.0),
                        ChannelValue(GnssChannels.LAT.id, 50.0 + i * 1e-6),
                        ChannelValue(GnssChannels.LON.id, 19.0),
                        ChannelValue(GnssChannels.SPEED_KMH.id, 50.0),
                        ChannelValue(GnssChannels.SATS.id, 20.0),
                    ),
                ),
            )
        }
        assertTrue(recording.stop())
        return recording.id
    }

    @Test
    fun `a finished session gets its report once and keeps it in quality json`() {
        val id = recorded()
        val first = assertNotNull(files().load(id))
        assertEquals(id, first.sessionId)
        assertEquals("FINISHED", first.sessionState)
        assertEquals(now, first.computedAtUtcMillis)
        assertTrue(File(File(root, id), QualityReportFiles.FileName).isFile)
        assertEquals(1, made)

        // Read back, not made again.
        val second = assertNotNull(files().load(id))
        assertEquals(first, second)
        assertEquals(1, made)
    }

    @Test
    fun `a clip index written after the report makes it again`() {
        val id = recorded()
        assertNotNull(files().load(id))
        val index = File(QualityReportFiles.clipIndexPath(File(root, id).path))
        index.parentFile.mkdirs()
        index.writeText("""{"version":1,"clips":[]}""")
        assertNotNull(files().load(id))
        assertEquals(2, made)
    }

    @Test
    fun `no report for a session that is missing or still recording`() {
        assertNull(files().load("nope"))
        val recording = Recorder(SessionStore(root)).start(SessionInfo(startUtcMillis = StartUtc))
        try {
            assertNull(files().load(recording.id))
        } finally {
            recording.stop()
        }
        assertEquals(0, made)
    }

    private companion object {
        const val StartUtc = 1_792_000_000_000L
        const val Fixes = 250
    }
}
