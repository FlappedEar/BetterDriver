@file:Suppress("MagicNumber") // Tolerances.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.VboFixture
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.focus.MaxSecondaryFocus
import com.flappedear.betterdriver.analysis.focus.nextSessionPlan
import com.flappedear.betterdriver.analysis.focus.sessionFocusAreas
import com.flappedear.betterdriver.analysis.optimal.OptimalKind
import com.flappedear.betterdriver.analysis.optimal.optimalClipPlan
import com.flappedear.betterdriver.analysis.optimal.optimalLap
import com.flappedear.betterdriver.analysis.optimal.sessionRange
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.replay.replayLaps
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The post-session analysis of Arek's RaceChrono session at Silesia Ring (27 Sep 2026), imported as
 * the app imports it and timed against RaceChrono's start/finish line: the summary (FBBD-20), parity
 * with FlappedEar Telemetry reading the same file (FBBD-4), and the optimal lap's video plan
 * (FBBD-163).
 */
class AnalysisReplayTest {
    @TempDir
    lateinit var tmp: File

    private val store by lazy { SessionStore(File(tmp, "sessions")) }
    private val track = RealCorpus.silesiaTrack()

    private fun silesia(): SessionData =
        store.read(RealCorpus.import(store, File(tmp, "staging"), RealCorpus.SILESIA, listOf(track)).id)

    @Test
    fun `the summary shows the best lap, the three corners with most time available and one primary focus (FBBD-20)`() {
        val data = silesia()
        val analysis = analyzeSession(telemetrySessionOf(data, track))
        val laps = analysis.laps.timedLaps
        val fastest = laps[assertNotNull(analysis.laps.fastestLapIndex)]
        // The best lap is the one the lap timer found fastest.
        val best = replayLaps(data.id, data.sampleSets, track).laps.map { it.lap }
            .filter { it.kind == LapKind.Timed }.minBy { it.timeMicros }
        assertEquals(best.timeMicros / 1e6, fastest.durationSeconds, 0.02)

        // Top 3 corners by time available, each with its evidence: the lap that drove it fastest,
        // and both laps measured through it.
        val corners = analysis.segments!!.segments.filter { it.type == TrackSegmentType.Corner }.map { it.id }.toSet()
        val top = assertNotNull(analysis.summary).gains
            .filter { it.segmentId in corners && (it.lossSeconds ?: 0.0) > 0.05 }
            .take(3)
        assertEquals(3, top.size, "corners where the best lap loses time")
        val numbers = laps.map { it.number }
        for (row in top) {
            val source = assertNotNull(row.sourceLapReference as? Int, row.name)
            assertTrue(source in numbers && source != fastest.number, "${row.name}: fastest through it on lap $source")
            assertEquals(row.lossSeconds!!, row.actualSeconds!! - row.seconds!!, 1e-9)
            val metrics = assertNotNull(analysis.cornerMetrics[row.segmentId], row.name)
            assertEquals(laps.size, metrics.size, "${row.name}: measured on every lap")
        }
        assertTrue(top.zipWithNext().all { (a, b) -> a.lossSeconds!! >= b.lossSeconds!! }, "largest first")

        // One primary focus (up to two more), each a measured observation with the laps to compare.
        val focus = sessionFocusAreas(analysis)
        assertTrue(focus.size in 1..3, "focus areas: $focus")
        for (area in focus) {
            assertTrue(area.observation.any(Char::isDigit), "${area.name}: a measured number")
            val what = "${area.name}: laps ${area.lap} and ${area.against}"
            assertTrue(area.lap in numbers && area.against in numbers, what)
            assertTrue(area.sampleCount >= 2, area.name)
        }

        // What to work on next: one primary corner, at most two secondary ones, never a Low one,
        // each with its time available.
        val plan = nextSessionPlan(analysis)
        assertNotNull(plan.primary, "no primary focus")
        assertTrue(plan.secondary.size <= MaxSecondaryFocus, "secondary: ${plan.secondary.map { it.name }}")
        for (f in plan.focuses) {
            assertTrue(f.segmentId in corners, "${f.name}: a corner")
            assertTrue(f.confidence.level != Confidence.LOW, "${f.name}: ${f.confidence.why}")
            assertTrue((f.timeAvailableSeconds ?: 0.0) > 0.0, "${f.name}: time available")
        }
        println("next session: ${plan.focuses.map { "${it.role} ${it.name} ${it.timeAvailableSeconds} ${it.what}" }}")
    }

    /** [session] with every value stored as float32, as FlappedEar Telemetry's parser stores it. */
    private fun asFloat32(session: TelemetrySession) = TelemetrySession(
        session.channels.mapValues { (_, c) ->
            val values = DoubleArray(c.values.size) { c.values[it].toFloat().toDouble() }
            TelemetryChannel(c.name, c.timestamps, values, c.unit)
        },
        session.aliases,
        session.timingGates,
        session.metadata,
    )

    private fun topLosses(a: SessionAnalysis) = a.summary!!.gains.take(3).map { it.name to it.lossSeconds!! }

