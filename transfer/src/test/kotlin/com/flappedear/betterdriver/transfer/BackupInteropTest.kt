@file:Suppress("MagicNumber") // Test sizes and positions.

package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.SetupJson
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.timing.track.TrackStore
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared backup code (BackupWriter, BackupRestorer: what the iOS app runs, FBBD-372) against
 * Android's Backup and BackupRestore: a backup made by either restores with the other, the same way.
 */
class BackupInteropTest {
    @TempDir
    lateinit var tmp: File

    private val center = GeoPoint(52.0, 21.0)
    private var ids = 0

    private fun track(id: String, revision: Int = 1) = Track(
        id = id,
        name = "Track $id",
        center = center,
        startFinish = TrackLine.across(center, 90.0),
        revision = revision,
    )

    private fun device(name: String): File {
        val root = File(tmp, name)
        val tracks = TrackStore(File(root, "tracks"))
        tracks.save(track("a"), nowUtcMillis = 1_000)
        tracks.save(track("b"), nowUtcMillis = 2_000)
        val setup = Setup()
            .addEquipment(EquipmentModel.RACEBOX, "RaceBox Mini 1", "AA:BB:CC:DD:EE:01", newId = { "id-${ids++}" })
            .addCar("FR-S", newId = { "id-${ids++}" })
        File(root, "setup.json").writeText(SetupJson.encode(setup))
        File(root, "coach/references").mkdirs()
        File(root, "coach/settings.properties").writeText("verbosity=NORMAL\n")
        File(root, "coach/references/a.json").writeText("{\"reference\":1}")
        File(root, "imu-calibration").mkdirs()
        File(root, "imu-calibration/AABBCCDDEE01.txt").writeText("1 0 0")
        File(root, "logs").mkdirs()
        File(root, "logs/app.log").writeText("never backed up")
        return root
    }

    private fun session(root: File, start: Long = 1_700_000_000_000): String {
        val store = SessionStore(File(root, "sessions"))
        val (id, dir) = store.createSession(SessionInfo(start, "FR-S", "Track a"))
        File(dir, SessionFiles.chunkName(1)).writeBytes(Random(start).nextBytes(150_000))
        val finished = Properties().apply { setProperty("endMicros", "1000000") }
        SessionFiles.writeAtomically(File(dir, SessionFiles.FINISHED), finished)
        File(dir, "video").mkdirs()
        File(dir, "video/phone_0001.mp4").writeBytes(Random(1).nextBytes(100_000))
        return id
    }

