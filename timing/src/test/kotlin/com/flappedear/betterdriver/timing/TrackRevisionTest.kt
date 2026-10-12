@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.SegmentKind
import com.flappedear.betterdriver.timing.track.SegmentShape
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackRevision
import com.flappedear.betterdriver.timing.track.TrackStore
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

/** The track's corner identity: revisions, placing, and keeping it in the track file (FBBD-172). */
class TrackRevisionTest {
    private val circuit = FakeCircuit()
    private val bend = PI * circuit.radius

    /** Where the two bends and two straights of [circuit] are, metres from the line. */
    private val bend1 = 200.0..200.0 + bend
    private val bend2 = bend1.endInclusive + 400.0..bend1.endInclusive + 400.0 + bend

    private val shapes = listOf(
        SegmentShape(SegmentKind.Corner, bend1.start, bend1.endInclusive),
        SegmentShape(SegmentKind.Straight, bend1.endInclusive, bend2.start),
        SegmentShape(SegmentKind.Corner, bend2.start, bend2.endInclusive),
        SegmentShape(SegmentKind.Straight, bend2.endInclusive, bend1.start),
    )

    /** One lap every 2 m, [offset] metres outwards (another line), [bump] metres north on the top straight. */
    private fun lap(offset: Double = 0.0, bump: (Double) -> Double = { 0.0 }): List<Xy> =
        (0 until (circuit.length / 2).toInt()).map { i ->
            val p = circuit.xy(i * 2.0)
            val h = circuit.xy(i * 2.0 + 0.5) - circuit.xy(i * 2.0 - 0.5)
            // Driven anticlockwise: outwards is to the right.
            val out = p + Xy(h.y, -h.x) * (offset / h.length())
            Xy(out.x, out.y + if (p.y > 0) bump(p.x) else 0.0)
        }

    private val first = TrackRevision.first(circuit.frame, lap(), closed = true, shapes)

    @Test
    fun `the first revision numbers corners and straights in lap order`() {
        assertEquals(1, first.number)
        assertEquals(listOf("c1", "s1", "c2", "s2"), first.segments.map { it.id })
        assertEquals(listOf("Corner 1", "Straight 1", "Corner 2", "Straight 2"), first.segments.map { it.name })
        assertEquals(circuit.length, first.lengthMeters, 5.0)
        // A point in the second bend is in corner 2, from either frame.
        val p = circuit.xy(bend2.start + 50.0)
        val ahead = circuit.xy(bend2.start + 51.0) - circuit.xy(bend2.start + 49.0)
        assertEquals("c2", first.segmentAt(assertNotNull(first.locate(circuit.frame, p, ahead))).id)
        // Driven the other way, the same place is not on this track.
        assertNull(first.locate(circuit.frame, p, ahead * -1.0))
        // On a straight, the nearest corner.
        assertEquals("c1", first.cornerAt(bend1.start - 20.0)?.id)
    }

    @Test
    fun `another line, or a GNSS spike, keeps the revision`() {
        assertTrue(first.fits(circuit.frame, lap(offset = 6.0)))
        assertSame(first, first.revised(circuit.frame, lap(offset = -6.0), shapes))
        val spiked = lap().toMutableList().also { it[300] = it[300] + Xy(60.0, 0.0) }
        assertSame(first, first.revised(circuit.frame, spiked, shapes))
    }

