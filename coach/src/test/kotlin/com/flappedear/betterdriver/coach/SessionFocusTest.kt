@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The next-session focus (FBBD-20): kept per track and session under the corner's CornerId, read
 * back as the latest session's, and handed to the next session's coach, which works on the primary
 * corner first.
 */
class SessionFocusTest {
    private val sim = SimCircuit()
    private val track = sim.track()
    private val steady = SimCircuit.Pace()

    /** Bend [k]'s line every 5 m from 10 m before it to 10 m after it, as the lap analysis keeps it. */
    private fun line(k: Int): Pair<List<Double>, List<Double>> {
        val from = sim.apexes[k] - sim.arc / 2 - 10
        val points = (0..((sim.arc + 20) / 5).toInt()).map { sim.frame.toGeo(sim.xy(from + it * 5.0)) }
        return points.map { it.lat } to points.map { it.lon }
    }

    private fun target(session: String, k: Int, role: String = FocusTarget.Secondary): FocusTarget {
        val (lats, lons) = line(k)
        val id = "c${k + 1}"
        return FocusTarget(
            id = FocusTarget.idOf(session, id),
            role = role,
            segmentId = id,
            name = "Corner ${k + 1}",
            kind = "repeatedLoss",
            confidence = "HIGH",
            timeAvailableSeconds = 0.3 - k * 0.05,
            changes = listOf(FocusChangeRecord("brakingPoint", 120.0, 128.0, "m")),
            what = "Your 2 fastest passes braked 8 m later than your other 4.",
            lats = lats,
            lons = lons,
        )
    }

    private fun record(session: String, start: Long, vararg corners: Int) = SessionFocusRecord(
        trackId = "sim",
        sessionId = session,
        sessionStartUtcMillis = start,
        algorithm = "test",
        targets = corners.mapIndexed { i, k ->
            target(session, k, if (i == 0) FocusTarget.Primary else FocusTarget.Secondary)
        },
    )

    private fun reference(): CoachReference {
        val laps = tracedLaps(track, sim.drive(List(3) { steady }).fixes.asSequence())
        return assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
    }

    private fun cornerCues(engine: CueEngine, drive: SimCircuit.Drive): List<Cue> =
        drive.fixes.flatMap { engine.update(it).cues }.filter { it.kind == CueKind.Corner }

    /** Braking early everywhere, by a different amount in each corner: corner 1 loses least. */
    private val earlyEverywhere = SimCircuit.Pace(earlierM = listOf(12.0, 18.0, 24.0, 30.0))

