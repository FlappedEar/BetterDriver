package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.DeleteResult
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.VideoFiles
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeleteSessionTest {
    @TempDir
    lateinit var tmp: File

    private val gnss = FakeGnss()

    private fun finished(store: SessionStore): String {
        val before = store.list().map { it.id }.toSet()
        val rec = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL))
        val id = store.list().map { it.id }.single { it !in before }
        rec.stop()
        return id
    }

    @Test
    fun `deleting a session removes its directory and its video`() {
        val store = SessionStore(tmp)
        val id = finished(store)
        val video = File(store.dir(id), VideoFiles.DIR).apply { mkdirs() }
        File(video, "phone-0001.mp4").writeBytes(ByteArray(1000))
        File(video, "GX010042.MP4").writeBytes(ByteArray(500))
        assertTrue(store.sizeBytes(store.dir(id)) >= 1500)

        assertEquals(DeleteResult.DELETED, store.delete(id))
        assertFalse(store.dir(id).exists())
        assertEquals(emptyList(), store.list())
        assertEquals(emptyList(), tmp.listFiles()!!.toList(), "nothing is left behind")
    }

    @Test
    fun `only the chosen session is deleted`() {
        val store = SessionStore(tmp)
        val a = finished(store)
        val b = finished(store)
        assertEquals(DeleteResult.DELETED, store.delete(a))
        assertEquals(listOf(b), store.list().map { it.id })
    }

    @Test
    fun `a session being recorded cannot be deleted`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL))
        val s = store.list().single()
        assertEquals(SessionState.RECORDING, s.state)
        assertEquals(DeleteResult.RECORDING, store.delete(s.id))
        assertTrue(s.dir.isDirectory)
        rec.stop()
        assertEquals(DeleteResult.DELETED, store.delete(s.id))
    }

    @Test
    fun `unknown and out-of-root ids are refused`() {
        val store = SessionStore(File(tmp, "sessions"))
        File(tmp, "outside").mkdirs()
        assertEquals(DeleteResult.NOT_FOUND, store.delete("nope"))
        assertEquals(DeleteResult.NOT_FOUND, store.delete(""))
        assertEquals(DeleteResult.NOT_FOUND, store.delete("../outside"))
        assertEquals(DeleteResult.NOT_FOUND, store.delete("."))
        assertTrue(File(tmp, "outside").isDirectory)
    }

    @Test
    fun `a delete cut short is hidden from the list and finished by the next store`() {
        val store = SessionStore(tmp)
        val id = finished(store)
        // What a crash between the rename and the recursive delete leaves.
        assertTrue(store.dir(id).renameTo(File(tmp, ".deleting-$id")))
        assertEquals(emptyList(), store.list())
        assertEquals(emptyList(), store.recoverInterrupted())
        SessionStore(tmp)
        assertEquals(emptyList(), tmp.listFiles()!!.toList())
    }
}
