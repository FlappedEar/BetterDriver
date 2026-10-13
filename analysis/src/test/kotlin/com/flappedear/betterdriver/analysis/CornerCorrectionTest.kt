@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.identity.analyzeKeepingIdentity
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.coach.FocusTarget
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.RevisionSegment
import com.flappedear.betterdriver.timing.track.SegmentEdit
import com.flappedear.betterdriver.timing.track.SegmentKind
import com.flappedear.betterdriver.timing.track.SegmentType
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackRevision
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.timing.track.corrected
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The driver's corner corrections (FBBD-179) in the lap analysis: every session at the track is
 * analysed with them from its next analysis on, also after a restart, and going back to the detected
 * corners loses nothing of the sessions.
 */
class CornerCorrectionTest {
    @TempDir
    lateinit var tmp: File

    private val sim = SimTrack.rectangle()

    /** The track and two sessions there, analysed once so the track has its detected corners. */
    private class Setup(val tracks: File, val sessions: SessionStore, val track: Track, val detected: TrackRevision)

    private fun setup(name: String): Setup {
        val root = File(tmp, name)
        val tracks = TrackStore(File(root, "tracks"))
        val sessions = SessionStore(File(root, "sessions"))
        val track = tracks.save(sim.track(), nowUtcMillis = 1_000)
        sim.record(sessions, track, laps = 4, startUtc = 10_000)
        sim.record(sessions, track, laps = 3, startUtc = 20_000)
        analyzeAll(tracks, sessions, track.id)
        val detected = assertNotNull(tracks.get(track.id)?.identity)
        return Setup(File(root, "tracks"), sessions, tracks.get(track.id)!!, detected)
    }

    /** Every session at track [id] analysed as the app analyses it, keeping what it makes. */
    private fun analyzeAll(tracks: TrackStore, sessions: SessionStore, id: String): List<SessionAnalysis> =
        sessions.list().map { s ->
            val data = sessions.read(s.dir)
            val track = tracks.get(id)!!
            analyzeKeepingIdentity(telemetrySessionOf(data, track), track.identity, data.info.startUtcMillis) { e, m ->
                tracks.updateIdentity(id, e, m)?.identity
            }
        }

    private fun named(segments: List<RevisionSegment>) = segments.map { it.id to it.name }.sortedBy { it.first }

    /** The span of [s] along a centreline [length] long. */
    private fun span(s: RevisionSegment, length: Double) = (s.endMeters - s.startMeters).mod(length)

