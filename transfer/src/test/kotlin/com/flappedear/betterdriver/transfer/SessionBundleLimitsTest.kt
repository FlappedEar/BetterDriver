@file:Suppress("MagicNumber") // Test sizes.

package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Sizes a session bundle may declare (audit 2026-10-22, A08): files read whole are refused from
 * their declared size, before a byte is copied or read into memory; video is not limited.
 */
class SessionBundleLimitsTest {
    @TempDir
    lateinit var tmp: File

    private val sessionId = "20261022-080000-ab12cd34"
    private val staging get() = File(tmp, "staging").apply { mkdirs() }

    /** Valid metadata of a session with the GNSS channels, as the recorder writes it. */
    private fun meta(): ByteArray {
        val store = SessionStore(File(tmp, "src"))
        val (_, dir) = store.createSession(info())
        return File(dir, SessionFiles.META).readBytes()
    }

    private fun info() = SessionInfo(1_760_000_000_000L, "FR-S", "", listOf("gnss"), null, GnssChannels.ALL)

    private fun header(): ByteArray = ByteArrayOutputStream().also {
        Properties().apply {
            setProperty("format", SessionBundle.Format)
            setProperty("version", BundleFile.Version.toString())
            setProperty("session", sessionId)
        }.store(it, null)
    }.toByteArray()

    /**
     * A bundle of whole [entries], then optionally one entry that declares [declared] bytes and holds
     * none: a reader that checks the size first refuses it as too large, one that reads first finds it cut short.
     */
    private fun bundle(entries: List<Pair<String, ByteArray>>, declared: Pair<String, Long>? = null): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write(BundleFile.Magic)
            writeInt(BundleFile.Version)
            for ((path, bytes) in listOf(SessionBundle.Header to header()) + entries) {
                writeByte(BundleFile.FileTag)
                writeUTF(path)
                writeLong(bytes.size.toLong())
                write(bytes)
                writeInt(CRC32().apply { update(bytes) }.value.toInt())
            }
            if (declared != null) {
                writeByte(BundleFile.FileTag)
                writeUTF(declared.first)
                writeLong(declared.second)
            } else {
                writeByte(BundleFile.EndTag)
                writeInt(entries.size + 1)
            }
        }
        return out.toByteArray()
    }

    private fun refused(bytes: ByteArray, free: Long = Long.MAX_VALUE): IOException {
        val e = assertFailsWith<IOException> {
            SessionBundle.receive(bytes.inputStream(), staging, freeBytes = { free })
        }
        assertEquals(emptyList(), staging.listFiles()!!.toList(), "staging cleaned up")
        return e
    }

    @Test
    fun `oversized metadata is refused from its declared size`() {
        val e = refused(bundle(emptyList(), "session/${SessionFiles.META}" to SessionLimits.MaxMetaBytes + 1))
        assertTrue(e.message!!.contains("too large"), e.message)
    }

    @Test
    fun `oversized metadata already on disk is refused before it is loaded`() {
        // Within the declared-size check only if the limit were missing; readInfo checks the file too.
        val dir = File(tmp, "s").apply { mkdirs() }
        File(dir, SessionFiles.META).writeBytes(meta() + "#".repeat(SessionLimits.MaxMetaBytes.toInt()).toByteArray())
        val e = assertFailsWith<BundleFormatException> { SessionLimits.readInfo(dir, "session") }
        assertTrue(e.message!!.contains("too large"), e.message)
    }

    @Test
    fun `a bad channel count is refused before the channel list is built`() {
        val text = meta().toString(Charsets.ISO_8859_1).replace(Regex("(?m)^channels=\\d+"), "channels=5000000")
        val e = refused(bundle(listOf("session/${SessionFiles.META}" to text.toByteArray(Charsets.ISO_8859_1))))
        assertTrue(e.message!!.contains("channel count"), e.message)
    }

    @Test
    fun `an oversized chunk is refused from its declared size`() {
        val chunk = "session/${SessionFiles.chunkName(1)}"
        val e = refused(bundle(listOf("session/${SessionFiles.META}" to meta()), chunk to (1L shl 30)))
        assertTrue(e.message!!.contains("too large"), e.message)
        // An index file read whole has its own limit.
        val index = refused(bundle(emptyList(), "session/video/clips.json" to SessionLimits.MaxSmallFileBytes + 1))
        assertTrue(index.message!!.contains("too large"), index.message)
    }

    @Test
    fun `a file that would fill the device is refused before it is copied`() {
        val e = refused(bundle(emptyList(), "session/video/GX010001.MP4" to (1L shl 30)), free = 512L shl 20)
        assertTrue(e.message!!.contains("free space"), e.message)
    }

    @Test
    fun `a refused bundle leaves the sessions already here unchanged`() {
        val store = SessionStore(File(tmp, "tablet"))
        store.createSession(info())
        val before = store.list().map { it.id to SessionFiles.sizeBytes(it.dir) }
        val inbox = SessionInbox(store, File(tmp, "inbox"))
        assertFailsWith<BundleFormatException> {
            inbox.receive(bundle(emptyList(), "session/${SessionFiles.chunkName(1)}" to (1L shl 30)).inputStream())
        }
        assertEquals(before, store.list().map { it.id to SessionFiles.sizeBytes(it.dir) })
        assertEquals(emptyList(), File(tmp, "inbox").listFiles()!!.toList())
    }

    @Test
    fun `a valid video larger than every whole-read limit still arrives`() {
        val video = ByteArray((SessionLimits.MaxChunkBytes + 1).toInt()) { it.toByte() }
        val chunk = ByteArray(1000)
        val received = SessionBundle.receive(
            bundle(
                listOf(
                    "session/${SessionFiles.META}" to meta(),
                    "session/${SessionFiles.chunkName(1)}" to chunk,
                    "session/video/phone_0001.mp4" to video,
                ),
            ).inputStream(),
            staging,
        )
        assertEquals(sessionId, received.sessionId)
        assertEquals(video.size.toLong(), File(received.sessionDir, "video/phone_0001.mp4").length())
    }

    @Test
    fun `limits apply by kind of file`() {
        assertEquals(SessionLimits.MaxMetaBytes, SessionLimits.maxBytes(SessionFiles.META))
        assertEquals(SessionLimits.MaxChunkBytes, SessionLimits.maxBytes(SessionFiles.chunkName(12)))
        assertEquals(SessionLimits.MaxSmallFileBytes, SessionLimits.maxBytes(SessionFiles.FINISHED))
        assertEquals(SessionLimits.MaxSmallFileBytes, SessionLimits.maxBytes("video/clips.json"))
        assertEquals(null, SessionLimits.maxBytes("video/GX010001.MP4"))
        assertEquals(null, SessionLimits.maxBytes("video/chunk-000001.bdr")) // Not a session chunk: never read as one.
    }
}