    @Test
    fun `a geometry change beyond the threshold makes a new revision with an id mapping`() {
        // A 40 m loop out of the top straight, 120 m long: a new corner, beyond the 25 m threshold.
        val bumped = lap { x -> if (x in -60.0..60.0) 40.0 * (1 + cos(PI * x / 60.0)) / 2 else 0.0 }
        assertFalse(first.fits(circuit.frame, bumped))
        val along = DoubleArray(bumped.size).also { a ->
            for (i in 1 until bumped.size) a[i] = a[i - 1] + (bumped[i] - bumped[i - 1]).length()
        }
        // The top straight is at 2 m steps from bend1's end; the loop is in the middle of it.
        fun at(s: Double) = along[(s / 2).toInt()]
        val top = bend1.endInclusive..bend2.start
        val loopStart = top.start + 140.0
        val loopEnd = top.start + 260.0
        val newShapes = listOf(
            SegmentShape(SegmentKind.Corner, at(bend1.start), at(bend1.endInclusive)),
            SegmentShape(SegmentKind.Straight, at(bend1.endInclusive), at(loopStart)),
            SegmentShape(SegmentKind.Corner, at(loopStart), at(loopEnd)),
            SegmentShape(SegmentKind.Straight, at(loopEnd), at(bend2.start)),
            SegmentShape(SegmentKind.Corner, at(bend2.start), at(bend2.endInclusive)),
            SegmentShape(SegmentKind.Straight, at(bend2.endInclusive), at(bend1.start)),
        )
        val second = first.revised(circuit.frame, bumped, newShapes, along)
        assertEquals(2, second.number)
        // The bends and the bottom straight keep their ids; the top straight's first part keeps its id,
        // the loop and the stretch after it are new.
        assertEquals(listOf("c1", "s1", "c3", "s3", "c2", "s2"), second.segments.map { it.id })
        assertEquals(mapOf("c1" to "c1", "s1" to "s1", "c2" to "c2", "s2" to "s2"), second.previousIds)
        // Numbers follow the new lap: the old corner 2 is corner 3 now.
        assertEquals(listOf(1, 2, 3), second.corners.map { it.number })
        assertEquals("Corner 3", second.segment("c2")?.name)
        // The new layout fits the new revision, and keeps it.
        assertSame(second, second.revised(circuit.frame, bumped, newShapes, along))
        // Ids are never reused: a later revision numbers new ones after c3 and s3.
        assertEquals(4, second.nextCornerId)
        assertEquals(4, second.nextStraightId)
    }

    @Test
    fun `segments are placed on another lap by position`() {
        val other = lap(offset = 4.0)
        val along = DoubleArray(other.size).also { a ->
            for (i in 1 until other.size) a[i] = a[i - 1] + (other[i] - other[i - 1]).length()
        }
        val starts = assertNotNull(first.place(circuit.frame, other, along))
        assertEquals(4, starts.size)
        // The line 4 m wide of the first lap is 4π m longer in each bend, the same on the straights.
        assertEquals(bend1.start, starts[0], 3.0)
        assertEquals(bend2.endInclusive + 8 * PI, starts[3], 3.0)
        assertTrue(starts.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `the identity is kept in the track file and survives a restart and edits`() {
        val track = circuit.track().copy(identity = first)
        assertEquals(track, TrackJson.decode(TrackJson.encode(track)))
        val dir = Files.createTempDirectory("tracks").toFile()
        val store = TrackStore(dir)
        val plain = store.save(circuit.track(), nowUtcMillis = 1_000)
        assertNull(plain.identity)
        // Stored without bumping the lines' revision or the update time.
        val withIdentity = assertNotNull(store.updateIdentity(plain.id, null, first))
        assertEquals(plain.copy(identity = first), withIdentity)
        // Another analysis that made its own from the old state does not replace it.
        val other = first.copy(segments = first.segments.map { it.copy(id = it.id + "x") })
        assertEquals(first, store.updateIdentity(plain.id, null, other)?.identity)
        // An edit of the lines from a copy read before the identity keeps it.
        val edited = store.save(plain.copy(sectorLines = emptyList()), nowUtcMillis = 2_000)
        assertEquals(first, edited.identity)
        assertEquals(first, TrackStore(dir).get(plain.id)?.identity)
    }

    @Test
    fun `a damaged identity costs only the identity, not the track`() {
        val track = circuit.track().copy(identity = first)
        val damaged = TrackJson.encode(track).replaceFirst("\"number\": 1", "\"number\": -4")
        val read = TrackJson.decode(damaged)
        assertNull(read.identity)
        assertEquals(track.copy(identity = null), read)
    }
}
