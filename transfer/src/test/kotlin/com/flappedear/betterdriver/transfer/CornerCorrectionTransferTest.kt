@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.SegmentEdit
import com.flappedear.betterdriver.timing.track.SegmentKind
import com.flappedear.betterdriver.timing.track.SegmentShape
import com.flappedear.betterdriver.timing.track.SegmentType
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.timing.track.TrackRevision
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.timing.track.corrected
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The driver's corner corrections (FBBD-179) survive a backup and its restore, and move with a shared track. */
class CornerCorrectionTransferTest {
    @TempDir
    lateinit var tmp: File

    private val center = GeoPoint(52.0, 21.0)
    private val frame = LocalFrame(center)

    /** A 1.2 km oval round [center]: two 300 m straights and two bends, sampled every 2 m. */
    private fun oval(offset: Double = 0.0): List<Xy> {
        val r = 100.0 + offset
        val straight = 300.0
        val bend = PI * r
        val length = 2 * straight + 2 * bend
        return (0 until (length / 2).toInt()).map { i -> ovalPoint(i * 2.0, r, straight, bend) }
    }

    private fun ovalPoint(s: Double, r: Double, straight: Double, bend: Double): Xy {
        val half = straight / 2
        val d = listOf(0.0, straight, straight + bend, 2 * straight + bend)
        return if (s < d[1]) {
            Xy(-half + s, -r)
        } else if (s < d[2]) {
            Xy(half + r * sin((s - d[1]) / r), -r * cos((s - d[1]) / r))
        } else if (s < d[3]) {
            Xy(half - (s - d[2]), r)
        } else {
            Xy(-half - r * sin((s - d[3]) / r), r * cos((s - d[3]) / r))
        }
    }

    private fun identity(offset: Double = 0.0): TrackRevision {
        val bend = PI * (100.0 + offset)
        val shapes = listOf(
            SegmentShape(SegmentKind.Straight, 0.0, 300.0),
            SegmentShape(SegmentKind.Corner, 300.0, 300.0 + bend),
            SegmentShape(SegmentKind.Straight, 300.0 + bend, 600.0 + bend),
            SegmentShape(SegmentKind.Corner, 600.0 + bend, 600.0 + 2 * bend),
        )
        return TrackRevision.first(frame, oval(offset), closed = true, shapes)
    }

    private val track = Track(
        id = "oval",
        name = "Oval",
        center = center,
        startFinish = TrackLine.across(frame.toGeo(Xy(-140.0, -100.0)), 90.0),
    )

    /** [store]'s track with its corners corrected: a corner split, named and called a chicane. */
    private fun correct(store: TrackStore, now: Long): Track {
        var t = assertNotNull(store.get(track.id))
        val c1 = t.identity!!.segment("c1")!!
        val edits = listOf(
            // Two thirds in, so the middle of the corner as another device has it is in the first part.
            SegmentEdit.Split("c1", c1.startMeters + (c1.endMeters - c1.startMeters) * 2 / 3),
            SegmentEdit.Rename("c2", "Hairpin"),
            SegmentEdit.ChangeType("c3", SegmentType.Chicane),
        )
        for (e in edits) t = assertNotNull(store.correctIdentity(track.id, t.identity!!, e, nowUtcMillis = now))
        assertTrue(t.identity!!.corrected)
        return t
    }

    private fun names(t: Track?) = t?.identity?.segments?.map { it.name }

    private fun backup(root: File) = ByteArrayOutputStream().also {
        Backup.write(root, emptyMap(), "0.3.9 (build 1)", false, it, nowUtcMillis = 1_760_000_000_000)
    }.toByteArray()

    private fun restore(root: File, bytes: ByteArray, mode: RestoreMode): RestoreResult {
        val r = BackupRestore(root, File(root, "backup-staging"))
        return r.restore(r.unpack(bytes.inputStream()), mode, emptyMap(), {}) {}
    }

    @Test
    fun `corrections come back from a backup, by replace and by merge over the uncorrected track`() {
        val phone = File(tmp, "phone")
        val store = TrackStore(File(phone, "tracks"))
        store.save(track, nowUtcMillis = 1_000)
        store.updateIdentity(track.id, null, identity())
        val corrected = correct(store, now = 2_000)
        val bytes = backup(phone)

        // A new phone: the corrections are back.
        val fresh = File(tmp, "fresh")
        restore(fresh, bytes, RestoreMode.REPLACE)
        assertEquals(corrected, TrackStore(File(fresh, "tracks")).get(track.id))

        // The same phone after the corrections were undone: a merge brings them back (they are newer).
        store.correctIdentity(track.id, corrected.identity!!, SegmentEdit.RestoreAutomatic, nowUtcMillis = 1_500)
        File(phone, "tracks/oval.track.json").writeText(
            TrackJson.encode(store.get(track.id)!!.copy(updatedUtcMillis = 1_000)),
        )
        val result = restore(phone, bytes, RestoreMode.MERGE)
        assertEquals(1, result.tracksUpdated)
        assertEquals(corrected.identity, TrackStore(File(phone, "tracks")).get(track.id)?.identity)
    }

