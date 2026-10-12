@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.identity.IdentityMigrations
import com.flappedear.betterdriver.analysis.identity.migrateTrackIdentities
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackEvent
import com.flappedear.betterdriver.timing.track.TrackStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Corner identities for tracks and sessions stored before them (FBBD-172). */
class IdentityMigrationTest {
    @TempDir
    lateinit var tmp: File

    private val rectangle = SimTrack.rectangle("rectangle")
    private val hairpin = SimTrack.hairpin("hairpin")

    /** A track file as builds before corner identities wrote it: no identity field at all. */
    private fun oldTrackFile(dir: File, t: Track) {
        val line = t.startFinish!!
        dir.mkdirs()
        File(dir, "${t.id}.track.json").writeText(
            """
            {
                "format": "betterdriver-track",
                "version": 1,
                "track": {
                    "id": "${t.id}",
                    "name": "${t.name}",
                    "center": { "lat": ${t.center.lat}, "lon": ${t.center.lon} },
                    "radiusMeters": 2000.0,
                    "startFinish": {
                        "left": { "lat": ${line.left.lat}, "lon": ${line.left.lon} },
                        "right": { "lat": ${line.right.lat}, "lon": ${line.right.lon} }
                    },
                    "sectorLines": [],
                    "revision": 3,
                    "updatedUtcMillis": 1786000000000
                }
            }
            """.trimIndent(),
        )
    }

    /** Every session's lap times against [tracks], by session id. */
    private fun lapTimes(store: SessionStore, tracks: List<Track>): Map<String, List<Double>> =
        store.list().associate { s ->
            val data = store.read(s.dir)
            val named = TrackEvent.savedTrackId(data.events)
            val track = tracks.firstOrNull { it.id == named } ?: tracks.first { it.id == "hairpin" }
            s.id to deriveSourceLapSession(telemetrySessionOf(data, track)).timedLaps.map { it.durationSeconds }
        }

