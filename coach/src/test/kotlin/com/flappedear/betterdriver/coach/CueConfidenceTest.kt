@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.Confidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Confidence-gated cues (FBBD-177): no precise advice from weak evidence, no advice from one lap. */
class CueConfidenceTest {
    private val sim = SimCircuit()
    private val track = sim.track()
    private val steady = SimCircuit.Pace()
    private val early2 = SimCircuit.Pace(earlierM = listOf(0.0, 15.0, 0.0, 0.0))

    private fun reference(vararg paces: SimCircuit.Pace): CoachReference {
        val laps = tracedLaps(track, sim.drive(paces.toList()).fixes.asSequence())
        return assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
    }

    private fun cornerCues(engine: CueEngine, fixes: List<CoachFix>): List<Cue> =
        fixes.flatMap { engine.update(it).cues }.filter { it.kind == CueKind.Corner }

    private val target = CornerPass(
        corner = 1, lap = 1, brakeAt = 100.0, apexAt = 160.0, entryKmh = 160.0, minKmh = 80.0,
        timeMicros = 5_000_000L, gapMicros = 40_000, intervalMicros = 40_000,
    )
    private val slower = target.copy(lap = 2, brakeAt = 85.0, timeMicros = 5_300_000L)

    @Test
    fun `a pass with clean positions has no doubts, a gap or a near stop makes it Low`() {
        assertTrue(passDoubts(target).isEmpty())
        val gap = passDoubts(target.copy(gapMicros = 700_000))
        assertEquals(listOf(CueDoubts.GNSS_GAP), gap.map { it.code })
        assertEquals(Confidence.LOW, confidenceOf(gap))
        assertTrue(target.copy(gapMicros = 700_000).unusable())
        // A 1 Hz receiver's second between positions is its rate, not a gap; it is weak, though.
        val oneHz = passDoubts(target.copy(gapMicros = 1_000_000, intervalMicros = 1_000_000))
        assertEquals(listOf(CueDoubts.WEAK_INTERPOLATION), oneHz.map { it.code })
        assertEquals(Confidence.MEDIUM, confidenceOf(oneHz))
        assertEquals(Confidence.LOW, confidenceOf(passDoubts(target.copy(minKmh = 8.0))))
        assertEquals(Confidence.MEDIUM, confidenceOf(passDoubts(target.copy(speedMeasured = false))))
        // Passes stored before FBBD-177 know nothing of their positions: no doubt from that.
        assertTrue(passDoubts(target.copy(gapMicros = 0, intervalMicros = 0)).isEmpty())
    }

    @Test
    fun `a gain within the usual spread, too few passes, or an unmatched corner leave a cue Medium`() {
        val steadyLosses = listOf(300_000L, 310_000L, 305_000L)
        assertTrue(cueDoubts(slower, target, steadyLosses, unidentified = false).isEmpty())
        val scattered = cueDoubts(slower, target, listOf(0L, 700_000L, 300_000L), unidentified = false)
        assertEquals(listOf(CueDoubts.SPREAD), scattered.map { it.code })
        assertEquals(Confidence.MEDIUM, confidenceOf(scattered))
        val one = cueDoubts(slower, target, listOf(300_000L), unidentified = false)
        assertEquals(listOf(CueDoubts.FEW_PASSES), one.map { it.code })
        val unmatched = cueDoubts(slower, target, steadyLosses, unidentified = true)
        assertEquals(listOf(CueDoubts.IDENTITY), unmatched.map { it.code })
        // The Low ones come first.
        val both = cueDoubts(slower.copy(gapMicros = 900_000), target, listOf(300_000L), unidentified = false)
        assertEquals(Confidence.LOW, both.first().cap)
    }

    @Test
    fun `a corner improvement seen on one lap only never produces a cue`() {
        // Earlier, corner 2 was braked late once only (the last lap): today's early braking matches
        // the second fastest pass there, so there is nothing confirmed to ask for.
        val drive = sim.drive(List(5) { early2 })
        val once = CueEngine(track, CoachSettings(Verbosity.Full), reference(early2, early2, steady))
        assertTrue(cornerCues(once, drive.fixes).none { it.corner == 2 })
        // Seen on two laps, it is asked for.
        val twice = CueEngine(track, CoachSettings(Verbosity.Full), reference(early2, steady, steady))
        val cue = cornerCues(twice, drive.fixes).first { it.corner == 2 }
        assertTrue(cue.advice is Advice.BrakeLater)
        assertEquals(Confidence.HIGH, cue.confidence, cue.confidenceText())
        assertTrue(cue.text.contains("brake at"), cue.text)
    }

    @Test
    fun `a speed worked out from positions gives the cue without its number`() {
        val fixes = sim.drive(List(5) { early2 }).fixes.map { it.copy(speedKmh = null) }
        val engine = CueEngine(track, CoachSettings(Verbosity.Focus), reference(steady, steady, steady))
        val cue = cornerCues(engine, fixes).first()
        assertEquals(2, cue.corner)
        assertEquals(Confidence.MEDIUM, cue.confidence)
        assertTrue(cue.doubts.any { it.code == CueDoubts.SPEED_NOT_MEASURED }, cue.confidenceText())
        assertEquals("Turn 2, brake a little later", cue.text)
        assertEquals(false, (cue.advice as Advice.BrakeLater).precise)
        assertTrue(CueClips.covers(cue.text))
    }

