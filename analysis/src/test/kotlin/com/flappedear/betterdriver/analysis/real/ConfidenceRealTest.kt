@file:Suppress("MagicNumber") // Laps, corners and limits of the checks.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.confidence.Codes
import com.flappedear.betterdriver.analysis.confidence.SessionCodes
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.coach.CoachReference
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.Cue
import com.flappedear.betterdriver.coach.CueEngine
import com.flappedear.betterdriver.coach.CueKind
import com.flappedear.betterdriver.coach.CueResult
import com.flappedear.betterdriver.coach.SessionLaps
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.buildReference
import com.flappedear.betterdriver.coach.coachFixes
import com.flappedear.betterdriver.coach.passDoubts
import com.flappedear.betterdriver.coach.tracedLaps
import com.flappedear.betterdriver.coach.unusable
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.live.LapKind
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Confidence-gated coaching (FBBD-177) on Arek's three real Silesia Ring sessions of 27 Sep 2026, at
 * 10 Hz as RaceChrono recorded them (the built-in Demo track replay is resampled to 25 Hz and bridges
 * the gaps, so it cannot show them):
 *
 * - The detectors do not grade clean driving Low: on laps at race pace no corner pass is too weak for
 *   the coach, and the analysis grades a corner or straight Low only for a lap far slower there than
 *   the others (an anomalous lap), never for its positions.
 * - Every Low the analysis gives has a cause in the data: a GPS gap, a near stop, the pit lane, or an
 *   anomalous lap.
 * - With GPS gaps put into corners, those passes are graded Low: the coach never coaches from or
 *   towards them, nor measures a cue's result on them, and the analysis grades those laps Low there.
 * - With positions 0.3 s apart, the coach says no number at all.
 */
class ConfidenceRealTest {
    @TempDir
    lateinit var tmp: File

    private val store by lazy { SessionStore(File(tmp, "sessions")) }
    private val track = RealCorpus.silesiaTrack()

    private fun session(name: String): SessionData =
        store.read(RealCorpus.import(store, File(tmp, "staging-$name"), name, listOf(track)).id)

    private fun analyse(data: SessionData): SessionAnalysis = analyzeSession(telemetrySessionOf(data, track))

    /** The coach reference from [names]' laps, as the coach would hold it after driving them. */
    private fun reference(names: List<String>): CoachReference = assertNotNull(
        buildReference(
            track,
            names.map { session(it) }.map { SessionLaps(it.id, tracedLaps(track, it.coachFixes())) },
        ),
    )

    /** Every cue and result of a coached run over [data] at Full, from the first timed lap. */
    private fun coach(data: SessionData, reference: CoachReference?): Pair<List<Cue>, List<CueResult>> {
        val engine = CueEngine(track, CoachSettings(Verbosity.Full, learningLaps = 0), reference)
        val cues = mutableListOf<Cue>()
        val results = mutableListOf<CueResult>()
        data.coachFixes().forEach { f -> engine.update(f).let { cues += it.cues; results += it.results } }
        return cues to results
    }

    @Test
    fun `clean laps are not graded Low, and every Low has a cause in the data`() {
        for (name in RealCorpus.SILESIA_DAY) {
            val data = session(name)
            analysisLows(name, analyse(data))
            // The coach: no corner pass at race pace too weak to coach from.
            val traced = tracedLaps(track, data.coachFixes()).filter { it.lap.kind == LapKind.Timed }
            val map = reference(listOf(name)).map()
            val median = traced.map { it.lap.timeMillis }.sorted()[traced.size / 2]
            val passes = traced.filter { it.lap.timeMillis <= median * AtPaceShare }
                .flatMap { map.passes(it.trace, it.lap.number) }
            val weak = passes.filter { it.unusable() }.map { "lap ${it.lap} corner ${it.corner}: ${passDoubts(it)}" }
            assertEquals(emptyList(), weak, "$name: passes at race pace too weak to coach from")
        }
    }

    /** Every Low in [analysis] has a cause in the data; on laps at race pace only an anomalous lap, and few. */
    private fun analysisLows(name: String, analysis: SessionAnalysis) {
        val confidence = analysis.confidence
        assertEquals(Confidence.HIGH, confidence.session.level, "$name: ${confidence.session.why}")
        val laps = analysis.laps.timedLaps
        val median = laps.map { it.durationSeconds }.sorted()[laps.size / 2]
        val atPace = laps.indices.filter { laps[it].durationSeconds <= median * AtPaceShare }.toSet()
        val segments = assertNotNull(analysis.segments, name).segments
        segments.forEach { assertEquals(Confidence.HIGH, confidence.segment(it.id).level, "$name ${it.name}") }
        val lows = segments.flatMap { s -> laps.indices.map { i -> Triple(s, i, confidence.lap(s.id, i)) } }
            .filter { it.third.level == Confidence.LOW }
        for ((segment, i, graded) in lows) {
            val low = graded.reasons.filter { it.cap == Confidence.LOW }.map { it.code }
            val what = "$name ${segment.name}: ${graded.why}"
            assertTrue(low.all { it in LowCauses }, what)
            if (i in atPace) assertEquals(listOf(SessionCodes.ANOMALOUS_LAP), low, what)
        }
        // An anomalous lap through a corner is a lap gone wrong there, not weak data: a few at most.
        val lowAtPace = lows.count { it.second in atPace }
        val graded = segments.size * atPace.size
        assertTrue(lowAtPace * 100 <= graded * MaxAnomalousPercent, "$name: $lowAtPace of $graded Low at race pace")
    }