    private fun <T> withDir(block: (File) -> T): T {
        val dir = Files.createTempDirectory("focus").toFile()
        try {
            return block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `each session's focus is kept per track and session, and the latest one with a focus is the next session's`() =
        withDir { dir ->
            val store = SessionFocusStore(dir)
            assertNull(store.latest("sim"))
            val older = record("20261011-101500-aaaa", 1_000, 2, 0)
            val newer = record("20261012-101500-bbbb", 2_000, 1)
            val empty = record("20261013-101500-cccc", 3_000)
            assertTrue(store.save(newer))
            assertTrue(store.save(older))
            assertTrue(store.save(empty))
            // The same record again is not written again.
            assertEquals(false, store.save(newer))
            assertEquals(listOf(older, newer, empty), store.history("sim"))
            assertEquals(newer, store.get("sim", newer.sessionId))
            // A later session with nothing clear enough leaves the earlier focus standing.
            assertEquals(newer, store.latest("sim"))
            // The session being driven never hands its own focus to itself.
            assertEquals(older, store.latest("sim", exclude = newer.sessionId))
            assertNull(store.latest("other"))
            // Kept under the track, one file per session.
            assertEquals(3, File(dir, "sim").list().orEmpty().size)

            // A damaged file is no record.
            File(dir, "sim/${newer.sessionId}.json").writeText("{ not json")
            assertEquals(older, store.latest("sim"))

            // Deleting the track deletes its records.
            assertTrue(store.delete("sim"))
            assertTrue(store.history("sim").isEmpty())
        }

    @Test
    fun `a deleted session's focus is gone, and one whose session is gone is never the next session's`() =
        withDir { dir ->
            val store = SessionFocusStore(dir)
            val older = record("s1", 1_000, 0)
            val newer = record("s2", 2_000, 1)
            store.save(older)
            store.save(newer)
            // Its session gone but its record left behind (a failed delete): skipped.
            assertEquals(older, store.latest("sim") { it != "s2" })
            // Deleting the session deletes its record, at whatever track.
            assertTrue(store.deleteSession("s2"))
            assertEquals(listOf(older), store.history("sim"))
            assertEquals(older, store.latest("sim"))
            assertTrue(store.deleteSession("never-recorded"))
        }

    @Test
    fun `a record with a coordinate out of range or an unknown role reads as none, never failing the coach`() =
        withDir { dir ->
            val store = SessionFocusStore(dir)
            val older = record("s1", 1_000, 0)
            val newer = record("s2", 2_000, 1)
            store.save(older)
            store.save(newer)
            val f = File(dir, "sim/s2.json")
            val good = f.readText()
            f.writeText(good.replaceFirst(Regex("\"lats\":\\[[-0-9.]+"), "\"lats\":[95.0"))
            assertTrue(f.readText().contains("95.0"))
            assertNull(store.get("sim", "s2"))
            assertEquals(older, store.latest("sim"))
            f.writeText(good.replace("\"role\":\"primary\"", "\"role\":\"boss\""))
            assertTrue(f.readText().contains("boss"))
            assertNull(store.get("sim", "s2"))
            assertEquals(older, store.latest("sim"))
            assertFailsWith<IllegalArgumentException> { target("s3", 0).copy(lons = listOf(1.0)) }
        }

    @Test
    fun `a record keeps each focus under its CornerId, primary first, with what to change`() = withDir { dir ->
        val store = SessionFocusStore(dir)
        val r = record("s1", 1, 1, 3).let { r ->
            r.copy(targets = r.targets.map { it.copy(cornerId = it.segmentId, identityRevision = 2) })
        }
        store.save(r)
        val back = assertNotNull(store.get("sim", "s1"))
        assertEquals(listOf("s1/c2", "s1/c4"), back.targets.map { it.id })
        assertEquals(listOf(true, false), back.targets.map { it.primary })
        assertEquals(listOf("c2", "c4"), back.targets.map { it.cornerId })
        assertEquals(8.0, back.targets[0].changes.single().let { it.faster - it.other })
        val picks = back.picks()
        assertEquals(listOf("Corner 2", "Corner 4"), picks.map { it.name })
        assertEquals(listOf("c2", "c4"), picks.map { it.cornerId })
        assertEquals(listOf(2, 2), picks.map { it.identityRevision })
    }

    @Test
    fun `a session id or track id that is no file name is refused`() = withDir { dir ->
        val store = SessionFocusStore(dir)
        assertFailsWith<IllegalArgumentException> { store.save(record("../x", 1, 0)) }
        assertFailsWith<IllegalArgumentException> { store.get("../sim", "s1") }
    }

    @Test
    fun `the next session's coach works on the focus corners, the primary first`() {
        val paces = List(7) { earlyEverywhere }
        val plain = cornerCues(CueEngine(track, CoachSettings(Verbosity.Focus), reference()), sim.drive(paces))
        assertEquals(setOf(2, 3, 4), plain.mapNotNull { it.corner }.toSet(), "cues: ${plain.map { it.text }}")

        // The last session recommended corner 1, which loses least here, then corner 2.
        val focus = record("s1", 1, 0, 1).picks()
        val engine = CueEngine(track, CoachSettings(Verbosity.Focus), reference(), focus = focus)
        val cued = cornerCues(engine, sim.drive(paces))
        assertEquals(listOf(1, 2), engine.focusCorners)
        // Corners 1 and 2 take two of the three places; corner 4, losing most, the last.
        assertEquals(setOf(1, 2, 4), cued.mapNotNull { it.corner }.toSet(), "cues: ${cued.map { it.text }}")
        assertTrue(cued.any { it.corner == 1 })
    }

    @Test
    fun `a corner the driver picked comes after the primary focus and before the secondary ones`() {
        val paces = List(7) { earlyEverywhere }
        // Primary corner 1, secondary corners 2 and 3; the driver picked corner 4.
        val focus = record("s1", 1, 0, 1, 2).picks()
        val picked = record("s0", 0, 3).picks()
        val engine = CueEngine(track, CoachSettings(Verbosity.Focus), reference(), picks = picked, focus = focus)
        val cued = cornerCues(engine, sim.drive(paces))
        assertEquals(listOf(1, 2, 3), engine.focusCorners)
        assertEquals(setOf(4), engine.pickedCorners)
        // Three places: the primary, the pick, then the first secondary; the second secondary waits.
        assertEquals(setOf(1, 4, 2), cued.mapNotNull { it.corner }.toSet(), "cues: ${cued.map { it.text }}")
    }

    @Test
    fun `the coach session cues the focus corner it was handed`() {
        // Corner 1 loses least: without the focus it is never cued (see above).
        val focus = record("s1", 1, 0).picks()
        val session = CoachSession(CoachSettings(Verbosity.Focus), track, reference(), focus = focus)
        val corners = sim.drive(List(7) { earlyEverywhere }).fixes.flatMap { fix ->
            session.onFix(fix, fix.tMicros) { true }.events.filter { it.kind == CoachEvents.CUE }
                .mapNotNull { CoachEvents.parseCue(it.tMicros, it.text)?.corner }
        }
        assertTrue(1 in corners, "cued corners: $corners")
    }
}