    @Test
    fun `a GNSS dropout before the braking point leaves the corner without a cue`() {
        val paces = List(5) { early2 }
        val drive = sim.drive(paces)
        val brake = sim.brakePoint(1, early2)
        // About 26 m, more than half a second at that speed, without a position on every lap.
        val dropped = drive.fixes.filterIndexed { i, _ ->
            val inLap = ((drive.distance[i] % sim.length) + sim.length) % sim.length
            inLap !in (brake - 28.0)..(brake - 2.0)
        }
        fun engine() = CueEngine(track, CoachSettings(Verbosity.Full), reference(steady, steady, steady))
        val clean = cornerCues(engine(), drive.fixes)
        assertTrue(clean.any { it.corner == 2 })
        val gapped = cornerCues(engine(), dropped)
        assertFalse(gapped.any { it.corner == 2 }, "cues: ${gapped.map { it.text }}")
        // The same positions held, not dropped, are a dropout too.
        var held: CoachFix? = null
        val frozen = drive.fixes.mapIndexed { i, f ->
            val inLap = ((drive.distance[i] % sim.length) + sim.length) % sim.length
            if (inLap in (brake - 28.0)..(brake - 2.0)) {
                val h = held ?: f.also { held = it }
                f.copy(lat = h.lat, lon = h.lon)
            } else {
                held = null
                f
            }
        }
        val heldCues = cornerCues(engine(), frozen)
        assertFalse(heldCues.any { it.corner == 2 }, "cues: ${heldCues.map { it.text }}")
    }

    @Test
    fun `a reference built from speeds worked out from positions never gives a precise cue`() {
        val derived = sim.drive(List(3) { steady }).fixes.map { it.copy(speedKmh = null) }
        val laps = tracedLaps(track, derived.asSequence())
        assertTrue(laps.all { l -> l.trace.speedsMeasured.orEmpty().none { it } })
        val reference = assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
        assertTrue(reference.targets.isNotEmpty())
        assertTrue(reference.targets.none { it.speedMeasured })
        // Today's speeds are measured; the target's are not, so the cue says no number.
        val engine = CueEngine(track, CoachSettings(Verbosity.Focus), reference)
        val cue = cornerCues(engine, sim.drive(List(5) { early2 }).fixes).first()
        assertEquals(Confidence.MEDIUM, cue.confidence)
        assertTrue(cue.doubts.any { it.code == CueDoubts.SPEED_NOT_MEASURED }, cue.confidenceText())
        assertFalse(cue.text.any(Char::isDigit) && "metres" in cue.text, cue.text)
        // Measured speeds keep their flag through the lap's trace.
        val measured = tracedLaps(track, sim.drive(List(2) { steady }).fixes.asSequence())
        assertTrue(measured.all { l -> l.trace.speedsMeasured.orEmpty().all { it } })
    }

    @Test
    fun `the pass after a cue is not judged when its positions stopped`() {
        val drive = sim.drive(List(6) { early2 })
        fun run(fixes: List<CoachFix>): Pair<List<Cue>, List<CueResult>> {
            val engine = CueEngine(track, CoachSettings(Verbosity.Full), reference(steady, steady, steady))
            val updates = fixes.map { engine.update(it) }
            return updates.flatMap { it.cues }.filter { it.kind == CueKind.Corner } to updates.flatMap { it.results }
        }
        val (cues, results) = run(drive.fixes)
        val cue = cues.first { it.corner == 2 }
        assertTrue(results.any { it.cue.seq == cue.seq })
        // The lap the cue was given on loses its positions before the braking point.
        val brake = sim.brakePoint(1, early2)
        val gapped = drive.fixes.filterIndexed { i, f ->
            val inLap = ((drive.distance[i] % sim.length) + sim.length) % sim.length
            val afterCue = f.tMicros > cue.tMicros && f.tMicros < cue.tMicros + 20_000_000L
            !(afterCue && inLap in (brake - 28.0)..(brake - 2.0))
        }
        val (sameCues, weakResults) = run(gapped)
        val same = sameCues.first { it.corner == 2 }
        assertEquals(cue.text, same.text)
        assertTrue(weakResults.none { it.cue.seq == same.seq }, "results: ${weakResults.map { it.improved }}")
    }

    @Test
    fun `a cue's confidence and doubts are logged and read back`() {
        val cue = Cue(
            7, 0, 4, CueKind.Corner, "Turn 2, brake a little later", 2, Advice.BrakeLater(10, precise = false),
            slower, target, confidence = Confidence.MEDIUM,
            doubts = listOf(CueDoubt(CueDoubts.SPREAD, Confidence.MEDIUM, CueDoubts.text(CueDoubts.SPREAD))),
        )
        val text = CoachEvents.cueText(cue)
        assertTrue(" conf=MEDIUM why=within_spread " in text, text)
        val back = assertNotNull(CoachEvents.parseCue(0, text))
        assertEquals(Confidence.MEDIUM, back.confidence)
        assertEquals(Advice.BrakeLater(10, precise = false), back.advice)
        assertEquals(listOf(CueDoubts.SPREAD), back.doubts.map { it.code })
        assertEquals("Medium confidence: within the usual pass-to-pass spread", back.confidenceText())
        // A cue logged before FBBD-177 was said with its number.
        val logged = "seq=1 lap=4 kind=Corner corner=2 advice=brake_later:10 text=x"
        val old = assertNotNull(CoachEvents.parseCue(0, logged))
        assertEquals(Confidence.HIGH, old.confidence)
        assertEquals(Advice.BrakeLater(10), old.advice)
        assertEquals("High confidence", old.confidenceText())
    }
}