    /** A backup as the iOS app writes it: the shared writer, file pieces read with java.io here. */
    private fun sharedBackup(root: File, sessions: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        BackupWriter.write(
            root = root.path,
            includeSessions = sessions,
            appVersion = "1.25.0 (build 1480)",
            nowUtcMillis = 1_760_000_000_000,
            prefs = null,
            out = { b, from, to -> out.write(b, from, to - from) },
            readFile = { path, block ->
                File(path).inputStream().use { input ->
                    val buf = ByteArray(7_000) // Pieces smaller than a chunk, as iOS reads 64 KiB.
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        block(buf, n)
                    }
                }
            },
        )
        return out.toByteArray()
    }

    private fun androidBackup(root: File, sessions: Boolean) = ByteArrayOutputStream().also {
        val prefs = mapOf("setup_done" to true)
        Backup.write(root, prefs, "1.24.0 (build 99)", sessions, it, nowUtcMillis = 1_760_000_000_000)
    }.toByteArray()

    /** The java.io staging writer the test gives the shared unpack (iOS gives its stdio one). */
    private class JvmStaging(private val dir: String) : StagingFiles {
        private var out: FileOutputStream? = null

        override fun create(path: String) {
            val f = File(dir, path)
            f.parentFile.mkdirs()
            out = FileOutputStream(f)
        }

        override fun write(bytes: ByteArray, from: Int, to: Int) {
            out!!.write(bytes, from, to - from)
        }

        override fun close() {
            out?.close()
            out = null
        }

        override fun freeBytes(): Long = Long.MAX_VALUE
    }

    private fun sharedRestorer(root: File) = BackupRestorer(root.path, File(root, "backup-staging").path)

    /** Unpacks [bytes] with the shared code, fed in pieces of [piece] bytes. */
    private fun sharedUnpack(root: File, bytes: ByteArray, piece: Int = 4_096) =
        sharedRestorer(root).unpack({ JvmStaging(it) }) { feed ->
            var i = 0
            while (i < bytes.size) {
                val n = minOf(piece, bytes.size - i)
                feed(bytes, i, i + n)
                i += n
            }
        }

    private fun files(root: File, vararg areas: String): Map<String, String> = areas.flatMap { area ->
        File(root, area).walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText(Charsets.ISO_8859_1) }.toList()
    }.toMap()

    @Test
    fun `a backup from the shared writer restores on Android with everything`() {
        val iphone = device("iphone")
        val id = session(iphone)
        val bytes = sharedBackup(iphone, sessions = true)
        val pixel = File(tmp, "pixel").apply { mkdirs() }

        val r = BackupRestore(pixel, File(pixel, "backup-staging"))
        val unpacked = r.unpack(bytes.inputStream())
        assertEquals(2, unpacked.summary.tracks)
        assertEquals(1, unpacked.summary.equipment)
        assertEquals(1, unpacked.summary.cars)
        assertEquals(1, unpacked.summary.sessions)
        assertTrue(unpacked.summary.withSessions)
        assertFalse(unpacked.summary.prefs, "the iPhone keeps none of Android's preferences")
        assertEquals("1.25.0 (build 1480)", unpacked.summary.appVersion)
        var prefsWritten: Map<String, Any>? = null
        val result = r.restore(unpacked, RestoreMode.MERGE, emptyMap()) { prefsWritten = it }

        assertEquals(2, result.tracksAdded)
        assertEquals(1, result.sessionsAdded)
        assertNull(prefsWritten)
        val areas = arrayOf("tracks", "coach", "imu-calibration")
        assertEquals(files(iphone, *areas), files(pixel, *areas))
        assertEquals(File(iphone, "setup.json").readText(), File(pixel, "setup.json").readText())
        assertTrue(File(pixel, "sessions/$id/${SessionFiles.chunkName(1)}").isFile)
        assertFalse(File(pixel, "sessions/$id/video").exists(), "video never goes into a backup")
        assertFalse(File(pixel, "logs").exists())
    }

    @Test
    fun `an Android backup restores with the shared code, merge then replace`() {
        val pixel = device("pixel")
        val id = session(pixel)
        val bytes = androidBackup(pixel, sessions = true)
        val iphone = File(tmp, "iphone").apply { mkdirs() }
        TrackStore(File(iphone, "tracks")).save(track("c"), nowUtcMillis = 5_000)

        val staged = sharedUnpack(iphone, bytes)
        assertEquals(2, staged.summary.tracks)
        assertTrue(staged.summary.prefs)
        assertEquals(1, staged.summary.sessions)
        val merged = sharedRestorer(iphone).restore(staged, RestoreMode.MERGE)

        assertEquals(2, merged.tracksAdded)
        assertEquals(1, merged.equipmentAdded)
        assertEquals(1, merged.carsAdded)
        assertEquals(1, merged.sessionsAdded)
        assertEquals(setOf("a", "b", "c"), TrackStore(File(iphone, "tracks")).all().map { it.id }.toSet())
        assertEquals(files(pixel, "coach", "imu-calibration"), files(iphone, "coach", "imu-calibration"))
        assertTrue(File(iphone, "sessions/$id/meta.properties").isFile)
        assertEquals(
            File(pixel, "sessions/$id/${SessionFiles.chunkName(1)}").readBytes().toList(),
            File(iphone, "sessions/$id/${SessionFiles.chunkName(1)}").readBytes().toList(),
        )

        val replaced = sharedRestorer(iphone).restore(sharedUnpack(iphone, bytes, piece = 1), RestoreMode.REPLACE)
        assertEquals(1, replaced.sessionsKept, "a session here is never replaced")
        assertEquals(setOf("a", "b"), TrackStore(File(iphone, "tracks")).all().map { it.id }.toSet())
        assertFalse(File(iphone, "backup-staging/restore").exists())
    }

    @Test
    fun `the shared writer writes what Android's writer writes, entry for entry`() {
        val root = device("both")
        session(root)
        val shared = Backup.unpack(sharedBackup(root, sessions = true).inputStream(), File(tmp, "s1"))
        val android = Backup.unpack(androidBackup(root, sessions = true).inputStream(), File(tmp, "s2"))
        assertEquals(android.summary.copy(prefs = false, appVersion = ""), shared.summary.copy(appVersion = ""))
        val areas = arrayOf("tracks", "coach", "imu-calibration", "sessions", "setup.json")
        assertEquals(files(android.dir, *areas), files(shared.dir, *areas))
    }

    @Test
    fun `a damaged backup is refused by the shared code and leaves nothing behind`() {
        val bytes = androidBackup(device("pixel"), sessions = false)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        val iphone = File(tmp, "iphone").apply { mkdirs() }
        assertFailsWith<BundleDamagedException> { sharedUnpack(iphone, bytes) }
        assertEquals(emptyList(), File(iphone, "backup-staging").list().orEmpty().toList())
        assertFailsWith<BundleDamagedException> { sharedUnpack(iphone, sharedBackup(device("x"), false).copyOf(100)) }
        assertFailsWith<BundleDamagedException> {
            sharedUnpack(iphone, ByteArrayOutputStream().also { SessionBundleWriterProbe.bundle(it) }.toByteArray())
        }
    }

    @Test
    fun `a restore cut short after its commit is finished at the next start`() {
        val bytes = androidBackup(device("pixel"), sessions = false)
        val iphone = File(tmp, "iphone").apply { mkdirs() }
        val r = sharedRestorer(iphone)
        val staged = sharedUnpack(iphone, bytes)
        assertFailsWith<IllegalStateException> {
            r.restore(staged, RestoreMode.MERGE, beforeCommit = { error("recording started") })
        }
        assertFalse(File(iphone, "tracks").exists(), "nothing changes when stopped before the commit")
        assertFalse(r.pending)
        r.clearStaging()
        assertFalse(File(iphone, "backup-staging").exists())
    }

    @Test
    fun `track files and the kind of a file are the same on both apps`() {
        val t = track("x").copy(name = "Tor Jastrząb / short")
        assertEquals("Tor-Jastrząb-short.bdtrack", TrackShare.fileName(t))
        assertEquals(TrackTransfer.fileName(t), TrackShare.fileName(t))
        val bytes = TrackShare.encode(t)
        assertEquals(t, TrackTransfer.read(bytes.inputStream()))
        assertEquals(IncomingKind.TRACK, IncomingFiles.kindOf(bytes))
        assertEquals(IncomingKind.BACKUP, TrackShare.kindOf(androidBackup(device("p"), false).copyOf(8)))
        assertFailsWith<BundleDamagedException> { TrackShare.decode(ByteArray(TrackShare.MaxTrackBytes + 1)) }
        assertFailsWith<BundleDamagedException> { TrackShare.decode("not json".encodeToByteArray()) }
    }
}

/** A session bundle (not a backup), to show the backup reader refuses one. */
private object SessionBundleWriterProbe {
    fun bundle(out: ByteArrayOutputStream) {
        val packer = BundlePacker({ b, from, to -> out.write(b, from, to - from) })
        packer.file("bundle.properties", "format=betterdriver-session\n".encodeToByteArray())
        packer.finish()
    }
}
