package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoIndex
import com.flappedear.betterdriver.video.VideoSourceKind
import com.flappedear.betterdriver.video.gpmf.FakeGoProFile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.time.ZoneOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit A01: picking a video the session already has never puts the session's copy at risk. The
 * existing file and the index stay byte for byte as they were when the new copy breaks off half-way
 * or cannot be placed, and two picked files of one name never overwrite each other.
 */
class FileImportTest {
    @TempDir
    lateinit var session: File

    @TempDir
    lateinit var card: File

    private val camera = "hero8"
    private val data = TestSession.data(400)
    private val videoDir get() = File(session, "video")
    private val index get() = File(videoDir, "clips.json")
    private val stored get() = File(videoDir, "$camera-GH010007.MP4")

    private fun import(vararg files: PickedFile, laterMillis: Long = 0): List<String> = FileImport(
        session,
        camera,
        VideoSourceKind.GOPRO_FILES,
        GoProImporter(zone = ZoneOffset.UTC),
        nowMillis = { System.currentTimeMillis() + laterMillis },
        readSession = { data },
    ).run(files.toList())

    /**
     * The state an import killed half-way through its commit leaves: the session's copy moved into
     * the staging folder, the new file in its place, the journal naming the new clip.
     */
    private fun killedDuringCommit(newClip: VideoClip): File {
        val staging = File(videoDir, ".import-killed").apply { mkdirs() }
        val journal = Json.encodeToString(ListSerializer(VideoClip.serializer()), listOf(newClip))
        File(staging, "commit.json").writeText(journal)
        stored.copyTo(File(staging, stored.name + ".previous"))
        stored.writeText("the new copy")
        return staging
    }

    /** A HERO8-style chapter on the "card", recorded [offsetUs] into the session. */
    private fun chapter(name: String, offsetUs: Long, seconds: Int = 60): File {
        val file = File(card, "$offsetUs-$name").also { it.parentFile.mkdirs() }
        val payloads = List(seconds) { s ->
            val firstUtc = TestSession.StartUtcMillis + offsetUs / 1000 + s * 1000L
            val speeds = List(18) { i -> TestSession.speedAt(firstUtc + i * 1000L / 18) }
            FakeGoProFile.gps5Payload(firstUtc + 400, speeds)
        }
        FakeGoProFile.writeMp4(file, payloads)
        return file
    }

    private fun picked(name: String?, file: File) = PickedFile(name) { file.inputStream() }

