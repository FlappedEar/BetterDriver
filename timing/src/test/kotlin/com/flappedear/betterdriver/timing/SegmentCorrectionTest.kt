@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.SegmentEdit
import com.flappedear.betterdriver.timing.track.SegmentKind
import com.flappedear.betterdriver.timing.track.SegmentShape
import com.flappedear.betterdriver.timing.track.SegmentType
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackRevision
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.timing.track.adoptedOver
import com.flappedear.betterdriver.timing.track.corrected
import com.flappedear.betterdriver.timing.track.edited
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The driver's corrections of the detected corners and straights (FBBD-179). */
class SegmentCorrectionTest {
    private val circuit = FakeCircuit()
    private val bend = PI * circuit.radius
    private val bend1 = 200.0..200.0 + bend
    private val bend2 = bend1.endInclusive + 400.0..bend1.endInclusive + 400.0 + bend

    /** The top straight, with a kink at its middle the detection took for a corner (c2). */
    private val kinkStart = bend1.endInclusive + 180.0
    private val kinkEnd = bend1.endInclusive + 220.0

    private val shapes = listOf(
        SegmentShape(SegmentKind.Corner, bend1.start, bend1.endInclusive),
        SegmentShape(SegmentKind.Straight, bend1.endInclusive, kinkStart),
        SegmentShape(SegmentKind.Corner, kinkStart, kinkEnd),
        SegmentShape(SegmentKind.Straight, kinkEnd, bend2.start),
        SegmentShape(SegmentKind.Corner, bend2.start, bend2.endInclusive),
        SegmentShape(SegmentKind.Straight, bend2.endInclusive, bend1.start),
    )

    private fun lap(offset: Double = 0.0): List<Xy> = (0 until (circuit.length / 2).toInt()).map { i ->
        val p = circuit.xy(i * 2.0)
        val h = circuit.xy(i * 2.0 + 0.5) - circuit.xy(i * 2.0 - 0.5)
        p + Xy(h.y, -h.x) * (offset / h.length())
    }

    /** As detected: Corner 1 (c1), Straight 1 (s1), the kink Corner 2 (c2), Straight 2 (s2), Corner 3 (c3), ... */
    private val detected = TrackRevision.first(circuit.frame, lap(), closed = true, shapes)

    private fun TrackRevision.apply(edit: SegmentEdit) = assertNotNull(corrected(edit), "$edit")

    private fun ids(r: TrackRevision) = r.segments.map { it.id }

    private fun names(r: TrackRevision) = r.segments.map { it.name }

    /** Every corner of [before] not in [touched] keeps its CornerId, and the mapping says so. */
    private fun assertUntouchedKeepIds(before: TrackRevision, after: TrackRevision, touched: Set<String>) {
        for (c in before.corners.filter { it.id !in touched }) {
            assertNotNull(after.segment(c.id), "${c.id} kept")
            assertEquals(c.id, after.previousIds[c.id], "${c.id} maps to itself")
        }
    }

    @Test
    fun `as detected`() {
        assertEquals(listOf("c1", "s1", "c2", "s2", "c3", "s3"), ids(detected))
        assertEquals(
            listOf("Corner 1", "Straight 1", "Corner 2", "Straight 2", "Corner 3", "Straight 3"),
            names(detected),
        )
        assertFalse(detected.corrected)
    }

    @Test
    fun `rename keeps the revision and the ids, and the numbered name comes back`() {
        val named = detected.apply(SegmentEdit.Rename("c3", "  Parabolica "))
        assertEquals(1, named.number)
        assertEquals(ids(detected), ids(named))
        assertEquals("Parabolica", named.segment("c3")?.name)
        assertEquals("Corner 3", named.segment("c3")?.numberedName)
        assertTrue(named.corrected && named.edited("c3") && !named.edited("c1"))
        assertEquals(detected.segments, named.detected)
        // Blank, or the numbered name typed back, is no name: the detected segments again.
        assertEquals(detected, named.apply(SegmentEdit.Rename("c3", " ")))
        assertEquals(detected, named.apply(SegmentEdit.Rename("c3", "Corner 3")))
        assertNull(detected.corrected(SegmentEdit.Rename("c3", "x".repeat(41))))
        assertNull(detected.corrected(SegmentEdit.Rename("c9", "Nowhere")))
    }

