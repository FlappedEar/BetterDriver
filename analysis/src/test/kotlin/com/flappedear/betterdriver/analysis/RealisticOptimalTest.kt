@file:Suppress("MagicNumber") // Tolerances.

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.optimal.JointThresholds
import com.flappedear.betterdriver.analysis.optimal.OptimalKind
import com.flappedear.betterdriver.analysis.optimal.PedalState
import com.flappedear.betterdriver.analysis.optimal.RealisticOptimalLap
import com.flappedear.betterdriver.analysis.optimal.lapBenchmarks
import com.flappedear.betterdriver.analysis.optimal.optimalLap
import com.flappedear.betterdriver.analysis.optimal.realisticOptimal
import com.flappedear.betterdriver.analysis.real.RealCorpus
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The realistic optimal lap (FBBD-178) on every fixture: the parity corpus, the simulated tracks
 * and Arek's Silesia Ring session. Its joints are within the compatibility thresholds and it lies
 * between the raw theoretical best and the best actual lap.
 */
class RealisticOptimalTest {
    @TempDir
    lateinit var tmp: File

    private val corpus = listOf(
        "corners_gaps.vbo", "corners_inferred.vbo", "corners_measured.vbo", "laps_arc_minutes.vbo",
        "laps_clean.vbo", "laps_clockwise.vbo", "laps_end_inside_corridor.vbo", "laps_gps_gap.vbo",
        "laps_invalid_coordinate.vbo", "laps_long_trace.vbo", "racechrono_10_2_4.vbo", "segments_mixed.vbo",
        "segments_mixed_gps_gap.vbo", "segments_mixed_mirrored.vbo", "segments_rectangle.vbo",
    )

    /** [SimTrack.rectangle] with the line in the middle of its first corner, which is driven over it. */
    private val overTheLine = SimTrack(
        "over-the-line",
        listOf(
            SimTrack.Arc(40.0, 45.0), SimTrack.Straight(235.0), SimTrack.Arc(25.0, 90.0), SimTrack.Straight(515.0),
            SimTrack.Arc(60.0, 90.0), SimTrack.Straight(210.0), SimTrack.Arc(30.0, 90.0), SimTrack.Straight(530.0),
            SimTrack.Arc(40.0, 45.0),
        ),
    )

    /** Each fixture's name and how to read it. */
    private fun fixtures(): List<Pair<String, () -> TelemetrySession>> =
        corpus.map { name -> name to { VboFixture.read(name) } } +
            listOf(SimTrack.rectangle(), SimTrack.chicane("chicane"), SimTrack.hairpin(), overTheLine).map { sim ->
                "sim ${sim.id}" to { telemetrySessionOf(sim.session(laps = 5), sim.track()) }
            } +
            listOf("silesia" to ::silesia)

    private fun silesia(): TelemetrySession {
        val store = SessionStore(File(tmp, "sessions"))
        val track = RealCorpus.silesiaTrack()
        val id = RealCorpus.import(store, File(tmp, "staging"), RealCorpus.SILESIA, listOf(track)).id
        return telemetrySessionOf(store.read(id), track)
    }

    @TestFactory
    fun `every joint is compatible and best actual is at least realistic optimal is at least raw theoretical`():
        List<DynamicTest> = fixtures().map { (name, read) ->
        DynamicTest.dynamicTest(name) { check(name, analyzeSession(read())) }
    }