    /** A digest of every file under [dir], so any byte written to a session shows. */
    private fun digest(dir: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
            md.update(it.relativeTo(dir).path.toByteArray())
            md.update(it.readBytes())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `stored tracks get corner ids from their sessions, and nothing is lost`() {
        val trackDir = File(tmp, "tracks")
        oldTrackFile(trackDir, rectangle.track())
        oldTrackFile(trackDir, hairpin.track())
        // A track with no session yet: it gets its identity when it is first analysed.
        oldTrackFile(trackDir, SimTrack.rectangle("empty", GeoPoint(48.0, 11.0)).track())
        val tracks = TrackStore(trackDir)
        val sessions = SessionStore(File(tmp, "sessions"))
        val stored = tracks.all()
        rectangle.record(sessions, stored.single { it.id == "rectangle" }, laps = 4, startUtc = 1_000)
        rectangle.record(sessions, stored.single { it.id == "rectangle" }, laps = 3, startUtc = 2_000)
        // Recorded before the hairpin was a track: found by where it drove.
        hairpin.record(sessions, null, laps = 4, startUtc = 3_000)
        // A road drive somewhere else.
        SimTrack.rectangle("road", GeoPoint(45.0, 7.0)).record(sessions, null, laps = 2, startUtc = 4_000)

        val tracksBefore = tracks.all()
        assertTrue(tracksBefore.all { it.identity == null })
        val sessionsBefore = sessions.list().map { it.id }.sorted()
        val timesBefore = lapTimes(sessions, tracksBefore)
        val bytesBefore = digest(sessions.root)

        val migrated = migrateTrackIdentities(tracks, sessions)

        assertEquals(setOf("rectangle", "hairpin"), migrated.toSet())
        val after = tracks.all()
        // The same tracks, every field as it was but the identity; the lines' revision does not move.
        assertEquals(tracksBefore.size, after.size)
        assertEquals(tracksBefore, after.map { it.copy(identity = null) })
        for (id in migrated) {
            val identity = assertNotNull(after.single { it.id == id }.identity, id)
            assertEquals(1, identity.number)
            assertTrue(identity.corners.size >= 4, "$id: ${identity.segments.map { it.name }}")
        }
        assertNull(after.single { it.id == "empty" }.identity)
        // Sessions are only read: the same sessions, the same bytes, the same lap times.
        assertEquals(sessionsBefore, sessions.list().map { it.id }.sorted())
        assertEquals(bytesBefore, digest(sessions.root))
        assertEquals(timesBefore, lapTimes(sessions, after))
        assertTrue(timesBefore.values.sumOf { it.size } >= 9, "$timesBefore")

        // The analysis names the stored sessions' corners by the migrated ids.
        val rect = after.single { it.id == "rectangle" }
        for (s in sessions.list()) {
            val data = sessions.read(s.dir)
            if (TrackEvent.savedTrackId(data.events) != rect.id) continue
            val analysis = analyzeSession(telemetrySessionOf(data, rect), identity = rect.identity)
            assertEquals(rect.identity, analysis.identity)
            val ids = analysis.segments!!.segments.map { it.id }.toSet()
            assertEquals(rect.identity!!.segments.map { it.id }.toSet(), ids)
        }

        // Run again (the next app start): nothing changes.
        assertEquals(emptyList(), migrateTrackIdentities(tracks, sessions))
        assertEquals(after, tracks.all())
        // Read back as a new store, as after a restart.
        assertEquals(after, TrackStore(trackDir).all())
    }

    @Test
    fun `a track added later gets its identity at the next refresh, and an unchanged refresh does nothing`() {
        val trackDir = File(tmp, "tracks")
        oldTrackFile(trackDir, rectangle.track())
        val tracks = TrackStore(trackDir)
        val sessions = SessionStore(File(tmp, "sessions"))
        rectangle.record(sessions, tracks.all().single(), laps = 3, startUtc = 1_000)
        val migrations = IdentityMigrations()
        assertEquals(listOf("rectangle"), migrations.runIfChanged(tracks, sessions))

        // Nothing new: not run, so a session that would now be read is not.
        var asked = 0
        assertEquals(emptyList(), migrations.runIfChanged(tracks, sessions) { asked++ > 0 })
        assertEquals(0, asked)

        // A track imported (or restored) after the first run, with a session recorded there.
        oldTrackFile(trackDir, hairpin.track())
        hairpin.record(sessions, tracks.get("hairpin"), laps = 3, startUtc = 2_000)
        assertEquals(listOf("hairpin"), migrations.runIfChanged(tracks, sessions))
        assertNotNull(tracks.get("hairpin")?.identity)

        // A run stopped part way is run again at the next refresh.
        val later = SimTrack.rectangle("later", GeoPoint(48.0, 11.0))
        oldTrackFile(trackDir, later.track())
        later.record(sessions, tracks.get("later"), laps = 3, startUtc = 3_000)
        assertEquals(emptyList(), migrations.runIfChanged(tracks, sessions) { true })
        assertNull(tracks.get("later")?.identity)
        assertEquals(listOf("later"), migrations.runIfChanged(tracks, sessions))
    }

    @Test
    fun `a track changed to a point-to-point course is given an identity of its new kind`() {
        val trackDir = File(tmp, "tracks")
        oldTrackFile(trackDir, rectangle.track())
        val tracks = TrackStore(trackDir)
        val sessions = SessionStore(File(tmp, "sessions"))
        rectangle.record(sessions, tracks.all().single(), laps = 3, startUtc = 1_000)
        migrateTrackIdentities(tracks, sessions)
        val track = assertNotNull(tracks.get("rectangle"))
        val r1 = assertNotNull(track.identity)
        // As the editor would leave it if the circuit had been a point-to-point course: the identity is
        // of the other kind, so it is made again from the sessions, keeping the corner ids.
        assertNotNull(tracks.updateIdentity(track.id, r1, r1.copy(closed = false)))
        assertEquals(listOf("rectangle"), migrateTrackIdentities(tracks, sessions))
        val r2 = assertNotNull(tracks.get("rectangle")?.identity)
        assertTrue(r2.closed)
        assertEquals(2, r2.number)
        assertEquals(r1.corners.map { it.id }, r2.corners.map { it.id })
    }
}
