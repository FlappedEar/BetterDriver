@file:Suppress("MagicNumber") // Test sizes and positions.

package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.Frames
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapEvent
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.LapTimer
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackEvent
import com.flappedear.betterdriver.timing.track.TrackLine
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Properties
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionBundleTest {
    @TempDir
    lateinit var tmp: File

    private val gnss = FakeGnss()

    /** The fake ring as a saved track, start/finish due east and sector lines north and west. */
    private val track = Track(
        id = "fake-ring",
        name = "Fake ring",
        center = GeoPoint(gnss.centerLat, gnss.centerLon),
        startFinish = lineAt(0.0),
        sectorLines = listOf(lineAt(PI / 2), lineAt(PI)),
        revision = 3,
    )

    private fun lineAt(angle: Double): TrackLine {
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(gnss.centerLat))
        val at = GeoPoint(
            gnss.centerLat + gnss.radiusM * sin(angle) / 111_320.0,
            gnss.centerLon + gnss.radiusM * cos(angle) / mPerDegLon,
        )
        return TrackLine.across(at, (360 - Math.toDegrees(angle)) % 360)
    }

    /** A finished session of [fixes] fixes timed at [track], with two phone clips and a clip index. */
    private fun session(store: SessionStore, fixes: Int = 3_000): File {
        val info = SessionInfo(
            gnss.startUtcMillis, "FR-S", "Fake ring", listOf("fake-gnss"), gnss.startFinish, GnssChannels.ALL,
        )
        val (_, dir) = store.createSession(info)
        File(dir, SessionFiles.chunkName(1)).outputStream().use { out ->
            GnssChannels.ALL.forEach { out.write(Frames.encode(Record.Channel(it))) }
            out.write(Frames.encode(Record.Event(RecorderEvent(0, TrackEvent.KIND, "saved fake-ring rev 3 Fake ring"))))
            for (i in 0 until fixes) {
                out.write(Frames.encode(Record.Samples(SampleSet(i * 40_000L, gnss.fix(i.toLong())))))
            }
        }
        SessionFiles.writeAtomically(
            File(dir, SessionFiles.FINISHED),
            Properties().apply { setProperty("endMicros", ((fixes - 1) * 40_000L).toString()) },
        )
        val video = File(dir, "video").apply { mkdirs() }
        File(video, "phone_0001.mp4").writeBytes(Random(1).nextBytes(300_000))
        File(video, "phone_0002.mp4").writeBytes(Random(2).nextBytes(70_001))
        File(video, "clips.json").writeText("""{"version":1,"clips":[]}""")
        File(video, "clips.json.lock").writeText("")
        File(video, "hero11-0001.mp4.part").writeText("half a download")
        File(dir, SessionFiles.LOCK).writeText("")
        return dir
    }

    private fun files(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes().contentHashCode().toString() }

    private fun laps(store: SessionStore, id: String): List<Lap> {
        val timer = LapTimer(track)
        return store.read(id).sampleSets.mapNotNull { it.toFix() }
            .flatMap(timer::update)
            .filterIsInstance<LapEvent.LapDone>()
            .map { it.lap }
    }

    private fun bundle(store: SessionStore, dir: File, track: Track? = this.track): ByteArray =
        ByteArrayOutputStream().also { SessionBundle.write(store, dir, track, it) }.toByteArray()

    @Test
    fun `a session with video and its track arrives byte for byte with the same laps and sectors`() {
        val phone = SessionStore(File(tmp, "phone/sessions"))
        val dir = session(phone)
        val sent = mutableListOf<Int>()
        val bytes = ByteArrayOutputStream().also { SessionBundle.write(phone, dir, track, it) { n -> sent += n } }
            .toByteArray()
        assertEquals(SessionBundle.size(dir), sent.sum().toLong())

        val tablet = SessionStore(File(tmp, "tablet/sessions"))
        val inbox = SessionInbox(tablet, File(tmp, "tablet/transfer"))
        val received = inbox.receive(bytes.inputStream())
        assertEquals(dir.name, received.sessionId)
        assertEquals("FR-S", received.info.car)
        assertEquals(track, received.track)
        assertFalse(inbox.exists(received))
        assertTrue(inbox.accept(received, replace = false))

        val arrived = tablet.dir(dir.name)
        val expected = files(dir) - SessionFiles.LOCK - "video/clips.json.lock" - "video/hero11-0001.mp4.part"
        assertEquals(expected, files(arrived))
        assertEquals(SessionState.FINISHED, tablet.list().single().state)
        assertEquals(phone.read(dir.name).sampleSets, tablet.read(dir.name).sampleSets)

        val before = laps(phone, dir.name)
        val timed = before.filter { it.kind == LapKind.Timed }
        assertTrue(timed.size >= 2, "the test session has timed laps")
        assertTrue(timed.all { it.allSectors }, "every sector of a timed lap is timed")
        assertEquals(before, laps(tablet, dir.name))
        assertEquals(emptyList(), File(tmp, "tablet/transfer").listFiles()!!.toList(), "nothing left in staging")
    }

    @Test
    fun `the track the session was timed with is named in its events`() {
        val store = SessionStore(File(tmp, "s"))
        val dir = session(store, fixes = 10)
        assertEquals("fake-ring", TrackEvent.savedTrackId(store.read(dir.name).events))
        assertEquals(null, TrackEvent.savedTrackId(listOf(RecorderEvent(0, TrackEvent.KIND, "circuit Q123 Somewhere"))))
        assertEquals(null, TrackEvent.savedTrackId(emptyList()))
    }

    @Test
    fun `a session without a track travels without one`() {
        val phone = SessionStore(File(tmp, "phone"))
        val dir = session(phone, fixes = 10)
        val inbox = SessionInbox(SessionStore(File(tmp, "tablet")), File(tmp, "staging"))
        val received = inbox.receive(bundle(phone, dir, track = null).inputStream())
        assertEquals(null, received.track)
        assertTrue(inbox.accept(received, replace = false))
    }

    @Test
    fun `a damaged or cut-off bundle is refused and leaves nothing behind`() {
        val phone = SessionStore(File(tmp, "phone"))
        val bytes = bundle(phone, session(phone, fixes = 100))
        val tabletRoot = File(tmp, "tablet")
        val tablet = SessionStore(tabletRoot)
        val staging = File(tmp, "staging")
        val inbox = SessionInbox(tablet, staging)

        val flipped = bytes.copyOf().also { it[it.size - 1000] = (it[it.size - 1000] + 1).toByte() }
        for (bad in listOf(flipped, bytes.copyOf(bytes.size / 2), bytes.copyOf(bytes.size - 1), ByteArray(0))) {
            assertFailsWith<BundleFormatException> { inbox.receive(bad.inputStream()) }
        }
        assertFailsWith<BundleFormatException> { inbox.receive("not a bundle at all".byteInputStream()) }
        assertEquals(emptyList(), tablet.list())
        assertEquals(emptyList(), staging.listFiles()!!.toList())
    }

    @Test
    fun `a bundle from a newer app or with an escaping path is refused`() {
        val newer = ByteArrayOutputStream().also {
            it.write(BundleFile.Magic)
            it.write(byteArrayOf(0, 0, 0, 9))
        }.toByteArray()
        val inbox = SessionInbox(SessionStore(File(tmp, "t")), File(tmp, "staging"))
        assertFailsWith<BundleFormatException> { inbox.receive(newer.inputStream()) }

        for (path in listOf("../evil", "session/../../x", "/abs", "a//b", "a b")) {
            assertFailsWith<BundleFormatException> { BundleFile.checkPath(path) }
        }
        BundleFile.checkPath("session/video/GX010031.MP4")
    }

    @Test
    fun `a session already here is kept or replaced as chosen`() {
        val phone = SessionStore(File(tmp, "phone"))
        val dir = session(phone, fixes = 50)
        val tablet = SessionStore(File(tmp, "tablet"))
        val inbox = SessionInbox(tablet, File(tmp, "staging"))
        assertTrue(inbox.accept(inbox.receive(bundle(phone, dir).inputStream()), replace = false))

        // The phone gets more video after the first transfer.
        File(dir, "video/gopro_hero11_0001.MP4").writeBytes(ByteArray(1234) { 7 })
        val again = inbox.receive(bundle(phone, dir).inputStream())
        assertTrue(inbox.exists(again))
        assertFalse(inbox.accept(again, replace = false))
        assertFalse(File(tablet.dir(dir.name), "video/gopro_hero11_0001.MP4").exists())

        assertTrue(inbox.accept(inbox.receive(bundle(phone, dir).inputStream()), replace = true))
        assertEquals(1234, File(tablet.dir(dir.name), "video/gopro_hero11_0001.MP4").length())
        assertEquals(1, tablet.list().size)
        assertEquals(emptyList(), File(tmp, "staging").listFiles()!!.toList())
    }

    @Test
    fun `a replace cut short by the app dying keeps the session that was here`() {
        val phone = SessionStore(File(tmp, "phone"))
        val dir = session(phone, fixes = 50)
        val tablet = SessionStore(File(tmp, "tablet"))
        val staging = File(tmp, "staging")
        val inbox = SessionInbox(tablet, staging)
        assertTrue(inbox.accept(inbox.receive(bundle(phone, dir).inputStream()), replace = false))
        val here = files(tablet.dir(dir.name))

        // Killed after the old session was moved aside, before the new one was moved in.
        inbox.receive(bundle(phone, dir).inputStream())
        assertTrue(tablet.dir(dir.name).renameTo(File(staging, "old-" + dir.name)))
        assertTrue(inbox.clearStaging(), "the session that was here went back")
        assertEquals(here, files(tablet.dir(dir.name)))
        assertEquals(emptyList(), staging.listFiles()!!.toList())

        // Killed after the swap, before the old copy was deleted: the new one stays.
        tablet.dir(dir.name).copyRecursively(File(staging, "old-" + dir.name))
        File(tablet.dir(dir.name), "video/new.mp4").writeBytes(ByteArray(10))
        assertFalse(inbox.clearStaging())
        assertTrue(File(tablet.dir(dir.name), "video/new.mp4").exists())
        assertEquals(emptyList(), staging.listFiles()!!.toList())
    }

    @Test
    fun `a session still recording is not sent`() {
        val store = SessionStore(File(tmp, "s"))
        val (_, dir) = store.createSession(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL))
        val lock = SessionFiles.lock(dir)
        try {
            assertFailsWith<IOException> { bundle(store, dir) }
        } finally {
            lock.release()
        }
    }

    @Test
    fun `a file that shrinks while it is sent fails the send`() {
        val writer = BundleWriter(ByteArrayOutputStream())
        assertFailsWith<IOException> { writer.file("a", 10, ByteArray(5).inputStream()) }
    }

    @Test
    fun `incoming files are told apart by their first bytes`() {
        val phone = SessionStore(File(tmp, "phone"))
        val bytes = bundle(phone, session(phone, fixes = 5))
        assertEquals(IncomingKind.SESSION, IncomingFiles.kindOf(bytes.copyOf(IncomingFiles.PeekBytes)))
        assertEquals(IncomingKind.TRACK, IncomingFiles.kindOf("  {\"format\"".toByteArray()))
        assertEquals(IncomingKind.UNKNOWN, IncomingFiles.kindOf("PK\u0003\u0004".toByteArray()))
    }

    @Test
    fun `session ids that could leave the session folder are refused`() {
        assertTrue(SessionInbox.validId("20251011-080000-ab12cd34"))
        for (bad in listOf("", "..", "a/b", "-x", "a b", "x".repeat(65))) assertFalse(SessionInbox.validId(bad), bad)
    }

    @Test
    fun `a big video file streams through without being held in memory`() {
        val src = File(tmp, "big.bin")
        val size = 20L shl 20
        src.outputStream().use { out ->
            repeat((size shr 16).toInt()) { out.write(ByteArray(1 shl 16) { it.toByte() }) }
        }
        val bundle = File(tmp, "big.bundle")
        bundle.outputStream().use { o ->
            BundleWriter(o).apply {
                src.inputStream().use { file("session/video/big.mp4", size, it) }
                finish()
            }
        }
        var seen = 0L
        bundle.inputStream().use { input ->
            BundleReader(input).read { _, s, contents ->
                assertEquals(size, s)
                val buf = ByteArray(8192)
                while (true) {
                    val n = contents.read(buf)
                    if (n < 0) break
                    seen += n
                }
            }
        }
        assertEquals(size, seen)
        assertContentEquals(src.readBytes().copyOf(16), ByteArray(16) { it.toByte() })
    }
}
