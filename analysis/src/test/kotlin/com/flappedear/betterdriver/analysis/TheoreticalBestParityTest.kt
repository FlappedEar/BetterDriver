@file:Suppress("MagicNumber", "LongMethod")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.ProjectedSample
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.ConsistencyAlgorithm
import com.flappedear.betterdriver.analysis.segments.ConsistencySummary
import com.flappedear.betterdriver.analysis.segments.ConsistencyTooFewSamples
import com.flappedear.betterdriver.analysis.segments.LapSectorTimes
import com.flappedear.betterdriver.analysis.segments.PopulationLap
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestLap
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestRow
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestSummary
import com.flappedear.betterdriver.analysis.segments.TimeLossObservations
import com.flappedear.betterdriver.analysis.segments.TimeLossRanking
import com.flappedear.betterdriver.analysis.segments.TimedLapSectors
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.compareSectorTimes
import com.flappedear.betterdriver.analysis.segments.computeLapSectorTimes
import com.flappedear.betterdriver.analysis.segments.computeTheoreticalBest
import com.flappedear.betterdriver.analysis.segments.computeTimeLossObservations
import com.flappedear.betterdriver.analysis.segments.publishTheoreticalBest
import com.flappedear.betterdriver.analysis.segments.rankPopulationTimeLosses
import com.flappedear.betterdriver.analysis.segments.rankTimeLosses
import com.flappedear.betterdriver.analysis.segments.summarizeConsistency
import com.flappedear.betterdriver.analysis.segments.projectedCoverageMeters
import com.flappedear.betterdriver.analysis.segments.timePopulation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Sector timing and the theoretical best against FlappedEar Telemetry's parity reference
 * (FlappedEar Overlays' C++): every corpus file with its segments, shifted segments and every
 * other segment, plus hand-made projections and a hand-made population.
 */
class TheoreticalBestParityTest {
    private val reference = Parity.load("theoretical_best_reference.json")
    private val cases = reference.obj("cases")
    private val configuration = cases.str("configuration")

    @TestFactory
    fun days(): List<DynamicTest> = reference.arr("files").map { it.jsonObject }.map { entry ->
        val name = entry.str("file")
        DynamicTest.dynamicTest(name) { checkDay(name, entry) }
    }

    private fun checkDay(name: String, entry: JsonObject) {
        val session = VboFixture.read(name)
        val laps = deriveSourceLapSession(session)
        val byNumber = laps.timedLaps.associateBy { it.number }
        // Grouped by run, in their order within each run, as calculateOutingTheoreticalBest orders them.
        val rows = entry.arr("population").map { it.jsonObject }
            .withIndex().sortedWith(compareBy({ it.value.str("runId") }, { it.index })).map { it.value }
        for (variant in entry.arr("variants").map { it.jsonObject }) {
            val what = "$name ${variant.str("name")}"
            val canonical = variant.str("canonicalRunId")
            assertEquals("run-a", canonical, what)
            assertEquals("", variant.str("error"), what)
            val approved = approved(variant.obj("segments").getValue(canonical), variant.str("revision"))
            val population = rows.map {
                val lap = byNumber.getValue(it.int("lapNumber"))
                PopulationLap(
                    session,
                    lap.startTelemetryTime,
                    lap.endTelemetryTime,
                    "${it.str("runId")}#${lap.number}",
                    it.str("runId"),
                )
            }
            val axisLap = rows.zip(population).filter { it.first.str("runId") == canonical }
                .minBy { it.second.end - it.second.start }.first.int("lapNumber")
            val computed = timePopulation(laps, axisLap, approved, population)
            assertNotNull(computed, what)
            val axis = variant.obj("axis")
            assertEquals(axis.int("pointCount"), computed.axis.points.size, what)
            close(computed.axis.lengthMeters, axis.dbl("lengthMeters"), what)
            val wantLaps = variant.arr("laps").map { it.jsonObject }
            assertEquals(wantLaps.size, computed.times.size, what)
            computed.times.forEachIndexed { i, times -> expectSectorTimes(times, wantLaps[i], "$what lap $i") }
            expectBest(computed.best, variant.obj("best"), what)
            val actualBest = computed.times.firstOrNull { it.lapReference == entry.str("actualBest") }
            assertEquals(nullableString(variant["actualBest"]), actualBest?.lapReference, what)
            expectPublished(
                publishTheoreticalBest(approved, computed.best, computed.times, actualBest),
                variant.obj("published"),
                what,
            )
            expectRanking(
                rankPopulationTimeLosses(approved, computed, population, actualBest),
                variant.obj("timeLossRunBests"),
                "$what run bests",
            )
            expectRanking(
                rankPopulationTimeLosses(approved, computed, population, actualBest, allLaps = true),
                variant.obj("timeLossAllLaps"),
                "$what all laps",
            )
            variant["observations"]?.takeUnless { it is JsonNull }?.let { observations ->
                val start = population.lastOrNull { it.reference == entry.str("actualBest") }?.start ?: 0.0
                expectObservations(
                    computeTimeLossObservations(
                        approved,
                        computed.axis.lengthMeters,
                        computed.times[0],
                        population[0].start,
                        actualBest!!,
                        start,
                    ),
                    observations.jsonObject,
                    "$what observations",
                )
            }
            val comparisons = variant["comparisons"]?.takeUnless { it is JsonNull }?.jsonArray ?: continue
            for (value in comparisons.map { it.jsonObject }) {
                val comparison = compareSectorTimes(computed.times[0], computed.times[1], value.str("segmentId"))
                assertEquals(value.bool("valid"), comparison.valid, what)
                closeOrNull(comparison.secondsDelta, value["delta"], what)
                assertEquals(value.str("reason"), comparison.unavailableReason, what)
            }
        }
    }

    @Test
    fun sectorTimesOfHandMadeProjections() {
        cases.arr("sectorTimes").map { it.jsonObject }.forEachIndexed { index, item ->
            val stored = item.getValue("stored")
            // A stored value that is not a list of segments is storage parsing, not ported.
            if (stored !is JsonArray) return@forEachIndexed
            val result = item.obj("result")
            val trace = trace(item.arr("trace"))
            val times = computeLapSectorTimes(
                approved(stored, result.str("revision")),
                item.dbl("lengthMeters"),
                trace,
                item.dbl("start"),
                item.dbl("end"),
                "case#$index",
            )
            expectSectorTimes(times, result, "case $index")
            close(
                projectedCoverageMeters(trace, 250.0, 520.0, item.dbl("lengthMeters")),
                item.dbl("coverage"),
                "case $index coverage",
            )
        }
    }

    @Test
    fun theoreticalBestAndTimeLossOfHandMadePopulation() {
        val item = cases.obj("population")
        val best = item.obj("best")
        val approved = approved(item.arr("stored"), best.str("revision"))
        val other = approved(item.arr("otherStored"), "other")
        val timed = item.arr("laps").map { it.jsonObject }.mapIndexed { i, lap ->
            val times = computeLapSectorTimes(
                if (lap.bool("other")) other else approved,
                1000.0,
                trace(lap.arr("trace")),
                lap.dbl("start"),
                lap.dbl("end"),
                "${if (i % 2 == 1) "b" else "a"}#$i",
            )
            TimedLapSectors(times, lap.dbl("start"))
        }
        val population = timed.map { it.times }
        expectBest(computeTheoreticalBest(approved, population), best, "population")
        expectBest(
            computeTheoreticalBest(ApprovedSegments(emptyList(), ""), population),
            item.obj("bestNone"),
            "no segments",
        )
        val observations = item.arr("observations").map { it.jsonObject }
        timed.forEachIndexed { i, lap ->
            expectObservations(
                computeTimeLossObservations(
                    approved, 1000.0, lap.times, lap.startTime, timed[0].times, timed[0].startTime,
                ),
                observations[i],
                "observations $i",
            )
        }
        val ranking = rankTimeLosses(approved, 1000.0, timed, timed[0], maximumResults = 3)
        val want = item.obj("ranking")
        assertEquals(want.bool("valid"), ranking.valid)
        assertEquals(want.int("observationCount"), ranking.observationCount)
        assertEquals(want.int("comparedLapCount"), ranking.comparedLapCount)
        assertEquals(want.int("untimedWindowCount"), ranking.untimedWindowCount)
        val losses = want.arr("losses").map { it.jsonObject }
        assertEquals(losses.size, ranking.losses.size)
        ranking.losses.forEachIndexed { i, loss ->
            assertEquals(losses[i].str("lap"), loss.lapReference)
            assertEquals(losses[i].str("segmentId"), loss.window.segmentId)
            close(loss.lossSeconds, losses[i].dbl("loss"), "loss $i")
            close(loss.coverageLap, losses[i].dbl("coverageLap"), "coverage $i")
            close(loss.coverageReference, losses[i].dbl("coverageReference"), "reference coverage $i")
        }
    }

    @Test
    fun consistencyQuartiles() {
        val summary = summarizeConsistency(listOf(4.0, 1.0, Double.NaN, 3.0, 2.0))
        assertEquals(4, summary.count)
        assertEquals(1.75, summary.q1)
        assertEquals(2.5, summary.median)
        assertEquals(3.25, summary.q3)
        assertEquals(1.5, summary.interquartileRange)
        val few = summarizeConsistency(listOf(1.0, 2.0))
        assertEquals(false, few.available)
        assertEquals(ConsistencyTooFewSamples, few.unavailableReason)
    }

    /** Stored segments of this configuration, as Telemetry's approvedSegmentation keeps them. */
    private fun approved(stored: JsonElement, revision: String) = ApprovedSegments(
        stored.jsonArray.map { it.jsonObject }
            .filter { it.str("trackConfigurationReference") == configuration }
            .map {
                TrackSegment(
                    it.str("id"),
                    it.str("name"),
                    TrackSegmentType.entries.first { t -> t.jsonName == it.str("type") },
                    it.dbl("startProgressMeters"),
                    it.dbl("endProgressMeters"),
                )
            },
        revision,
        configuration,
    )

    private fun trace(value: JsonArray): List<ProgressSegment> = value.map { segment ->
        ProgressSegment(
            segment.jsonArray.map { ProjectedSample(it.jsonArray.d(0), it.jsonArray.d(1), true) }.toMutableList(),
        )
    }

    private fun expectSectorTimes(times: LapSectorTimes, want: JsonObject, what: String) {
        assertEquals(want.str("lap"), times.lapReference, what)
        assertEquals(want.bool("valid"), times.valid, what)
        assertEquals(want.str("revision"), times.stamp.revision, what)
        assertEquals(want.str("algorithm"), times.stamp.calculationAlgorithm, what)
        assertEquals(want.bool("completePartition"), times.completePartition, what)
        close(times.lapSeconds, want.dbl("lapSeconds"), "$what lap seconds")
        closeOrNull(times.sumSeconds, want["sumSeconds"], "$what sum")
        closeOrNull(times.partitionErrorSeconds, want["partitionErrorSeconds"], "$what partition error")
        val sectors = want.arr("sectors").map { it.jsonObject }
        assertEquals(sectors.size, times.sectors.size, what)
        times.sectors.forEachIndexed { i, sector ->
            val other = sectors[i]
            val where = "$what sector $i"
            assertEquals(
                listOf(other.str("segmentId"), other.str("name"), other.str("type"), other.str("reason")),
                listOf(sector.segmentId, sector.name, sector.type.jsonName, sector.unavailableReason),
                where,
            )
            close(sector.startProgressMeters, other.dbl("start"), where)
            close(sector.endProgressMeters, other.dbl("end"), where)
            close(sector.lengthMeters, other.dbl("lengthMeters"), where)
            close(sector.coveredMeters, other.dbl("coveredMeters"), where)
            closeOrNull(sector.startTime, other["startTime"], "$where start time")
            closeOrNull(sector.endTime, other["endTime"], "$where end time")
            closeOrNull(sector.seconds, other["seconds"], "$where seconds")
        }
    }

    private fun expectBest(best: TheoreticalBestLap, want: JsonObject, what: String) {
        assertEquals(want.bool("valid"), best.valid, what)
        assertEquals(want.str("revision"), best.stamp.revision, what)
        assertEquals(want.str("algorithm"), best.stamp.calculationAlgorithm, what)
        assertEquals(want.str("reason"), best.unavailableReason, what)
        closeOrNull(best.totalSeconds, want["totalSeconds"], "$what total")
        val sectors = want.arr("sectors").map { it.jsonObject }
        assertEquals(sectors.size, best.sectors.size, what)
        best.sectors.forEachIndexed { i, sector ->
            assertEquals(sectors[i].str("segmentId"), sector.segmentId, what)
            assertEquals(sectors[i].str("source"), sector.sourceLapReference ?: "", what)
            assertEquals(sectors[i].str("reason"), sector.unavailableReason, what)
            closeOrNull(sector.seconds, sectors[i]["seconds"], "$what sector $i")
        }
    }

    /** "run-a#3" as Overlays labels it. */
    private fun label(reference: Any?): String {
        val (run, lap) = (reference as String).split('#')
        return "$run · LAP $lap"
    }

    private fun expectSummary(summary: ConsistencySummary, want: JsonObject, what: String) {
        assertEquals(want.int("count"), summary.count, what)
        assertEquals(want.bool("available"), summary.available, what)
        if (!summary.available) {
            assertEquals(want.str("unavailableReason"), summary.unavailableReason, what)
            return
        }
        closeOrNull(summary.minimum, want["minimum"], "$what minimum")
        closeOrNull(summary.q1, want["q1"], "$what q1")
        closeOrNull(summary.median, want["median"], "$what median")
        closeOrNull(summary.q3, want["q3"], "$what q3")
        closeOrNull(summary.maximum, want["maximum"], "$what maximum")
        closeOrNull(summary.interquartileRange, want["interquartileRange"], "$what IQR")
    }

    private fun expectRow(row: TheoreticalBestRow, want: JsonObject, what: String) {
        assertEquals(
            listOf(want.str("segmentId"), want.str("name"), want.str("type")),
            listOf(row.segmentId, row.name, row.type.jsonName),
            what,
        )
        closeOrNull(row.seconds, want["seconds"], "$what seconds")
        if (row.seconds != null) {
            assertEquals(want.str("sourceLapLabel"), label(row.sourceLapReference), what)
        } else {
            assertEquals(want.str("unavailableReason"), row.unavailableReason, what)
        }
        closeOrNull(row.actualSeconds, want["actualSeconds"], "$what actual")
        closeOrNull(row.lossSeconds, want["lossSeconds"], "$what loss")
        expectSummary(row.consistency, want.obj("consistency"), "$what consistency")
    }

    private fun expectPublished(summary: TheoreticalBestSummary, want: JsonObject, what: String) {
        closeOrNull(summary.totalSeconds, want["totalSeconds"], "$what total")
        closeOrNull(summary.differenceSeconds, want["differenceSeconds"], "$what difference")
        assertEquals(want.str("revision"), summary.revision, what)
        assertEquals(want.str("trackConfigurationReference"), summary.trackConfigurationReference, what)
        assertEquals(ConsistencyAlgorithm, want.str("consistencyAlgorithm"), what)
        val actual = want["actualBest"]?.takeUnless { it is JsonNull }?.jsonObject
        assertEquals(actual == null, summary.actualBest == null, what)
        if (actual != null) {
            val best = summary.actualBest!!
            assertEquals(actual.str("label"), label(best.reference), what)
            close(best.lapSeconds, actual.dbl("lapSeconds"), "$what best lap")
            assertEquals(actual.bool("coversWholeLap"), best.coversWholeLap, what)
            closeOrNull(best.sectorSumSeconds, actual["sectorSumSeconds"], "$what best sum")
        }
        val sectors = want.arr("sectors").map { it.jsonObject }
        assertEquals(sectors.size, summary.sectors.size, what)
        summary.sectors.forEachIndexed { i, row -> expectRow(row, sectors[i], "$what row $i") }
        assertEquals(
            want.arr("gains").map { it.jsonObject.str("segmentId") },
            summary.gains.map { it.segmentId },
            "$what gains",
        )
    }

    private fun expectRanking(ranking: TimeLossRanking, want: JsonObject, what: String) {
        if (want.str("state") != "ready") {
            assertEquals(false, ranking.valid, what)
            return
        }
        assertEquals(true, ranking.valid, what)
        assertEquals(want.str("referenceLabel"), label(ranking.referenceLap), what)
        assertEquals(want.int("observationCount"), ranking.observationCount, what)
        assertEquals(want.int("comparedLapCount"), ranking.comparedLapCount, what)
        assertEquals(want.int("untimedWindowCount"), ranking.untimedWindowCount, what)
        assertEquals(want.str("revision"), ranking.stamp.revision, what)
        val losses = want.arr("losses").map { it.jsonObject }
        assertEquals(losses.size, ranking.losses.size, what)
        ranking.losses.forEachIndexed { i, loss ->
            val other = losses[i]
            val window = loss.window
            val where = "$what loss $i"
            assertEquals(
                listOf("lapLabel", "segmentId", "name", "type", "role").map { other.str(it) },
                listOf(
                    label(loss.lapReference),
                    window.segmentId,
                    window.name,
                    window.type.jsonName,
                    window.role.jsonName,
                ),
                where,
            )
            assertEquals(nullableString(other["cornerSegmentId"]), window.cornerSegmentId.ifEmpty { null }, where)
            close(loss.lossSeconds, other.dbl("lossSeconds"), where)
            close(window.startProgressMeters, other.dbl("startMeters"), where)
            close(window.endProgressMeters, other.dbl("endMeters"), where)
            close(loss.coverageLap, other.dbl("coverageLap"), where)
            close(loss.coverageReference, other.dbl("coverageReference"), where)
            closeOrNull(window.cumulativeAtStartSeconds, other["cumulativeAtStartSeconds"], "$where at start")
            closeOrNull(window.cumulativeAtEndSeconds, other["cumulativeAtEndSeconds"], "$where at end")
        }
    }

    private fun expectObservations(observations: TimeLossObservations, want: JsonObject, what: String) {
        assertEquals(want.bool("valid"), observations.valid, what)
        assertEquals(want.str("reason"), observations.unavailableReason, what)
        assertEquals(want.bool("allWindowsTimed"), observations.allWindowsTimed, what)
        close(observations.timedIncrementSumSeconds, want.dbl("timedIncrementSumSeconds"), "$what sum")
        val windows = want.arr("windows").map { it.jsonObject }
        assertEquals(windows.size, observations.windows.size, what)
        observations.windows.forEachIndexed { i, window ->
            val other = windows[i]
            val where = "$what window $i"
            assertEquals(
                listOf(other.str("segmentId"), other.str("role"), other.str("cornerSegmentId"), other.str("reason")),
                listOf(window.segmentId, window.role.jsonName, window.cornerSegmentId, window.unavailableReason),
                where,
            )
            closeOrNull(window.incrementSeconds, other["increment"], "$where increment")
            closeOrNull(window.cumulativeAtStartSeconds, other["atStart"], "$where at start")
            closeOrNull(window.cumulativeAtEndSeconds, other["atEnd"], "$where at end")
        }
    }

    private fun nullableString(value: JsonElement?) = value?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun closeOrNull(actual: Double?, expected: JsonElement?, what: String) {
        if (expected == null || expected is JsonNull) {
            assertNull(actual, what)
        } else {
            assertNotNull(actual, what)
            close(actual, expected.jsonPrimitive.content.toDouble(), what)
        }
    }
}