    @Test
    fun `moving a boundary makes the next revision and every id maps to itself`() {
        val start = detected.segment("c3")!!.startMeters
        val moved = detected.apply(SegmentEdit.MoveBoundary("c3", start - 30.0))
        assertEquals(2, moved.number)
        assertEquals(ids(detected), ids(moved))
        assertEquals(start - 30.0, moved.segment("c3")!!.startMeters, 0.01)
        assertEquals(start - 30.0, moved.segment("s2")!!.endMeters, 0.01)
        assertEquals(detected.segments.associate { it.id to it.id }, moved.previousIds)
        assertUntouchedKeepIds(detected, moved, emptySet())
        // Never past the segment before, or within 10 m of its start.
        val s2 = detected.segment("s2")!!
        assertNull(detected.corrected(SegmentEdit.MoveBoundary("c3", s2.startMeters + 5.0)))
        assertNull(detected.corrected(SegmentEdit.MoveBoundary("c3", detected.segment("c3")!!.endMeters + 1.0)))
        // Across the start/finish line on a circuit: the start of corner 1 moves into the straight before it.
        assertNotNull(detected.corrected(SegmentEdit.MoveBoundary("s1", bend1.endInclusive - 20.0)))
        assertNotNull(detected.corrected(SegmentEdit.MoveBoundary("c1", 20.0)))
        val wrapped = detected.apply(SegmentEdit.MoveBoundary("s3", detected.lengthMeters - 20.0))
        assertEquals(detected.lengthMeters - 20.0, wrapped.segment("s3")!!.startMeters, 0.01)
    }

    @Test
    fun `splitting a corner gives the second part a new id, numbers follow the lap, the rest keep theirs`() {
        val c3 = detected.segment("c3")!!
        val middle = (c3.startMeters + c3.endMeters) / 2
        val split = detected.apply(SegmentEdit.Split("c3", middle))
        assertEquals(2, split.number)
        assertEquals(listOf("c1", "s1", "c2", "s2", "c3", "c4", "s3"), ids(split))
        assertEquals(listOf(1, 2, 3, 4), split.corners.map { it.number })
        assertEquals("Corner 4", split.segment("c4")?.name)
        assertEquals(middle, split.segment("c3")!!.endMeters, 0.01)
        assertEquals(middle, split.segment("c4")!!.startMeters, 0.01)
        assertEquals(c3.endMeters, split.segment("c4")!!.endMeters, 0.01)
        assertUntouchedKeepIds(detected, split, emptySet())
        assertEquals(5, split.nextCornerId)
        assertNull(detected.corrected(SegmentEdit.Split("c3", c3.startMeters + 4.0)))
    }

    @Test
    fun `merging two corners makes one chain under the first id`() {
        val merged = detected.apply(SegmentEdit.Merge("c2")).apply(SegmentEdit.Merge("c2"))
        // The kink merged with the straight after it, then with the last bend: one corner, two corners long.
        assertEquals(3, merged.number)
        assertEquals(listOf("c1", "s1", "c2", "s3"), ids(merged))
        assertEquals("Corners 2–3", merged.segment("c2")?.name)
        assertEquals(mapOf("c1" to "c1", "s1" to "s1", "c2" to "c2", "c3" to "c2", "s3" to "s3"), merged.previousIds)
        val once = detected.apply(SegmentEdit.Merge("s1"))
        assertEquals(mapOf("s1" to "c2", "c2" to "c2"), once.previousIds.filterKeys { it in setOf("s1", "c2") })
        assertUntouchedKeepIds(detected, once, setOf("c2"))
    }

    @Test
    fun `removing a false corner joins the straights either side, keeping every other id`() {
        val removed = detected.apply(SegmentEdit.Remove("c2"))
        assertEquals(2, removed.number)
        assertEquals(listOf("c1", "s1", "c3", "s3"), ids(removed))
        assertEquals(listOf("Corner 1", "Straight 1", "Corner 2", "Straight 2"), names(removed))
        assertEquals(bend1.endInclusive, removed.segment("s1")!!.startMeters, 1.0)
        assertEquals(detected.segment("s2")!!.endMeters, removed.segment("s1")!!.endMeters, 0.01)
        assertEquals("s1", removed.previousIds["c2"])
        assertEquals("s1", removed.previousIds["s2"])
        assertUntouchedKeepIds(detected, removed, setOf("c2"))
        // Down to two segments, nothing more can go.
        val two = removed.apply(SegmentEdit.Remove("c3"))
        assertEquals(listOf("c1", "s1"), ids(two))
        assertNull(two.corrected(SegmentEdit.Remove("c1")))
        assertNull(two.corrected(SegmentEdit.Merge("c1")))
    }

