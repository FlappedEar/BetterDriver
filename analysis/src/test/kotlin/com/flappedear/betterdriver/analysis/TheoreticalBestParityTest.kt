@file:Suppress("MagicNumber", "LongMethod")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.ProjectedSample
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.LapSectorTimes
import com.flappedear.betterdriver.analysis.segments.PopulationLap
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestLap
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.compareSectorTimes
import com.flappedear.betterdriver.analysis.segments.computeLapSectorTimes
import com.flappedear.betterdriver.analysis.segments.computeTheoreticalBest
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
                PopulationLap(session, lap.startTelemetryTime, lap.endTelemetryTime, "${it.str("runId")}#${lap.number}")
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
    fun theoreticalBestOfHandMadePopulation() {
        val item = cases.obj("population")
        val best = item.obj("best")
        val approved = approved(item.arr("stored"), best.str("revision"))
        val other = approved(item.arr("otherStored"), "other")
        val population = item.arr("laps").map { it.jsonObject }.mapIndexed { i, lap ->
            computeLapSectorTimes(
                if (lap.bool("other")) other else approved,
                1000.0,
                trace(lap.arr("trace")),
                lap.dbl("start"),
                lap.dbl("end"),
                "${if (i % 2 == 1) "b" else "a"}#$i",
            )
        }
        expectBest(computeTheoreticalBest(approved, population), best, "population")
        expectBest(
            computeTheoreticalBest(ApprovedSegments(emptyList(), ""), population),
            item.obj("bestNone"),
            "no segments",
        )
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
