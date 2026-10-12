@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.LapKind
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PicksTest {
    private val sim = SimCircuit()
    private val track = sim.track()
    private val steady = SimCircuit.Pace()

    /** The lap timer's frame, which the coach's corner positions are in. */
    private val timerFrame = LapCollector(track).frame

    /**
     * A pick of bend [k] as the lap analysis would make it: the bend's line every 5 m from 10 m before
     * it to 10 m after it, moved by [shift] (another line through the bend), named [name].
     */
    private fun SimCircuit.pick(k: Int, name: String = "Corner ${k + 1}", shift: Xy = Xy(0.0, 0.0)): CornerPick {
        val from = apexes[k] - arc / 2 - 10
        val points = (0..((arc + 20) / 5).toInt()).map { frame.toGeo(xy(from + it * 5.0) + shift) }
        return CornerPick(name, points.map { it.lat }, points.map { it.lon }, "s1")
    }

    private fun CornerPick.reversed() = copy(lats = lats.reversed(), lons = lons.reversed())

    private fun reference(paces: List<SimCircuit.Pace> = List(3) { steady }): CoachReference {
        val laps = tracedLaps(track, sim.drive(paces).fixes.asSequence())
        return assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
    }

    private fun cornerCues(engine: CueEngine, drive: SimCircuit.Drive): List<Cue> =
        drive.fixes.flatMap { engine.update(it).cues }.filter { it.kind == CueKind.Corner }

    @Test
    fun `picks are matched to the coach's corners by position, not by name or number`() {
        val map = reference().map()
        // The analysis may call the third bend anything; where it is decides.
        assertEquals(setOf(3), pickedCorners(map, timerFrame, listOf(sim.pick(2, name = "Corner 1"))))
        assertEquals(setOf(1, 4), pickedCorners(map, timerFrame, listOf(sim.pick(0), sim.pick(3))))
        // Another line through the bend, 8 m wide of the reference lap, is the same corner.
        assertEquals(setOf(2), pickedCorners(map, timerFrame, listOf(sim.pick(1, shift = Xy(8.0, -4.0)))))
        assertTrue(pickedCorners(map, timerFrame, emptyList()).isEmpty())
    }

    @Test
    fun `a pick on a straight, far off the track or driven the other way matches no corner`() {
        val map = reference().map()
        val straight = sim.frame.toGeo(sim.xy(sim.apexes[1] + 150))
        val onStraight = CornerPick("Straight", listOf(straight.lat), listOf(straight.lon))
        assertTrue(pickedCorners(map, timerFrame, listOf(onStraight)).isEmpty())
        assertTrue(pickedCorners(map, timerFrame, listOf(sim.pick(1, shift = Xy(60.0, 60.0)))).isEmpty())
        assertTrue(pickedCorners(map, timerFrame, listOf(sim.pick(1).reversed())).isEmpty())
    }

    @Test
    fun `corners found in this session match picks too`() {
        val engine = CueEngine(track, CoachSettings(Verbosity.Focus), picks = listOf(sim.pick(2)))
        assertTrue(engine.pickedCorners.isEmpty())
        sim.drive(List(2) { steady }).fixes.forEach { engine.update(it) }
        assertEquals(4, engine.corners.size)
        assertEquals(setOf(3), engine.pickedCorners)
    }

    @Test
    fun `the same corner picked in another session toggles off, another corner toggles on`() {
        val first = sim.pick(1)
        val again = sim.pick(1, name = "Corner 2", shift = Xy(5.0, 3.0)).copy(sessionId = "s2")
        assertTrue(first.sameCorner(again))
        assertFalse(first.sameCorner(sim.pick(2)))
        val picks = listOf(first).toggled(sim.pick(2))
        assertEquals(listOf("Corner 2", "Corner 3"), picks.map { it.name })
        assertEquals(listOf("Corner 3"), picks.toggled(again).map { it.name })
    }

    @Test
    fun `picks are stored per track, read back, cleared, and a damaged file is no picks`() {
        val dir = Files.createTempDirectory("picks").toFile()
        try {
            val store = CoachPicksStore(dir)
            assertTrue(store.get("sim").isEmpty())
            store.save("sim", listOf(sim.pick(1), sim.pick(3)))
            store.save("other-track", listOf(sim.pick(0)))
            assertEquals(listOf(sim.pick(1), sim.pick(3)), store.get("sim"))
            assertEquals(listOf("other-track", "sim"), store.all().keys.toList())
            assertEquals(listOf("sim.json", "other-track.json").sorted(), dir.list().orEmpty().sorted())
            store.save("sim", emptyList())
            assertTrue(store.get("sim").isEmpty())
            assertEquals(setOf("other-track"), store.all().keys)
            File(dir, "other-track.json").writeText("{ not json")
            assertTrue(store.get("other-track").isEmpty())
            assertTrue(store.all().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Braking early everywhere, by a different amount in each corner: corner 1 loses least. */
    private val earlyEverywhere = SimCircuit.Pace(earlierM = listOf(12.0, 18.0, 24.0, 30.0))

    @Test
    fun `at focus a picked corner is cued first and the losses fill the rest`() {
        val paces = List(7) { earlyEverywhere }
        val plain = cornerCues(CueEngine(track, CoachSettings(Verbosity.Focus), reference()), sim.drive(paces))
        assertEquals(setOf(2, 3, 4), plain.mapNotNull { it.corner }.toSet(), "cues: ${plain.map { it.text }}")
        val engine = CueEngine(track, CoachSettings(Verbosity.Focus), reference(), picks = listOf(sim.pick(0)))
        val picked = cornerCues(engine, sim.drive(paces))
        assertEquals(setOf(1), engine.pickedCorners)
        // Corner 1 takes a focus place; corner 2, losing least of the others, gives up its own.
        assertEquals(setOf(1, 3, 4), picked.mapNotNull { it.corner }.toSet(), "cues: ${picked.map { it.text }}")
    }

    @Test
    fun `picks that match nothing leave the coach as it was`() {
        val paces = List(7) { earlyEverywhere }
        val straight = sim.frame.toGeo(sim.xy(sim.apexes[1] + 150))
        val nowhere = CornerPick("Corner 9", listOf(straight.lat), listOf(straight.lon))
        for (v in listOf(Verbosity.Focus, Verbosity.Full)) {
            val plain = cornerCues(CueEngine(track, CoachSettings(v), reference()), sim.drive(paces))
            val engine = CueEngine(track, CoachSettings(v), reference(), picks = listOf(nowhere))
            val unmatched = cornerCues(engine, sim.drive(paces))
            assertEquals(plain, unmatched)
        }
    }

    /** A circuit whose second bend comes 60 m after the first: too short a straight for its own cue. */
    private val tight = SimCircuit(w = 600.0, h = 160.0)
    private val tightTrack = tight.track()

    private fun tightReference(): CoachReference {
        val laps = tracedLaps(tightTrack, tight.drive(List(3) { steady }).fixes.asSequence())
            .filter { it.lap.kind == LapKind.Timed }
        return assertNotNull(buildReference(tightTrack, listOf(SessionLaps("earlier", laps))))
    }

    @Test
    fun `a picked corner wins the straight it competes for at full`() {
        val early12 = SimCircuit.Pace(earlierM = listOf(15.0, 15.0, 0.0, 0.0))
        val paces = List(5) { early12 }
        val reference = tightReference()
        assertEquals(4, reference.corners.size)
        val plain = cornerCues(CueEngine(tightTrack, CoachSettings(Verbosity.Full), reference), tight.drive(paces))
        // Without picks the second corner's straight is too short: only the first corner is cued.
        assertEquals(listOf(1), plain.mapNotNull { it.corner }.distinct(), "cues: ${plain.map { it.text }}")
        val engine = CueEngine(tightTrack, CoachSettings(Verbosity.Full), reference, picks = listOf(tight.pick(1)))
        val drive = tight.drive(paces)
        val picked = mutableListOf<Cue>()
        val at = mutableListOf<Double>()
        drive.fixes.forEachIndexed { i, f ->
            engine.update(f).cues.filter { it.kind == CueKind.Corner }.forEach {
                picked += it
                at += drive.distance[i] % tight.length
            }
        }
        assertEquals(setOf(2), engine.pickedCorners)
        // The picked corner's cue wins the straight before the first corner and is said there, before
        // the first corner's braking point. On the lap after, while the driver tries it, the first
        // corner gets its own cue back.
        assertEquals(listOf(2, 1, 2), picked.map { it.corner }, "cues: ${picked.map { it.text }}")
        assertEquals(listOf(5, 6, 7), picked.map { it.lap })
        picked.indices.filter { picked[it].corner == 2 }.forEach { i ->
            assertTrue(at[i] < tight.brakePoint(0, early12), "said at ${at[i]} m")
        }
    }
}