    private fun check(name: String, analysis: SessionAnalysis) {
        val benchmarks = lapBenchmarks(analysis) ?: return // no counted lap: nothing to combine
        val raw = benchmarks.rawTheoreticalSeconds ?: return // no segments or incomplete coverage
        val realistic = assertNotNull(analysis.realisticOptimal, "$name: a realistic optimal with a theoretical best")
        val t = realistic.thresholds
        for (j in realistic.crossLapJoints) {
            val what = "$name ${j.fromSegmentId}->${j.toSegmentId} lap ${j.fromLap}->${j.toLap}"
            assertTrue(assertNotNull(j.speedDifferenceKmh, what) <= t.speedKmh, "$what speed ${j.speedDifferenceKmh}")
            assertTrue(assertNotNull(j.lateralDifferenceMeters, what) <= t.lateralMeters, "$what line")
            assertTrue(assertNotNull(j.headingDifferenceRadians, what) <= t.headingRadians, "$what direction")
            assertTrue(assertNotNull(j.accelerationDifferenceG, what) <= t.accelerationG, "$what acceleration")
            val pedals = setOf(j.from!!.pedals, j.to!!.pedals)
            assertTrue(pedals != setOf(PedalState.Braking, PedalState.Accelerating), "$what braking into throttle")
        }
        assertTrue(realistic.totalSeconds >= raw - 1e-9, "$name: realistic ${realistic.totalSeconds} < raw $raw")
        assertTrue(
            realistic.totalSeconds <= benchmarks.bestActualSeconds + 1e-9,
            "$name: realistic ${realistic.totalSeconds} > best ${benchmarks.bestActualSeconds}",
        )
        // Its film is made of the laps it chose, segment by segment.
        val film = assertNotNull(optimalLap(analysis).lap, name)
        assertEquals(OptimalKind.RealisticOptimal, film.kind)
        assertEquals(realistic.totalSeconds, film.totalSeconds)
        val chosen = realistic.sectors.associate { it.segmentId to it.lapNumber }
        assertTrue(film.parts.all { chosen[it.segmentId] == it.lapNumber }, name)
        assertEquals(realistic.sectors.sumOf { it.seconds }, film.parts.sumOf { it.seconds }, 1e-6, name)
    }

    @Test
    fun `at Silesia Ring the realistic optimal combines laps but refuses the joints the raw theoretical best makes`() {
        val analysis = analyzeSession(silesia())
        val benchmarks = assertNotNull(lapBenchmarks(analysis))
        val realistic = assertNotNull(benchmarks.realisticOptimalSeconds)
        val raw = assertNotNull(benchmarks.rawTheoreticalSeconds)
        // 142.21 s best actual, 139.43 s realistic optimal from four laps, 138.01 s raw theoretical best.
        assertTrue(realistic < benchmarks.bestActualSeconds - 1.0, "laps combined: $realistic")
        assertTrue(realistic > raw + 0.5, "incompatible joints refused: $realistic against $raw")
        assertTrue(assertNotNull(analysis.realisticOptimal).laps.size > 1)
    }

    @Test
    fun `a segment over the line comes from one lap at both ends of the realistic optimal`() {
        val analysis = analyzeSession(telemetrySessionOf(overTheLine.session(laps = 5), overTheLine.track()))
        val segments = assertNotNull(analysis.segments).segments
        val wrap = segments.single { it.endProgressMeters < it.startProgressMeters }
        val realistic = assertNotNull(analysis.realisticOptimal)
        val joints = realistic.joints
        assertEquals(segments.size, joints.size, "a joint between every pair of neighbours, round the loop")
        assertEquals(wrap.id, joints.first().fromSegmentId)
        assertEquals(wrap.id, joints.last().toSegmentId)
        val film = assertNotNull(optimalLap(analysis).lap)
        val ends = film.parts.filter { it.segmentId == wrap.id }
        assertEquals(2, ends.size)
        assertEquals(1, ends.map { it.lapNumber }.distinct().size)
    }

    @Test
    fun `no tolerance at all gives the best actual lap, and any tolerance never beats the raw theoretical best`() {
        val analysed = fixtures()
            .map { (name, read) -> read().let { Triple(name, it, analyzeSession(it)) } }
            .filter { lapBenchmarks(it.third)?.rawTheoreticalSeconds != null }
        for ((name, session, analysis) in analysed) {
            val benchmarks = assertNotNull(lapBenchmarks(analysis))
            val raw = assertNotNull(benchmarks.rawTheoreticalSeconds)
            fun with(t: JointThresholds): RealisticOptimalLap = assertNotNull(
                realisticOptimal(
                    session, analysis.axis!!, analysis.segments!!,
                    analysis.laps.timedLaps, analysis.lapTimes, analysis.traces, analysis.laps.fastestLapIndex, t,
                ),
                name,
            )
            val none = with(JointThresholds(0.0, -1.0, -1.0, -1.0))
            assertEquals(benchmarks.bestActualSeconds, none.totalSeconds, 1e-9, name)
            assertEquals(listOf(benchmarks.bestActualLapNumber), none.laps, name)
            val loose = with(JointThresholds(1e9, 1e9, 1e9, 1e9))
            assertTrue(loose.totalSeconds >= raw - 1e-9, name)
            assertTrue(loose.totalSeconds <= assertNotNull(analysis.realisticOptimal).totalSeconds + 1e-9, name)
        }
    }
}
