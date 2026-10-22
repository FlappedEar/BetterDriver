@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.review

import com.flappedear.betterdriver.recorder.store.SessionFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The lap review reads a session again when the session itself changed, not only its track or
 * video (audit A10): a session replaced by an import of the same id, which keeps its directory name
 * and has no video, must not leave the old laps on screen.
 */
class SessionStampTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val earlier = 1_760_000_000_000L
    private val later = earlier + 60_000L

    /** A session directory as the recorder leaves it, with [chunk] as its one chunk's bytes, written at [at]. */
    private fun session(dir: File, chunk: String, at: Long): File {
        dir.mkdirs()
        val files = listOf(
            File(dir, SessionFiles.META) to "id=20261022-090700-abcd1234\n",
            File(dir, SessionFiles.chunkName(0)) to chunk,
            File(dir, SessionFiles.FINISHED) to "endMicros=60000000\n",
        )
        for ((f, text) in files) {
            f.writeText(text)
            f.setLastModified(at)
        }
        return dir
    }

    @Test
    fun `a session replaced under the same id has another stamp`() {
        val sessions = tmp.newFolder("sessions")
        val target = session(File(sessions, "20261022-090700-abcd1234"), "first drive samples", earlier)
        val before = Stamps.of(target)
        // As the import swaps them: the session here moves aside, the received one takes its place.
        val incoming = session(File(tmp.newFolder("staging"), "received"), "second drive samples!", later)
        val old = File(tmp.root, "old")
        check(target.renameTo(old) && incoming.renameTo(target))
        assertNotEquals(before, Stamps.of(target))
    }

    @Test
    fun `a chunk rewritten at the same size has another stamp`() {
        val dir = session(tmp.newFolder("s"), "aaaa", earlier)
        val before = sessionStamp(dir)
        File(dir, SessionFiles.chunkName(0)).apply { writeText("bbbb"); setLastModified(later) }
        assertNotEquals(before, sessionStamp(dir))
    }

    @Test
    fun `a recovered session, or one more chunk, has another stamp`() {
        val dir = session(tmp.newFolder("s"), "aaaa", earlier)
        val before = sessionStamp(dir)
        File(dir, SessionFiles.RECOVERY).writeText("lastSampleMicros=1\n")
        val recovered = sessionStamp(dir)
        assertNotEquals(before, recovered)
        File(dir, SessionFiles.chunkName(1)).writeText("cccc")
        assertNotEquals(recovered, sessionStamp(dir))
    }

    @Test
    fun `the quality report written beside the session does not read it again`() {
        val dir = session(tmp.newFolder("s"), "aaaa", earlier)
        val before = Stamps.of(dir)
        File(dir, "quality.json").writeText("{}")
        assertEquals(before, Stamps.of(dir))
    }
}