    @Test
    fun `a shared track brings its corrections, kept under this device's corner ids`() {
        val phone = TrackStore(File(tmp, "phone"))
        phone.save(track, nowUtcMillis = 1_000)
        phone.updateIdentity(track.id, null, identity())
        val sent = correct(phone, now = 2_000)
        val file = TrackJson.encode(sent).toByteArray()
        val read = TrackTransfer.read(file.inputStream())
        assertTrue(TrackTransfer.carriesCorrections(read))

        // A tablet without the track takes it as it is.
        val empty = TrackStore(File(tmp, "empty"))
        assertEquals(TrackMatch.NEW, TrackTransfer.match(read, empty.get(read.id)))
        assertEquals(sent.identity, empty.save(read, adoptIdentity = true).identity)

        // A tablet with the track and corners of its own, from another lap: the corrections differ, so it asks.
        val tablet = TrackStore(File(tmp, "tablet"))
        tablet.save(track, nowUtcMillis = 500)
        val own = identity(offset = 2.0).copy(number = 3, nextCornerId = 7, nextStraightId = 7)
        tablet.updateIdentity(track.id, null, own)
        assertEquals(TrackMatch.DIFFERENT, TrackTransfer.match(read, tablet.get(read.id)))
        val kept = tablet.save(read, adoptIdentity = true)
        val identity = assertNotNull(kept.identity)
        assertEquals(names(sent), names(kept))
        assertEquals(
            listOf(
                SegmentType.Straight, SegmentType.Corner, SegmentType.Chicane, SegmentType.Straight, SegmentType.Corner,
            ),
            identity.segments.map { it.type },
        )
        // The next revision of the tablet's own, its corners under the ids they had there.
        assertEquals(4, identity.number)
        assertEquals(listOf("s1", "c1", "c7", "s2", "c2"), identity.segments.map { it.id })
        assertEquals("c1", identity.previousIds["c1"])
        assertEquals("c2", identity.previousIds["c2"])
        // Received again: the same.
        assertEquals(TrackMatch.SAME, TrackTransfer.match(read, tablet.get(read.id)))
        // Restore automatic there goes back to the corners as detected on the phone.
        val restored = assertNotNull(identity.corrected(SegmentEdit.RestoreAutomatic))
        assertEquals(listOf("Straight 1", "Corner 1", "Straight 2", "Corner 2"), restored.segments.map { it.name })
        // A track without corrections still never replaces the corners here.
        val plain = sent.copy(identity = identity())
        assertEquals(TrackMatch.SAME, TrackTransfer.match(plain, tablet.get(read.id)))
        assertEquals(identity, tablet.save(plain, adoptIdentity = TrackTransfer.carriesCorrections(plain)).identity)
    }

    @Test
    fun `a boundary moved on the other device is a different track, and taking it moves it here`() {
        // Both devices have the same track and corners: the tablet's came from the phone.
        val phone = TrackStore(File(tmp, "phone"))
        phone.save(track, nowUtcMillis = 1_000)
        phone.updateIdentity(track.id, null, identity())
        val tablet = TrackStore(File(tmp, "tablet"))
        tablet.save(assertNotNull(phone.get(track.id)), nowUtcMillis = 1_000)

        // On the phone the driver moves where the first bend starts, 20 m earlier: nothing else changes.
        val before = assertNotNull(phone.get(track.id)?.identity)
        val c1 = assertNotNull(before.segment("c1"))
        val edit = SegmentEdit.MoveBoundary("c1", c1.startMeters - 20.0)
        val moved = assertNotNull(phone.correctIdentity(track.id, before, edit, nowUtcMillis = 2_000))
        assertEquals(names(moved), names(tablet.get(track.id)))
        val read = TrackTransfer.read(TrackJson.encode(moved).toByteArray().inputStream())
        assertEquals(TrackMatch.DIFFERENT, TrackTransfer.match(read, tablet.get(track.id)))

        // Taken there, the boundary moves there too, and the same file is then the same track.
        val kept = assertNotNull(tablet.save(read, adoptIdentity = TrackTransfer.carriesCorrections(read)).identity)
        assertEquals(moved.identity?.segment("c1")?.startMeters, kept.segment("c1")?.startMeters)
        assertEquals(TrackMatch.SAME, TrackTransfer.match(read, tablet.get(track.id)))
    }
}