    @Test
    fun `a chicane keeps the revision, a corner made a straight or back gets a new id`() {
        val chicane = detected.apply(SegmentEdit.ChangeType("c3", SegmentType.Chicane))
        assertEquals(1, chicane.number)
        assertEquals("Chicane 3", chicane.segment("c3")?.name)
        assertEquals(SegmentType.Chicane, chicane.segment("c3")?.type)
        assertEquals(SegmentKind.Corner, chicane.segment("c3")?.kind)

        val straight = detected.apply(SegmentEdit.ChangeType("c2", SegmentType.Straight))
        assertEquals(2, straight.number)
        assertEquals(listOf("c1", "s1", "s4", "s2", "c3", "s3"), ids(straight))
        assertEquals("s4", straight.previousIds["c2"])
        assertEquals("Corner 2", straight.segment("c3")?.name)
        assertUntouchedKeepIds(detected, straight, setOf("c2"))

        val corner = straight.apply(SegmentEdit.ChangeType("s4", SegmentType.Corner))
        assertEquals("c4", corner.previousIds["s4"])
        assertEquals(SegmentType.Corner, corner.segment("c4")?.type)
        assertSame(detected, detected.corrected(SegmentEdit.ChangeType("c1", SegmentType.Corner)))
    }

    @Test
    fun `restore automatic returns to the detected segments, mapping each correction back`() {
        val c3 = detected.segment("c3")!!
        val corrected = detected
            .apply(SegmentEdit.Remove("c2"))
            .apply(SegmentEdit.Split("c3", (c3.startMeters + c3.endMeters) / 2))
            .apply(SegmentEdit.Rename("c1", "Turn One"))
        assertEquals(3, corrected.number)
        val restored = corrected.apply(SegmentEdit.RestoreAutomatic)
        assertEquals(4, restored.number)
        assertEquals(detected.segments, restored.segments)
        assertNull(restored.detected)
        // The split's new corner goes back to the corner it came from; ids are never reused.
        assertEquals("c3", restored.previousIds["c4"])
        assertEquals("c1", restored.previousIds["c1"])
        assertEquals(5, restored.nextCornerId)
        // A revision that was only named keeps its number.
        val named = detected.apply(SegmentEdit.Rename("c1", "Turn One"))
        assertEquals(detected, named.apply(SegmentEdit.RestoreAutomatic))
        assertSame(detected, detected.corrected(SegmentEdit.RestoreAutomatic))
    }

    @Test
    fun `each correction persists across a restart, and one made to stale corners is refused`() {
        val c3 = detected.segment("c3")!!
        val edits = listOf(
            SegmentEdit.Rename("c3", "Parabolica"),
            SegmentEdit.MoveBoundary("c3", c3.startMeters + 15.0),
            SegmentEdit.Split("c3", (c3.startMeters + c3.endMeters) / 2),
            SegmentEdit.Merge("c2"),
            SegmentEdit.Remove("c2"),
            SegmentEdit.ChangeType("c3", SegmentType.Chicane),
            SegmentEdit.ChangeType("c2", SegmentType.Straight),
        )
        for (edit in edits) {
            val dir = Files.createTempDirectory("tracks").toFile()
            val store = TrackStore(dir)
            val plain = store.save(circuit.track(), nowUtcMillis = 1_000)
            store.updateIdentity(plain.id, null, detected)
            val after = assertNotNull(store.correctIdentity(plain.id, detected, edit, nowUtcMillis = 5_000))
            val expected = detected.apply(edit)
            assertEquals(expected, after.identity, "$edit")
            // The lines and their revision stay; the update time moves, so a backup merge keeps it.
            assertEquals(plain.revision, after.revision)
            assertEquals(5_000, after.updatedUtcMillis)
            // As after a restart.
            assertEquals(expected, TrackStore(dir).get(plain.id)?.identity, "$edit after a restart")
            // A correction made to the corners as they were before is not applied over the new ones.
            assertEquals(expected, store.correctIdentity(plain.id, detected, SegmentEdit.Remove("c1"))?.identity)
            // An edit of the lines from a copy read before the correction keeps it.
            assertEquals(expected, store.save(plain.copy(name = "Renamed"), nowUtcMillis = 6_000).identity)
            // Restore automatic, also after a restart.
            TrackStore(dir).correctIdentity(plain.id, expected, SegmentEdit.RestoreAutomatic)
            val restored = assertNotNull(TrackStore(dir).get(plain.id)?.identity)
            assertEquals(detected.segments, restored.segments, "$edit restored")
        }
    }