    @Test
    fun `GPS gaps in corners are Low, no cue from them or towards them, and the analysis grades them Low`() {
        val data = session(RealCorpus.SILESIA)
        val traced = tracedLaps(track, data.coachFixes()).filter { it.lap.kind == LapKind.Timed }
        val map = reference(listOf(RealCorpus.SILESIA)).map()
        // A 1.6 s outage at every apex of every second timed lap.
        val gapped = traced.filterIndexed { i, _ -> i % 2 == 1 }
        val outages = gapped.flatMap { t ->
            val cursor = map.cursor()
            val along = t.trace.points.map { cursor.along(it) }
            map.passes(t.trace, t.lap.number).map { pass ->
                val i = along.indices.minBy { along[it]?.let { a -> abs(a - pass.apexAt) } ?: Double.MAX_VALUE }
                val at = t.lap.startMicros + t.trace.timesMicros[i]
                Outage(t.lap.number, pass.corner, at - OutageMicros / 2, at + OutageMicros / 2)
            }
        }
        assertTrue(outages.size >= 3 * map.corners.size, "outages: ${outages.size}")
        val cut = data.copy(sampleSets = data.sampleSets.filter { s -> outages.none { s.tMicros in it.from..it.to } })

        // The coach: those passes are Low, where the gap leaves one measured at all ...
        val cutTraced = tracedLaps(track, cut.coachFixes()).associateBy { it.lap.number }
        val measured = outages.mapNotNull { o ->
            val t = assertNotNull(cutTraced[o.lap], "lap ${o.lap}")
            map.passes(t.trace, o.lap).firstOrNull { it.corner == o.corner }
        }
        assertTrue(measured.size >= gapped.size, "gapped passes measured: ${measured.size}")
        for (pass in measured) {
            assertTrue(pass.unusable(), "lap ${pass.lap} corner ${pass.corner}: ${passDoubts(pass)}")
        }
        // ... so nothing is coached from them or towards them, and no result is measured on them.
        val (cues, results) = coach(cut, reference(listOf(RealCorpus.SILESIA_1353, RealCorpus.SILESIA_1454)))
        val corner = cues.filter { it.kind == CueKind.Corner }
        assertTrue(corner.size >= 5, "corner cues from the clean laps: ${corner.size}")
        for (c in corner) {
            assertTrue(c.before?.unusable() != true && c.target?.unusable() != true, "'${c.text}': ${c.doubts}")
            assertTrue(outages.none { it.lap == c.before?.lap && it.corner == c.corner }, "'${c.text}' from a gap")
        }
        val fromGaps = results.filter { it.after.unusable() }.map { "lap ${it.after.lap} corner ${it.after.corner}" }
        assertEquals(emptyList(), fromGaps, "results measured on a gap")

        // The analysis: each lap with outages has the corners and straights they fall in graded Low for the gap.
        val analysis = analyse(cut)
        val origin = cut.sampleSets.minOf { it.tMicros }
        val laps = analysis.laps.timedLaps
        val segments = assertNotNull(analysis.segments).segments
        for ((lapNumber, lapOutages) in outages.groupBy { it.lap }) {
            val mid = (lapOutages.first().from - origin) / 1e6
            val i = laps.indexOfFirst { mid >= it.startTelemetryTime && mid < it.endTelemetryTime }
            assertTrue(i >= 0, "coach lap $lapNumber in the analysis")
            val low = segments.count { s ->
                analysis.confidence.lap(s.id, i).let { it.level == Confidence.LOW && it.has(Codes.GNSS_GAP) }
            }
            // Two gaps can fall in one corner or straight of the analysis.
            val what = "lap ${laps[i].number}: $low segments Low for a gap, ${lapOutages.size} gaps"
            assertTrue(low * 4 >= lapOutages.size * 3, what)
        }
    }

    @Test
    fun `positions 0_3 s apart give advice without numbers`() {
        val data = session(RealCorpus.SILESIA)
        val sparse = data.copy(sampleSets = data.sampleSets.filterIndexed { i, _ -> i % 3 == 0 })
        val (cues, _) = coach(sparse, reference(listOf(RealCorpus.SILESIA_1353, RealCorpus.SILESIA_1454)))
        val corner = cues.filter { it.kind == CueKind.Corner }
        assertTrue(corner.isNotEmpty(), "corner cues")
        for (c in corner) {
            val advice = assertNotNull(c.advice)
            assertTrue(c.confidence != Confidence.HIGH && !advice.precise, "'${c.text}' ${c.confidence}")
            assertTrue(c.text.replace("Turn ${c.turn}", "").none(Char::isDigit), "'${c.text}' says a number")
        }
    }

    private class Outage(val lap: Int, val corner: Int, val from: Long, val to: Long)

    private companion object {
        /** What may grade a lap Low through a corner or straight: none of it is about clean positions. */
        val LowCauses = setOf(Codes.GNSS_GAP, Codes.VERY_LOW_SPEED, Codes.PIT_LANE, SessionCodes.ANOMALOUS_LAP)

        /** A lap at most this much slower than the session's median lap is at race pace. */
        const val AtPaceShare = 1.05

        /** At most this share of corners and straights on laps at race pace are anomalous, percent. */
        const val MaxAnomalousPercent = 5

        const val OutageMicros = 1_600_000L
    }
}
