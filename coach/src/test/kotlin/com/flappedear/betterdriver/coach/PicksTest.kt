@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.Xy
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `the other leg of a hairpin, driven the other way, is not the same corner`() {
        // The same stretch reversed stands in for a leg 0 m away driven the opposite way.
        val pick = sim.pick(1)
        assertFalse(pick.sameCorner(pick.reversed()))
        assertFalse(pick.reversed().sameCorner(pick))
        assertEquals(2, listOf(pick).toggled(pick.reversed()).size)
    }

    @Test
    fun `a pick with a coordinate out of range or not finite is refused, and its file reads as no picks`() {
        assertFailsWith<IllegalArgumentException> { CornerPick("C", listOf(95.0), listOf(16.8)) }
        assertFailsWith<IllegalArgumentException> { CornerPick("C", listOf(52.4), listOf(-181.0)) }
        assertFailsWith<IllegalArgumentException> { CornerPick("C", listOf(Double.NaN), listOf(16.8)) }
        assertFailsWith<IllegalArgumentException> { CornerPick("C", listOf(52.4), listOf(Double.POSITIVE_INFINITY)) }
        val dir = Files.createTempDirectory("picks").toFile()
        try {
            val store = CoachPicksStore(dir.path)
            store.save("sim", listOf(sim.pick(1)))
            val f = File(dir, "sim.json")
            f.writeText(f.readText().replaceFirst(Regex("\"lats\":\\[[-0-9.]+"), "\"lats\":[95.0"))
            assertTrue(f.readText().contains("95.0"))
            assertTrue(store.get("sim").isEmpty())
            assertTrue(store.all().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `picks are stored per track, read back, cleared, and a damaged file is no picks`() {
        val dir = Files.createTempDirectory("picks").toFile()
        try {
            val store = CoachPicksStore(dir.path)
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

    @Test
    fun `at full a pick changes no cue and no cue timing`() {
        val paces = List(7) { earlyEverywhere }
        val plain = cornerCues(CueEngine(track, CoachSettings(Verbosity.Full), reference()), sim.drive(paces))
        val engine = CueEngine(track, CoachSettings(Verbosity.Full), reference(), picks = listOf(sim.pick(1)))
        val picked = cornerCues(engine, sim.drive(paces))
        assertEquals(setOf(2), engine.pickedCorners)
        assertTrue(plain.isNotEmpty())
        // One cue per straight, for the corner that follows it, at the same moments.
        assertEquals(plain, picked)
    }
}