    @Test
    fun `corrections travel in the track file and survive a layout change for the corners that stay`() {
        val corrected = detected.apply(SegmentEdit.Remove("c2")).apply(SegmentEdit.Rename("c3", "Parabolica"))
            .apply(SegmentEdit.ChangeType("c1", SegmentType.Chicane))
        val track = circuit.track().copy(identity = corrected)
        assertEquals(track, TrackJson.decode(TrackJson.encode(track)))

        // A loop added on the top straight: the next revision is made from a lap, the named corners keep their names.
        val bumped = lap().map { p ->
            if (p.y > 0 && p.x in -60.0..60.0) Xy(p.x, p.y + 40.0 * (1 + cos(PI * p.x / 60.0)) / 2) else p
        }
        assertFalse(corrected.fits(circuit.frame, bumped))
        val along = DoubleArray(bumped.size).also { a ->
            for (i in 1 until bumped.size) a[i] = a[i - 1] + (bumped[i] - bumped[i - 1]).length()
        }
        fun at(s: Double) = along[(s / 2).toInt()]
        val loopStart = bend1.endInclusive + 140.0
        val loopEnd = bend1.endInclusive + 260.0
        val newShapes = listOf(
            SegmentShape(SegmentKind.Corner, at(bend1.start), at(bend1.endInclusive)),
            SegmentShape(SegmentKind.Straight, at(bend1.endInclusive), at(loopStart)),
            SegmentShape(SegmentKind.Corner, at(loopStart), at(loopEnd)),
            SegmentShape(SegmentKind.Straight, at(loopEnd), at(bend2.start)),
            SegmentShape(SegmentKind.Corner, at(bend2.start), at(bend2.endInclusive)),
            SegmentShape(SegmentKind.Straight, at(bend2.endInclusive), at(bend1.start)),
        )
        val revised = corrected.revised(circuit.frame, bumped, newShapes, along)
        assertEquals(corrected.number + 1, revised.number)
        assertEquals("Parabolica", revised.segment("c3")?.name)
        assertEquals(SegmentType.Chicane, revised.segment("c1")?.type)
        assertNull(revised.detected)
    }

    @Test
    fun `a corrected identity received from the other device keeps this device's ids for the corners both have`() {
        // This device made its own, from another line, numbered further on.
        val here = TrackRevision.first(circuit.frame, lap(offset = 3.0), closed = true, shapes)
            .copy(number = 5, nextCornerId = 9, nextStraightId = 9)
        val c3 = detected.segment("c3")!!
        // Split two thirds in, so the middle of this device's corner is in the first part.
        val received = detected.apply(SegmentEdit.Remove("c2"))
            .apply(SegmentEdit.Split("c3", c3.startMeters + (c3.endMeters - c3.startMeters) * 2 / 3))
            .apply(SegmentEdit.Rename("c1", "Turn One"))
        val kept = received.adoptedOver(here)
        assertEquals(6, kept.number)
        assertEquals(listOf("c1", "s1", "c3", "c9", "s3"), ids(kept))
        assertEquals(listOf("Turn One", "Straight 1", "Corner 2", "Corner 3", "Straight 2"), names(kept))
        assertEquals(
            mapOf("c1" to "c1", "s1" to "s1", "c2" to "s1", "s2" to "s1", "c3" to "c3", "s3" to "s3"),
            kept.previousIds,
        )
        // Restore automatic there goes back to the received detection, under this device's ids.
        val restored = kept.apply(SegmentEdit.RestoreAutomatic)
        assertEquals(6, restored.segments.size)
        assertTrue(restored.segments.map { it.id }.containsAll(listOf("c1", "s1", "c3", "s3")))
        assertSame(received, received.adoptedOver(null))
    }
}
