@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.SegmentKind
import com.flappedear.betterdriver.timing.track.SegmentShape
import com.flappedear.betterdriver.timing.track.TrackRevision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The coach names and picks corners by the track's corner identity (FBBD-172). */
class CornerIdentityTest {
    private val sim = SimCircuit()

    /**
     * The track's corners as the lap analysis made them: the first bend is a chain of two corners
     * ("Corners 1–2"), so the bends are corners 1–2, 3, 4 and 5, not the coach's 1 to 4.
     */
    private val identity: TrackRevision = run {
        val path = (0 until (sim.length / 2).toInt()).map { sim.xy(it * 2.0) }
        val bends = sim.apexes.map { it - sim.arc / 2 to it + sim.arc / 2 }
        val shapes = bends.flatMapIndexed { k, (start, end) ->
            val next = bends[(k + 1) % bends.size].first
            listOf(
                SegmentShape(SegmentKind.Corner, start, end, corners = if (k == 0) 2 else 1),
                SegmentShape(SegmentKind.Straight, end, next),
            )
        }
        TrackRevision.first(sim.frame, path, closed = true, shapes)
    }
    private val track = sim.track().copy(identity = identity)
    private val frame = assertNotNull(lapFrame(track))

    private fun reference(): CoachReference {
        val laps = tracedLaps(track, sim.drive(List(3) { SimCircuit.Pace() }).fixes.asSequence())
        return assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
    }

    private fun pickOf(k: Int, id: String? = null, revision: Int? = null): CornerPick {
        val from = sim.apexes[k] - sim.arc / 2 - 10
        val points = (0..((sim.arc + 20) / 5).toInt()).map { sim.frame.toGeo(sim.xy(from + it * 5.0)) }
        return CornerPick("Corner", points.map { it.lat }, points.map { it.lon }, "s1", id, revision)
    }

    @Test
    fun `the coach says the track's corner numbers`() {
        assertEquals(listOf("Corners 1–2", "Corner 3", "Corner 4", "Corner 5"), identity.corners.map { it.name })
        val engine = CueEngine(track, CoachSettings(Verbosity.Full), reference())
        val corners = engine.corners
        assertEquals(listOf(1, 2, 3, 4), corners.map { it.number })
        assertEquals(listOf("c1", "c2", "c3", "c4"), corners.map { it.cornerId })
        assertEquals(listOf(1, 3, 4, 5), corners.map { it.label })
        assertEquals("Turn 3, brake earlier", Phrases.corner(corners[1], Advice.BrakeEarlier))
        // Without an identity, the coach's own numbers, as before.
        val plain = CueEngine(sim.track(), CoachSettings(Verbosity.Full), reference()).corners
        assertEquals(listOf(1, 2, 3, 4), plain.map { it.label })
        assertTrue(plain.all { it.cornerId == null })
    }

    @Test
    fun `cues are logged and read back with the number said`() {
        val corner = identifyCorners(reference().corners, reference().path(), frame, identity)[1]
        val cue = Cue(
            7, 1_000, 4, CueKind.Corner, Phrases.corner(corner, Advice.BrakeEarlier), corner.number,
            Advice.BrakeEarlier, turn = corner.label, cornerId = corner.cornerId,
        )
        val read = assertNotNull(CoachEvents.parseCue(1_000, CoachEvents.cueText(cue)))
        assertEquals(3, read.heardTurn)
        assertEquals("c2", read.cornerId)
        // A cue logged before corner identities: the coach's own number is what was said.
        val old = assertNotNull(CoachEvents.parseCue(0, "seq=1 lap=3 kind=Corner corner=2 text=Turn 2, brake earlier"))
        assertEquals(2, old.heardTurn)
        assertNull(old.cornerId)
    }

    @Test
    fun `picks are matched by corner id, and old picks by where their line is`() {
        val map = identified(reference().map(), frame, identity)
        // By id, whatever the line says: c3 is the third bend.
        assertEquals(setOf(3), pickedCorners(map, frame, listOf(pickOf(0, id = "c3", revision = 1)), identity))
        // A pick from before corner identities has only its line: it is placed on the identity.
        assertEquals("c2", pickOf(1).cornerIdIn(identity))
        assertEquals(setOf(2, 4), pickedCorners(map, frame, listOf(pickOf(1), pickOf(3)), identity))
        // A pick from the revision before is mapped onto this one.
        val next = identity.copy(number = 2, previousIds = mapOf("c9" to "c1"))
        assertEquals(setOf(1), pickedCorners(identified(map, frame, next), frame, listOf(pickOf(2, "c9", 1)), next))
        // Two picks of the same corner id are the same corner, however they were driven; other ids are not.
        assertTrue(pickOf(0, "c2", 1).sameCorner(pickOf(2, "c2", 1).copy(sessionId = "s2")))
        assertFalse(pickOf(1, "c2", 1).sameCorner(pickOf(1, "c3", 1)))
    }

    @Test
    fun `a braking corner off the track's centreline keeps the coach's own number`() {
        val far = reference().corners
        val moved = TrackRevision.first(
            sim.frame,
            (0 until (sim.length / 2).toInt()).map { sim.xy(it * 2.0) + Xy(500.0, 500.0) },
            closed = true,
            identity.segments.map { SegmentShape(it.kind, it.startMeters, it.endMeters) },
        )
        val named = identifyCorners(far, reference().path(), frame, moved)
        assertTrue(named.all { it.cornerId == null && it.label == it.number })
    }
}