    private fun digest(dir: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
            md.update(it.relativeTo(dir).path.toByteArray())
            md.update(it.readBytes())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Each correction the driver can make, on the [detected] corners of the rectangle. */
    private fun edits(detected: TrackRevision): List<SegmentEdit> {
        val c1 = detected.corners[0]
        val c2 = detected.corners[1]
        val c3 = detected.corners[2]
        val c4 = detected.corners[3]
        return listOf(
            SegmentEdit.Rename(c1.id, "Turn One"),
            SegmentEdit.MoveBoundary(c2.id, c2.startMeters - 20.0),
            SegmentEdit.Split(c3.id, c3.startMeters + span(c3, detected.lengthMeters) / 2),
            SegmentEdit.Merge(c4.id),
            SegmentEdit.Remove(c2.id),
            SegmentEdit.ChangeType(c3.id, SegmentType.Chicane),
            SegmentEdit.ChangeType(c4.id, SegmentType.Straight),
        )
    }

    /** [a] was analysed with the corners of [made]: their ids, names and kinds, each about as long. */
    private fun assertAnalysedWith(made: TrackRevision, a: SessionAnalysis, what: String) {
        assertEquals(made, a.identity, "$what: analysed with the corrected corners")
        assertTrue(a.identified, what)
        val segments = assertNotNull(a.segments).segments
        assertEquals(named(made.segments), segments.map { it.id to it.name }.sortedBy { it.first }, what)
        val length = assertNotNull(a.axis).lengthMeters
        for (seg in segments) {
            val want = made.segment(seg.id)!!
            val type = if (want.kind == SegmentKind.Corner) TrackSegmentType.Corner else TrackSegmentType.Straight
            assertEquals(type, seg.type, "$what ${seg.name}")
            val got = (seg.endProgressMeters - seg.startProgressMeters).mod(length)
            val wanted = span(want, made.lengthMeters)
            assertTrue(abs(got - wanted) < 10.0, "$what ${seg.name}: $got m on the lap, $wanted m on the track")
        }
        assertTrue(made.corners.map { it.id }.containsAll(a.cornerMetrics.keys), "$what: ${a.cornerMetrics.keys}")
    }

    @Test
    fun `each correction persists across a restart and every session at the track is analysed with it`() {
        val names = setup("probe").detected.corners.map { it.name }
        assertEquals(listOf("Corner 1", "Corner 2", "Corner 3", "Corner 4"), names)
        for ((k, edit) in edits(setup("probe-edits").detected).withIndex()) {
            val s = setup("edit$k")
            val sessionBytes = digest(s.sessions.root)
            val made = assertNotNull(s.detected.corrected(edit), "$edit")
            assertNotNull(TrackStore(s.tracks).correctIdentity(s.track.id, s.detected, edit, nowUtcMillis = 5_000))

            // The next app start: a new store over the same files.
            val tracks = TrackStore(s.tracks)
            assertEquals(made, tracks.get(s.track.id)?.identity, "$edit")
            val analyses = analyzeAll(tracks, s.sessions, s.track.id)
            assertEquals(2, analyses.size)
            for (a in analyses) assertAnalysedWith(made, a, "$edit")
            // The analyses keep the correction: nothing replaced it.
            assertEquals(made, tracks.get(s.track.id)?.identity, "$edit kept")

            // Restore automatic: the detected corners again, for every session, and the sessions untouched.
            assertNotNull(tracks.correctIdentity(s.track.id, made, SegmentEdit.RestoreAutomatic))
            val back = TrackStore(s.tracks)
            assertEquals(s.detected.segments, back.get(s.track.id)?.identity?.segments, "$edit restored")
            val restored = back.get(s.track.id)!!.identity!!
            for (a in analyzeAll(back, s.sessions, s.track.id)) assertAnalysedWith(restored, a, "$edit restored")
            assertEquals(sessionBytes, digest(s.sessions.root), "$edit: sessions are only read")
        }
    }

    @Test
    fun `a focus kept before a correction follows its corner, untouched corners keeping their ids`() {
        val s = setup("focus")
        val detected = s.detected
        val c1 = detected.corners[0]
        val c3 = detected.corners[2]
        val line = detected.path(c1.startMeters, c1.endMeters)
        fun focusOn(id: String, from: List<GeoPoint>) = FocusTarget(
            id = "session/$id", role = FocusTarget.Primary, segmentId = id, cornerId = id,
            identityRevision = detected.number, name = "x", kind = "sectorGap", confidence = "HIGH",
            lats = from.map { it.lat }, lons = from.map { it.lon },
        ).pick("session")!!
        val split = assertNotNull(
            detected.corrected(SegmentEdit.Split(c3.id, c3.startMeters + span(c3, detected.lengthMeters) / 2)),
        )
        assertEquals(detected.number + 1, split.number)
        // An untouched corner keeps its id; the split one goes to its first part.
        assertEquals(c1.id, focusOn(c1.id, line).cornerIdIn(split))
        assertEquals(c3.id, focusOn(c3.id, detected.path(c3.startMeters, c3.endMeters)).cornerIdIn(split))
        // A name does not change the revision, so a focus there stays as it is.
        val named = assertNotNull(detected.corrected(SegmentEdit.Rename(c1.id, "Turn One")))
        assertEquals(detected.number, named.number)
        assertEquals(c1.id, focusOn(c1.id, line).cornerIdIn(named))
    }
}