    /** Gives the first [bytes] bytes of [file], then fails as a provider that lost the file would. */
    private fun breaking(name: String, file: File, bytes: Int) = PickedFile(name) {
        object : FilterInputStream(file.inputStream()) {
            var left = bytes

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) throw IOException("the provider went away")
                val n = super.read(b, off, minOf(len, left))
                if (n > 0) left -= n
                return n
            }
        }
    }

    private fun assertOnlyFiles(vararg names: String) {
        assertEquals((names.toList() + "clips.json" + "clips.json.lock").sorted(), videoDir.list()!!.sorted())
    }

    private fun first(): Pair<ByteArray, ByteArray> {
        assertEquals(emptyList(), import(picked("GH010007.MP4", chapter("GH010007.MP4", 5_300_000))))
        assertEquals(listOf(stored.name), VideoIndex(session).clips().map { it.fileName })
        return stored.readBytes() to index.readBytes()
    }

    @Test
    fun `a copy that breaks off half-way leaves the session's file and index as they were`() {
        val (video, clips) = first()
        val again = chapter("GH010007.MP4", 65_300_000)
        val problems = import(breaking("GH010007.MP4", again, bytes = (again.length() / 2).toInt()))
        assertEquals(1, problems.size, "$problems")
        assertContentEquals(video, stored.readBytes())
        assertContentEquals(clips, index.readBytes())
        assertOnlyFiles(stored.name)
    }

    @Test
    fun `a file that cannot be placed leaves the session's file and index as they were`() {
        val (video, clips) = first()
        // The same name, from another day: not in this session.
        val problems = import(picked("GH010007.MP4", chapter("GH010007.MP4", -86_400_000_000L)))
        assertEquals(1, problems.size, "$problems")
        assertContentEquals(video, stored.readBytes())
        assertContentEquals(clips, index.readBytes())
        assertOnlyFiles(stored.name)
    }

    @Test
    fun `a placed file replaces the earlier copy of the same name and its clip`() {
        first()
        val again = chapter("GH010007.MP4", 125_300_000)
        assertEquals(emptyList(), import(picked("GH010007.MP4", again)))
        assertContentEquals(again.readBytes(), stored.readBytes())
        val clip = VideoIndex(session).clips().single()
        assertTrue(clip.sessionOffsetUs in 125_240_000..125_360_000, "${clip.sessionOffsetUs}")
        assertOnlyFiles(stored.name)
    }

    @Test
    fun `two picked files of one name never overwrite each other`() {
        val a = chapter("GH010007.MP4", 5_300_000)
        val b = chapter("GH010007.MP4", 125_300_000)
        val problems = import(picked("GH010007.MP4", a), picked("DCIM/101GOPRO/GH010007.MP4", b))
        assertEquals(1, problems.size, "$problems")
        assertTrue(problems.single().contains("same name"), problems.single())
        assertContentEquals(a.readBytes(), stored.readBytes())
        assertEquals(listOf(stored.name), VideoIndex(session).clips().map { it.fileName })
    }

    @Test
    fun `a file with no usable name never takes the number of one the session has`() {
        val a = chapter("a.MP4", 5_300_000)
        val b = chapter("b.MP4", 125_300_000)
        assertEquals(emptyList(), import(picked(null, a)))
        val firstCopy = File(videoDir, "${camera}_0001.MP4")
        val bytes = firstCopy.readBytes()
        assertEquals(emptyList(), import(picked("my clip.mp4", b)))
        assertContentEquals(bytes, firstCopy.readBytes())
        assertContentEquals(b.readBytes(), File(videoDir, "${camera}_0002.MP4").readBytes())
        assertEquals(
            listOf("${camera}_0001.MP4", "${camera}_0002.MP4"),
            VideoIndex(session).clips().map { it.fileName }.sorted(),
        )
    }

    @Test
    fun `a staging folder left by a killed import is removed later, a fresh one is not`() {
        videoDir.mkdirs()
        val stale = File(videoDir, ".import-old").apply { mkdirs() }
        val half = File(stale, "x.MP4").apply { writeText("half") }
        val twoHoursAgo = System.currentTimeMillis() - 2 * 60 * 60 * 1000
        half.setLastModified(twoHoursAgo)
        stale.setLastModified(twoHoursAgo)
        val fresh = File(videoDir, ".import-other").apply { mkdirs() }
        import(picked("GH010007.MP4", chapter("GH010007.MP4", 5_300_000)))
        assertTrue(!stale.exists())
        assertTrue(fresh.isDirectory)
    }

    @Test
    fun `an import killed before its index was written gets the session's copy back`() {
        val (video, clips) = first()
        val newClip = VideoIndex(session).clips().single().let { it.copy(sessionOffsetUs = it.sessionOffsetUs + 1) }
        val staging = killedDuringCommit(newClip)
        // A fresh staging folder may be another import still running: left alone.
        import(laterMillis = 0)
        assertTrue(staging.isDirectory)
        import(laterMillis = 2 * 60 * 60 * 1000L)
        assertContentEquals(video, stored.readBytes())
        assertContentEquals(clips, index.readBytes())
        assertTrue(!staging.exists())
    }

    @Test
    fun `an import killed after its index was written is finished`() {
        first()
        val newClip = VideoIndex(session).clips().single().let { it.copy(sessionOffsetUs = it.sessionOffsetUs + 1) }
        val staging = killedDuringCommit(newClip)
        VideoIndex(session).putAll(listOf(newClip))
        import(laterMillis = 2 * 60 * 60 * 1000L)
        assertEquals("the new copy", stored.readText())
        assertEquals(listOf(newClip), VideoIndex(session).clips())
        assertTrue(!staging.exists())
    }
}