    @Test
    fun `the analysis matches FlappedEar Telemetry's of the same RaceChrono file (FBBD-4)`() {
        val data = silesia()
        val app = analyzeSession(telemetrySessionOf(data, track))
        val telemetry = analyzeSession(VboFixture.parse(RealCorpus.text(RealCorpus.SILESIA)))

        // The laps, to 0.02 s.
        assertEquals(telemetry.laps.timedLaps.size, app.laps.timedLaps.size)
        app.laps.timedLaps.zip(telemetry.laps.timedLaps).forEach { (a, b) ->
            assertEquals(b.durationSeconds, a.durationSeconds, 0.02, "lap ${a.number}")
            assertEquals(b.referenceEligible, a.referenceEligible, "lap ${a.number}")
        }
        assertEquals(telemetry.laps.fastestLapIndex, app.laps.fastestLapIndex)

        // Telemetry keeps every value as float32: 0.4 m steps of latitude here. Given the
        // positions it reads, the app's analysis of its own import finds Telemetry's corners and
        // straights, theoretical best, top time losses and primary focus.
        val same = analyzeSession(asFloat32(telemetrySessionOf(data, track)))
        assertEquals(
            telemetry.segments!!.segments.map { it.name to it.type },
            same.segments!!.segments.map { it.name to it.type },
        )
        same.segments!!.segments.zip(telemetry.segments!!.segments).forEach { (a, b) ->
            assertEquals(b.startProgressMeters, a.startProgressMeters, 0.01, a.name)
            assertEquals(b.endProgressMeters, a.endProgressMeters, 0.01, a.name)
        }
        assertEquals(telemetry.theoreticalBest!!.totalSeconds!!, same.theoreticalBest!!.totalSeconds!!, 0.001)
        topLosses(telemetry).zip(topLosses(same)).forEach { (b, a) ->
            assertEquals(b.first, a.first)
            assertEquals(b.second, a.second, 0.001, a.first)
        }
        fun areas(a: SessionAnalysis) =
            sessionFocusAreas(a).take(2).map { Triple(it.kind, it.name, it.lap to it.against) }
        assertEquals(areas(telemetry), areas(same))
    }

    @Test
    fun `the optimal lap's video plays its parts in track order and ends at the theoretical best (FBBD-163)`() {
        val data = silesia()
        val analysis = analyzeSession(telemetrySessionOf(data, track))
        val lap = assertNotNull(optimalLap(analysis, OptimalKind.RawTheoreticalBest).lap)
        val total = analysis.theoreticalBest!!.totalSeconds!!
        assertEquals(total, lap.totalSeconds, 1e-9)
        assertEquals(total, lap.parts.sumOf { it.seconds }, 1e-6)
        // In track order from the line: each part starts where the parts before it end.
        var offset = 0.0
        for (p in lap.parts) {
            assertEquals(offset, p.offsetSeconds, 1e-6, p.name)
            offset += p.seconds
        }
        val counted = analysis.laps.timedLaps.filter { it.referenceEligible }.map { it.number }
        assertTrue(lap.laps.all { it in counted }, "parts from counted laps: ${lap.laps}")

        // The phone filmed the whole session: every part is on video and the film is the theoretical best.
        val origin = data.sampleSets.first().tMicros
        val end = data.sampleSets.last().tMicros
        val whole = VideoClip("phone", "whole.mp4", origin, end)
        val plan = optimalClipPlan(lap, listOf(whole), { end - origin }, origin)
        assertTrue(plan.missing.isEmpty() && plan.partial.isEmpty())
        assertEquals(total, plan.totalMicros / 1e6, 0.001 * lap.parts.size)
        assertEquals(lap.parts.indices.toList(), plan.cuts.map { it.partIndex }.distinct(), "cuts in track order")

        // Filmed only during the fastest lap: the parts of the other laps are skipped and listed.
        val fastest = analysis.laps.timedLaps[analysis.laps.fastestLapIndex!!]
        val from = origin + (fastest.startTelemetryTime * 1e6).toLong()
        val to = origin + (fastest.endTelemetryTime * 1e6).toLong()
        val some = optimalClipPlan(lap, listOf(VideoClip("phone", "lap.mp4", from, to)), { to - from }, origin)
        val others = lap.parts.filter { it.lapNumber != fastest.number }
        assertTrue(others.isNotEmpty(), "the theoretical best takes parts from other laps")
        assertEquals(others, some.missing)
        val filmed = lap.parts.filter { it.lapNumber == fastest.number }.sumOf { it.seconds }
        assertEquals(filmed, some.totalMicros / 1e6, 0.001 * lap.parts.size)
    }

    @Test
    fun `the composite video plays the realistic optimal, each joint within a frame of the data (FBBD-178)`() {
        val data = silesia()
        val analysis = analyzeSession(telemetrySessionOf(data, track))
        val realistic = assertNotNull(analysis.realisticOptimal)
        val lap = assertNotNull(optimalLap(analysis).lap)
        assertEquals(OptimalKind.RealisticOptimal, lap.kind)
        assertEquals(realistic.totalSeconds, lap.totalSeconds)
        val raw = assertNotNull(optimalLap(analysis, OptimalKind.RawTheoreticalBest).lap)
        assertTrue(raw.totalSeconds < lap.totalSeconds, "the raw theoretical best stays available, and is faster")

        val origin = data.sampleSets.first().tMicros
        val end = data.sampleSets.last().tMicros
        val plan = optimalClipPlan(lap, listOf(VideoClip("phone", "whole.mp4", origin, end)), { end - origin }, origin)
        assertTrue(plan.missing.isEmpty() && plan.partial.isEmpty())
        // Every part starts and ends on the video where its lap crossed the segment's boundaries.
        val frame = 1_000_000L / 30
        for ((i, part) in lap.parts.withIndex()) {
            val cuts = plan.cuts.filter { it.partIndex == i }
            val range = part.sessionRange(origin)
            assertTrue(kotlin.math.abs(cuts.first().cut.sessionStartMicros - range.first) <= frame, part.name)
            val last = cuts.last().cut
            assertTrue(kotlin.math.abs(last.sessionStartMicros + last.durationMicros - range.last) <= frame, part.name)
        }
        val first = plan.cuts.first()
        val caption = plan.overlayAt(first, first.partStartMicros).captions.first()
        assertTrue(caption.startsWith("REALISTIC OPTIMAL · LAP "), caption)
    }
}
