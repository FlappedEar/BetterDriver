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
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackKind
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.timing.track.TrackStore
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {
    @TempDir
    lateinit var tmp: File

    private val center = GeoPoint(52.0, 21.0)

    private fun track(id: String, revision: Int = 1, updated: Long = 0, heading: Double = 90.0) = Track(
        id = id,
        name = "Track $id",
        center = center,
        startFinish = TrackLine.across(center, heading),
        revision = revision,
        updatedUtcMillis = updated,
    )

    private var ids = 0
    private fun newId() = "id-${ids++}"

    /** An app files folder with a bit of everything, as the app lays it out. */
    private fun device(name: String): File {
        val root = File(tmp, name)
        val tracks = TrackStore(File(root, "tracks"))
        tracks.save(track("a"), nowUtcMillis = 1_000)
        tracks.save(track("b"), nowUtcMillis = 2_000)
        val setup = Setup()
            .addEquipment(EquipmentModel.RACEBOX, "RaceBox Mini 1", "AA:BB:CC:DD:EE:01", ::newId)
            .addEquipment(EquipmentModel.PHONE_CAMERA, newId = ::newId)
            .addCar("FR-S", newId = ::newId)
        File(root, "setup.json").writeText(SetupJson.encode(setup))
        File(root, "coach").mkdirs()
        File(root, "coach/settings.properties").writeText("verbosity=NORMAL\n")
        File(root, "coach/references").mkdirs()
        File(root, "coach/references/a.json").writeText("{\"reference\":1}")
        File(root, "imu-calibration").mkdirs()
        File(root, "imu-calibration/AABBCCDDEE01.txt").writeText("1 0 0")
        File(root, "logs").mkdirs()
        File(root, "logs/app.log").writeText("debug log, never backed up")
        File(root, "session-setup.json").writeText("{}")
        return root
    }

    /** A finished session with a chunk, a marker and video that a backup leaves out. */
    private fun session(root: File, startUtcMillis: Long = 1_700_000_000_000): String {
        val store = SessionStore(File(root, "sessions"))
        val (id, dir) = store.createSession(SessionInfo(startUtcMillis, "FR-S", "Track a"))
        File(dir, SessionFiles.chunkName(1)).writeBytes(Random(startUtcMillis).nextBytes(50_000))
        SessionFiles.writeAtomically(
            File(dir, SessionFiles.FINISHED),
            Properties().apply { setProperty("endMicros", "1000000") },
        )
        File(dir, "video").mkdirs()
        File(dir, "video/phone_0001.mp4").writeBytes(Random(1).nextBytes(200_000))
        File(dir, "video/clips.json").writeText("{}")
        return id
    }

    private fun backup(root: File, prefs: Map<String, Any?> = mapOf("setup_done" to true), sessions: Boolean = false) =
        ByteArrayOutputStream().also {
            Backup.write(root, prefs, "0.3.0 (build 99)", sessions, it, nowUtcMillis = 1_760_000_000_000)
        }.toByteArray()

    private fun files(root: File, vararg areas: String): Map<String, String> = areas.flatMap { area ->
        File(root, area).walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }.toList()
    }.toMap()

    private fun restorer(root: File) = BackupRestore(root, File(root, "backup-staging"))

    private fun restore(
        root: File,
        bytes: ByteArray,
        mode: RestoreMode,
        prefsHere: Map<String, Any?> = emptyMap(),
        beforeCommit: () -> Unit = {},
    ): Pair<RestoreResult, Map<String, Any>?> {
        val r = restorer(root)
        var written: Map<String, Any>? = null
        val result = r.restore(r.unpack(bytes.inputStream()), mode, prefsHere, beforeCommit) { written = it }
        return result to written
    }

    @Test
    fun `a backup restored on an empty device brings everything back, without logs or video`() {
        val phone = device("phone")
        val id = session(phone)
        val prefs = mapOf("setup_done" to true, "count" to 3, "big" to 5L, "f" to 1.5f, "s" to "x")
        val bytes = backup(phone, prefs = prefs)
        val tablet = File(tmp, "tablet").apply { mkdirs() }

        val r = restorer(tablet)
        val unpacked = r.unpack(bytes.inputStream())
        val summary = unpacked.summary
        assertEquals(1_760_000_000_000, summary.createdUtcMillis)
        assertEquals("0.3.0 (build 99)", summary.appVersion)
        assertEquals(listOf(2, 2, 1, 0, 1, 2), listOf(
            summary.tracks, summary.equipment, summary.cars, summary.sessions, summary.calibrations, summary.coachFiles,
        ))
        assertTrue(summary.prefs)
        assertFalse(summary.withSessions)
        var written: Map<String, Any>? = null
        val result = r.restore(unpacked, RestoreMode.MERGE, emptyMap()) { written = it }

        assertEquals(2, result.tracksAdded)
        assertEquals(2, result.equipmentAdded)
        assertEquals(1, result.carsAdded)
        assertEquals(prefs, written)
        val areas = arrayOf("tracks", "setup.json", "coach", "imu-calibration")
        assertEquals(files(phone, *areas), files(tablet, *areas))
        assertFalse(File(tablet, "logs").exists(), "debug logs are never in a backup")
        assertFalse(File(tablet, "session-setup.json").exists())
        assertFalse(File(tablet, "sessions/$id").exists(), "sessions only with the option")
        assertFalse(File(tablet, "backup-staging/restore").exists(), "nothing left in staging")
        assertEquals(emptyList(), File(tablet, "backup-staging").listFiles()!!.toList())
    }

    @Test
    fun `point-to-point tracks come back with their kind and finish line`() {
        val phone = device("phone")
        val hill = track("hill").copy(
            kind = TrackKind.PointToPoint,
            finish = TrackLine.across(GeoPoint(52.01, 21.0), 0.0),
        )
        val stored = TrackStore(File(phone, "tracks")).save(hill, nowUtcMillis = 3_000)
        // Version 2 on disk, so the check before a restore must read it.
        assertTrue(File(phone, "tracks/hill.track.json").readText().contains("\"version\": 2"))
        val bytes = backup(phone)

        val tablet = File(tmp, "tablet").apply { mkdirs() }
        val (added, _) = restore(tablet, bytes, RestoreMode.MERGE)
        assertEquals(3, added.tracksAdded)
        assertEquals(stored, TrackStore(File(tablet, "tracks")).get("hill"))

        // An older circuit copy here is updated to the point-to-point track; replace brings it back too.
        val old = File(tmp, "old").apply { mkdirs() }
        TrackStore(File(old, "tracks")).save(track("hill"), nowUtcMillis = 1_000)
        val (merged, _) = restore(old, bytes, RestoreMode.MERGE)
        assertEquals(1, merged.tracksUpdated)
        assertEquals(stored, TrackStore(File(old, "tracks")).get("hill"))
        TrackStore(File(old, "tracks")).delete("hill")
        restore(old, bytes, RestoreMode.REPLACE)
        assertEquals(stored, TrackStore(File(old, "tracks")).get("hill"))
    }

    @Test
    fun `with sessions each session's data travels, its video does not`() {
        val phone = device("phone")
        val id = session(phone)
        val sizes = Backup.sessionsSize(phone)
        assertEquals(1, sizes.first)
        val bytes = backup(phone, sessions = true)
        assertTrue(bytes.size < 200_000, "no video in the backup")
        val tablet = File(tmp, "tablet").apply { mkdirs() }

        val (result, _) = restore(tablet, bytes, RestoreMode.MERGE)

        assertEquals(1, result.sessionsAdded)
        val here = File(tablet, "sessions/$id")
        assertEquals(
            File(phone, "sessions/$id/${SessionFiles.chunkName(1)}").readBytes().toList(),
            File(here, SessionFiles.chunkName(1)).readBytes().toList(),
        )
        assertFalse(File(here, "video").exists())
        assertEquals(id, SessionStore(File(tablet, "sessions")).list().single().id)
    }

    @Test
    fun `a session already here is kept, never replaced by one without video`() {
        val phone = device("phone")
        val id = session(phone)
        val bytes = backup(phone, sessions = true)
        File(phone, "sessions/$id/${SessionFiles.chunkName(1)}").writeText("the copy here")

        val (result, _) = restore(phone, bytes, RestoreMode.REPLACE)

        assertEquals(0, result.sessionsAdded)
        assertEquals(1, result.sessionsKept)
        assertEquals("the copy here", File(phone, "sessions/$id/${SessionFiles.chunkName(1)}").readText())
        assertTrue(File(phone, "sessions/$id/video/phone_0001.mp4").isFile)
    }

    @Test
    fun `a backup opened as an import is recognised as one`() {
        val bytes = backup(device("phone"))
        assertEquals(IncomingKind.BACKUP, IncomingFiles.kindOf(IncomingFiles.head(bytes.inputStream())))
    }

    @Test
    fun `progress counts every file byte written`() {
        val phone = device("phone")
        session(phone)
        var written = 0L
        ByteArrayOutputStream().use { Backup.write(phone, emptyMap(), "1", true, it) { n -> written += n } }
        assertEquals(Backup.size(phone, includeSessions = true), written)
    }

    @Test
    fun `a damaged or cut-short file is refused before anything changes`() {
        val phone = device("phone")
        session(phone)
        val bytes = backup(phone, sessions = true)
        val tablet = device("tablet")
        val before = files(tablet, "tracks", "setup.json", "coach", "imu-calibration")
        val r = restorer(tablet)

        val flipped = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }
        assertFailsWith<BundleFormatException> { r.unpack(flipped.inputStream()) }
        assertFailsWith<BundleFormatException> { r.unpack(bytes.copyOf(bytes.size - 3).inputStream()) }
        assertFailsWith<BundleFormatException> { r.unpack("not a backup at all".byteInputStream()) }
        val sessions = SessionStore(File(phone, "sessions"))
        val session = ByteArrayOutputStream().also { SessionBundle.write(sessions, sessions.list()[0].dir, null, it) }
            .toByteArray()
        assertFailsWith<BundleFormatException> { r.unpack(session.inputStream()) }

        assertEquals(before, files(tablet, "tracks", "setup.json", "coach", "imu-calibration"))
        assertEquals(emptyList(), File(tablet, "backup-staging").listFiles()!!.toList(), "staging cleaned up")
    }

    @Test
    fun `a backup whose track does not decode is refused even with good CRCs`() {
        val root = File(tmp, "bad").apply { mkdirs() }
        File(root, "tracks").mkdirs()
        File(root, "tracks/x.track.json").writeText("{ not a track")
        val bytes = backup(root)
        assertFailsWith<BundleFormatException> { restorer(File(tmp, "t")).unpack(bytes.inputStream()) }
    }

    @Test
    fun `an oversized track or setup is refused before it is read`() {
        fun withEntry(path: String, size: Long): ByteArray {
            val out = ByteArrayOutputStream()
            BundleWriter(out, Backup.Magic).apply {
                val m = "format=${Backup.Format}\nversion=1\ncreated=1\n".toByteArray()
                file(Backup.ManifestEntry, m.size.toLong(), m.inputStream())
                file(path, size, java.io.ByteArrayInputStream(ByteArray(size.toInt()) { ' '.code.toByte() }))
                finish()
            }
            return out.toByteArray()
        }
        val tablet = File(tmp, "t")
        val limits = listOf("tracks/x.track.json" to Backup.MaxTrackBytes, "setup.json" to Backup.MaxSetupBytes)
        for ((path, max) in limits) {
            val e = assertFailsWith<BundleFormatException> {
                restorer(tablet).unpack(withEntry(path, max + 1).inputStream())
            }
            assertTrue(e.message!!.contains("too large"), e.message)
        }
        assertEquals(emptyList(), File(tablet, "backup-staging").listFiles()!!.toList())
    }

    @Test
    fun `session metadata that is huge or claims absurd channels is refused`() {
        val phone = device("phone")
        val id = session(phone)
        val good = File(phone, "sessions/$id/${SessionFiles.META}").readText()
        val tablet = File(tmp, "tablet")
        fun withMeta(text: String): ByteArray {
            val out = ByteArrayOutputStream()
            BundleWriter(out, Backup.Magic).apply {
                val m = "format=${Backup.Format}\nversion=1\ncreated=1\n".toByteArray()
                file(Backup.ManifestEntry, m.size.toLong(), m.inputStream())
                val meta = text.toByteArray()
                file("sessions/$id/${SessionFiles.META}", meta.size.toLong(), meta.inputStream())
                finish()
            }
            return out.toByteArray()
        }

        restorer(tablet).unpack(withMeta(good).inputStream()) // the real one is fine
        val absurd = good.replace(Regex("(?m)^channels=.*$"), "") + "channels=2000000000\n"
        val e = assertFailsWith<BundleFormatException> { restorer(tablet).unpack(withMeta(absurd).inputStream()) }
        assertTrue(e.message!!.contains("channel count"), e.message)
        val huge = good + "#" + " ".repeat(Backup.MaxMetaBytes.toInt()) + "\n"
        val big = assertFailsWith<BundleFormatException> { restorer(tablet).unpack(withMeta(huge).inputStream()) }
        assertTrue(big.message!!.contains("too large"), big.message)
    }

    @Test
    fun `a backup from a newer app is refused`() {
        val out = ByteArrayOutputStream()
        BundleWriter(out, Backup.Magic).apply {
            val m = "format=${Backup.Format}\nversion=99\ncreated=1\n".toByteArray()
            file(Backup.ManifestEntry, m.size.toLong(), m.inputStream())
            finish()
        }
        val e = assertFailsWith<BundleFormatException> {
            restorer(File(tmp, "t")).unpack(out.toByteArray().inputStream())
        }
        assertTrue(e.message!!.contains("newer"))
    }

    @Test
    fun `merge keeps the newer of each track by revision then update time`() {
        val phone = device("phone")
        val store = TrackStore(File(phone, "tracks"))
        store.save(track("c", revision = 5, heading = 10.0), nowUtcMillis = 5_000)
        val bytes = backup(phone)
        val tablet = device("tablet")
        val here = TrackStore(File(tablet, "tracks"))
        here.save(track("a", revision = 7, heading = 45.0), nowUtcMillis = 9_000) // a higher revision here
        // Same revision as the backup's, updated earlier.
        File(tablet, "tracks/b.track.json").writeText(TrackJson.encode(track("b", 1, 1, heading = 30.0)))

        val (result, _) = restore(tablet, bytes, RestoreMode.MERGE)

        assertEquals(1, result.tracksAdded)
        assertEquals(1, result.tracksUpdated)
        assertEquals(7, here.get("a")!!.revision, "the newer track here stays")
        assertEquals(track("a", heading = 45.0).startFinish, here.get("a")!!.startFinish)
        assertEquals(2_000, here.get("b")!!.updatedUtcMillis, "same revision, later update wins")
        assertEquals(track("b").startFinish, here.get("b")!!.startFinish)
        assertEquals(5, here.get("c")!!.revision, "missing here: added")
    }

    @Test
    fun `newer compares revision first, then update time`() {
        assertTrue(RestoreRules.newer(track("a", 3, 0), track("a", 2, 9_999)))
        assertFalse(RestoreRules.newer(track("a", 2, 9_999), track("a", 3, 0)))
        assertTrue(RestoreRules.newer(track("a", 2, 2), track("a", 2, 1)))
        assertFalse(RestoreRules.newer(track("a", 2, 1), track("a", 2, 1)))
    }

    @Test
    fun `merge adds missing equipment and cars, and keeps the choice here`() {
        val incoming = Setup()
            .addEquipment(EquipmentModel.RACEBOX, "RB", "AA:BB:CC:DD:EE:01", { "rb-backup" })
            .addEquipment(EquipmentModel.PHONE_CAMERA, newId = { "phone-backup" })
            .addEquipment(EquipmentModel.GOPRO, "Hero", "11:22:33:44:55:66", { "gopro" })
            .addCar("FR-S", newId = { "frs" })
            .addCar("Clio", newId = { "clio" })
        val here = Setup()
            .addEquipment(EquipmentModel.PHONE_CAMERA, newId = { "phone-here" })
            .addCar("FR-S", newId = { "frs" })
            .chooseCar("frs")

        val merged = RestoreRules.mergeSetup(here, incoming)

        assertEquals(listOf("phone-here", "rb-backup", "gopro"), merged.equipment.map { it.id }, "one phone camera")
        assertEquals(listOf("frs", "clio"), merged.cars.map { it.id })
        assertEquals("frs", merged.session.carId)
        assertNull(merged.session.gnssId, "a choice here is kept")
    }

    @Test
    fun `merge into a fresh setup takes the backup's choice, mapped to the items here`() {
        val incoming = Setup()
            .addEquipment(EquipmentModel.RACEBOX, "RB", "AA:BB:CC:DD:EE:01", { "rb" })
            .addEquipment(EquipmentModel.PHONE_CAMERA, newId = { "phone-backup" })
            .addCar("FR-S", newId = { "frs" })
            .chooseCar("frs")
            .chooseGnss("rb")
            .useCamera("phone-backup", true)
        val fresh = Setup().addEquipment(EquipmentModel.PHONE_CAMERA, newId = { "phone-here" })

        val merged = RestoreRules.mergeSetup(fresh, incoming)

        assertEquals("rb", merged.session.gnssId)
        assertEquals("frs", merged.session.carId)
        assertEquals(listOf("phone-here"), merged.session.cameraIds)
    }

    @Test
    fun `merge keeps settings here and fills in what is absent`() {
        val phone = device("phone")
        File(phone, "imu-calibration/112233445566.txt").writeText("2 0 0")
        val bytes = backup(phone, prefs = mapOf("setup_done" to true, "only_backup" to "b"))
        val tablet = device("tablet")
        File(tablet, "coach/settings.properties").writeText("verbosity=QUIET\n")
        File(tablet, "imu-calibration/AABBCCDDEE01.txt").writeText("here")

        val (result, prefs) = restore(tablet, bytes, RestoreMode.MERGE, prefsHere = mapOf("setup_done" to false))

        assertEquals("verbosity=QUIET\n", File(tablet, "coach/settings.properties").readText())
        assertEquals("here", File(tablet, "imu-calibration/AABBCCDDEE01.txt").readText())
        assertEquals("2 0 0", File(tablet, "imu-calibration/112233445566.txt").readText())
        assertEquals(1, result.filesRestored)
        assertEquals(mapOf("setup_done" to false, "only_backup" to "b"), prefs)
        assertTrue(result.prefsChanged)
    }

    @Test
    fun `replace makes tracks, setup, coach and settings the backup's`() {
        val phone = device("phone")
        val bytes = backup(phone, prefs = mapOf("setup_done" to true))
        val tablet = device("tablet")
        TrackStore(File(tablet, "tracks")).save(track("only-here"))
        File(tablet, "coach/settings.properties").writeText("verbosity=QUIET\n")
        File(tablet, "coach/picks.properties").writeText("here")

        val (_, prefs) = restore(tablet, bytes, RestoreMode.REPLACE, prefsHere = mapOf("setup_done" to false, "x" to 1))

        assertEquals(listOf("a", "b"), TrackStore(File(tablet, "tracks")).all().map { it.id })
        assertEquals(
            SetupJson.decode(File(phone, "setup.json").readText()),
            SetupJson.decode(File(tablet, "setup.json").readText()),
        )
        assertEquals(files(phone, "coach"), files(tablet, "coach"))
        assertEquals(mapOf<String, Any>("setup_done" to true), prefs)
    }

    @Test
    fun `a restore stopped before its commit changes nothing`() {
        val phone = device("phone")
        session(phone)
        val bytes = backup(phone, sessions = true)
        val tablet = device("tablet")
        TrackStore(File(tablet, "tracks")).save(track("only-here"))
        val before = files(tablet, "tracks", "setup.json", "coach", "imu-calibration", "sessions")

        assertFailsWith<IOException> {
            restore(tablet, bytes, RestoreMode.REPLACE, beforeCommit = { throw IOException("recording") })
        }

        assertEquals(before, files(tablet, "tracks", "setup.json", "coach", "imu-calibration", "sessions"))
        assertEquals(emptyList(), File(tablet, "backup-staging").listFiles()!!.toList())
    }

    @Test
    fun `a restore killed after its commit is finished at the next start`() {
        val phone = device("phone")
        session(phone)
        val bytes = backup(phone, sessions = true)
        val tablet = device("tablet")
        TrackStore(File(tablet, "tracks")).save(track("only-here"))
        val r = restorer(tablet)
        val unpacked = r.unpack(bytes.inputStream())

        // The app "dies" while writing the preferences, the last step after the commit marker.
        assertFailsWith<IllegalStateException> {
            r.restore(unpacked, RestoreMode.REPLACE, emptyMap()) { error("killed") }
        }
        assertTrue(File(tablet, "backup-staging/restore/commit").isFile, "still marked to finish")
        assertTrue(r.pending)
        r.clearStaging() // must not drop a committed restore
        assertTrue(File(tablet, "backup-staging/restore/commit").isFile)

        var prefs: Map<String, Any>? = null
        assertTrue(restorer(tablet).finishPending { prefs = it })
        assertEquals(mapOf<String, Any>("setup_done" to true), prefs)
        assertEquals(listOf("a", "b"), TrackStore(File(tablet, "tracks")).all().map { it.id })
        assertEquals(1, SessionStore(File(tablet, "sessions")).list().size)
        assertFalse(File(tablet, "backup-staging/restore").exists())
        assertFalse(restorer(tablet).finishPending { error("nothing pending") })
        assertFalse(restorer(tablet).pending)
    }

    @Test
    fun `a restore killed half way through the swap is finished from where it stopped`() {
        val phone = device("phone")
        val bytes = backup(phone)
        val tablet = device("tablet")
        TrackStore(File(tablet, "tracks")).save(track("only-here"))
        val r = restorer(tablet)
        assertFailsWith<IllegalStateException> {
            r.restore(r.unpack(bytes.inputStream()), RestoreMode.REPLACE, emptyMap()) { error("killed") }
        }
        // Put the state back to "tracks moved aside, new tracks not yet in place".
        val work = File(tablet, "backup-staging/restore")
        File(work, "new").mkdirs()
        assertTrue(File(tablet, "tracks").renameTo(File(work, "new/tracks")))
        File(work, "old/tracks").mkdirs()

        assertTrue(restorer(tablet).finishPending { })
        assertEquals(listOf("a", "b"), TrackStore(File(tablet, "tracks")).all().map { it.id })
    }

    @Test
    fun `clearing staging drops an unfinished check`() {
        val phone = device("phone")
        val tablet = File(tmp, "tablet").apply { mkdirs() }
        val r = restorer(tablet)
        r.unpack(backup(phone).inputStream())
        r.clearStaging()
        assertFalse(File(tablet, "backup-staging").exists())
    }

    @Test
    fun `preferences keep their types and skip the ones a backup cannot hold`() {
        val prefs = mapOf("b" to true, "i" to 1, "l" to 2L, "f" to 0.5f, "s" to "a:b", "set" to setOf("x"), "n" to null)
        assertEquals(
            mapOf<String, Any>("b" to true, "i" to 1, "l" to 2L, "f" to 0.5f, "s" to "a:b"),
            BackupPrefs.kept(prefs),
        )
        assertFailsWith<BundleFormatException> { BackupPrefs.decode("x=i\\:nope\n".byteInputStream()) }
    }
}
