package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.io.ByteSink
import com.flappedear.betterdriver.io.Crc32
import com.flappedear.betterdriver.io.writeProperties
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionFileNames
import com.flappedear.betterdriver.recorder.store.metaEntries
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackJson
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds a bundle as BundleWriter writes it (see BundleFile), for the common tests. */
internal fun bundleOf(
    vararg files: Pair<String, ByteArray>,
    count: Int = files.size,
    crcOf: (ByteArray) -> Int = ::crc,
): ByteArray {
    val out = ByteSink()
    out.write(BundleFormat.SessionMagic)
    out.writeInt(BundleFormat.Version)
    for ((path, bytes) in files) {
        out.writeByte(BundleFormat.FileTag)
        out.writeUtf(path)
        out.writeLong(bytes.size.toLong())
        out.write(bytes)
        out.writeInt(crcOf(bytes))
    }
    out.writeByte(BundleFormat.EndTag)
    out.writeInt(count)
    return out.toByteArray()
}

internal fun crc(bytes: ByteArray) = Crc32().apply { update(bytes) }.value

/** Collects what an unpacker hands out. */
internal class Collect : BundleEntrySink {
    val files = linkedMapOf<String, ByteArray>()
    private var path = ""
    private var buf = ByteSink()

    override fun begin(path: String, size: Long) {
        this.path = path
        buf = ByteSink()
    }

    override fun bytes(bytes: ByteArray, from: Int, to: Int) = buf.write(bytes.copyOfRange(from, to))

    override fun end() {
        files[path] = buf.toByteArray()
    }
}

/** Staged files in memory. */
internal class MemoryStaging(private val free: Long = Long.MAX_VALUE) : StagingFiles {
    val files = linkedMapOf<String, ByteArray>()
    private var path: String? = null
    private var buf = ByteSink()

    override fun create(path: String) {
        this.path = path
        buf = ByteSink()
    }

    override fun write(bytes: ByteArray, from: Int, to: Int) = buf.write(bytes.copyOfRange(from, to))

    override fun close() {
        files[checkNotNull(path)] = buf.toByteArray()
        path = null
    }

    override fun freeBytes() = free
}

private fun unpack(bundle: ByteArray, piece: Int = bundle.size, sink: BundleEntrySink = Collect()): BundleEntrySink {
    val u = BundleUnpacker(sink)
    var i = 0
    while (i < bundle.size) {
        val end = minOf(bundle.size, i + piece)
        u.feed(bundle, i, end)
        i = end
    }
    u.finish()
    return sink
}

class BundleUnpackerTest {
    private val video = ByteArray(200_000) { (it * 31).toByte() }
    private val files = arrayOf(
        "bundle.properties" to "format=x\n".encodeToByteArray(),
        "session/video/clip-0.mp4" to video,
        "session/empty.json" to ByteArray(0),
    )

    @Test
    fun `reads every file whatever the piece size`() {
        val bundle = bundleOf(*files)
        for (piece in listOf(1, 2, 3, 7, 64, 65_536, bundle.size)) {
            val got = (unpack(bundle, piece) as Collect).files
            assertEquals(files.map { it.first }, got.keys.toList(), "piece $piece")
            files.forEach { (p, b) -> assertContentEquals(b, got.getValue(p), "$p piece $piece") }
        }
    }

    @Test
    fun `refuses damaged bundles`() {
        val good = bundleOf(*files)
        assertFailsWith<BundleDamagedException> { unpack(good.copyOf(good.size - 1)) }
        assertFailsWith<BundleDamagedException> { unpack(bundleOf(*files, crcOf = { crc(it) xor 1 })) }
        assertFailsWith<BundleDamagedException> { unpack(bundleOf(*files, count = 2)) }
        assertFailsWith<BundleDamagedException> { unpack(bundleOf("../escape" to ByteArray(1))) }
        assertFailsWith<BundleDamagedException> { unpack(good.copyOf().also { it[0] = 'X'.code.toByte() }) }
        assertFailsWith<BundleDamagedException> { unpack(good + byteArrayOf(0)) }
        val newer = good.copyOf().also { it[BundleFormat.SessionMagic.size + 3] = 2 }
        assertTrue(assertFailsWith<BundleDamagedException> { unpack(newer) }.message!!.contains("newer"))
    }
}

class SessionBundleReceiverTest {
    private val info = SessionInfo(startUtcMillis = 1_756_476_419_000L, car = "FR-S", track = "Jastrząb")
    private val meta = writeProperties(metaEntries("20250829-160659-abcd1234", info))
    private val track = Track(id = "jastrzab", name = "Jastrząb", center = GeoPoint(51.0, 19.0))
    private val trackJson = TrackJson.encode(track).encodeToByteArray()

    private fun header(id: String = "20250829-160659-abcd1234", trackId: String? = "jastrzab") = writeProperties(
        buildMap {
            put("format", SessionBundleReceiver.Format)
            put("version", "1")
            put("session", id)
            trackId?.let { put("track", it) }
        },
    )

    private fun receive(
        vararg files: Pair<String, ByteArray>,
        staging: MemoryStaging = MemoryStaging(),
    ): ReceivedSession {
        val r = SessionBundleReceiver(staging)
        unpack(bundleOf(*files), piece = 5, sink = r)
        return r.result()
    }

    @Test
    fun `stages the session and reads its info and track`() {
        val staging = MemoryStaging()
        val chunk = ByteArray(1000) { it.toByte() }
        val got = receive(
            "bundle.properties" to header(),
            "track.json" to trackJson,
            "session/" + SessionFileNames.META to meta,
            "session/" + SessionFileNames.chunkName(0) to chunk,
            "later/entry" to ByteArray(3),
            staging = staging,
        )
        assertEquals("20250829-160659-abcd1234", got.sessionId)
        assertEquals(info.copy(channels = got.info.channels), got.info)
        assertEquals(track, got.track)
        assertEquals(listOf(SessionFileNames.META, SessionFileNames.chunkName(0)), staging.files.keys.toList())
        assertContentEquals(chunk, staging.files.getValue(SessionFileNames.chunkName(0)))
    }

    @Test
    fun `a session without a track has none`() {
        val got = receive("bundle.properties" to header(trackId = null), "session/" + SessionFileNames.META to meta)
        assertNull(got.track)
    }

    @Test
    fun `refuses what is not a good session`() {
        val m = "session/" + SessionFileNames.META to meta
        assertFailsWith<BundleDamagedException> { receive("track.json" to trackJson, m) }
        assertFailsWith<BundleDamagedException> { receive("bundle.properties" to header(id = "../x"), m) }
        assertFailsWith<BundleDamagedException> { receive("bundle.properties" to header()) }
        assertFailsWith<BundleDamagedException> {
            receive("bundle.properties" to header(trackId = "other"), "track.json" to trackJson, m)
        }
        val big = ByteArray((SessionEntryLimits.MaxChunkBytes + 1).toInt())
        assertFailsWith<BundleDamagedException> {
            receive("bundle.properties" to header(), m, "session/" + SessionFileNames.chunkName(0) to big)
        }
        val full = MemoryStaging(free = SessionEntryLimits.SpaceMarginBytes)
        assertFailsWith<NotEnoughSpaceException> { receive("bundle.properties" to header(), m, staging = full) }
    }
}
