@file:Suppress("MagicNumber", "LongMethod")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.progress.buildProgressAxis
import com.flappedear.betterdriver.analysis.progress.computeTrackFeatures
import com.flappedear.betterdriver.analysis.progress.projectLapTrace
import com.flappedear.betterdriver.analysis.segments.ProgressRange
import com.flappedear.betterdriver.analysis.segments.SegmentProposalBoundary
import com.flappedear.betterdriver.analysis.segments.SegmentProposalOptions
import com.flappedear.betterdriver.analysis.segments.SegmentReviewSmoothingMeters
import com.flappedear.betterdriver.analysis.segments.SegmentReviewUnavailable
import com.flappedear.betterdriver.analysis.segments.TrackSegmentProposals
import com.flappedear.betterdriver.analysis.segments.computeSegmentReview
import com.flappedear.betterdriver.analysis.segments.coverageGaps
import com.flappedear.betterdriver.analysis.segments.proposeTrackSegments
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Segment proposals against FlappedEar Telemetry's parity reference (FlappedEar Overlays' C++ over
 * the same corpus): every lap's own review with corner chains kept, every lap's coverage gaps and
 * proposals on the reference lap's axis, and option and gap variants.
 */
class SegmentsParityTest {
    @TestFactory
    fun segments(): List<DynamicTest> = Parity.files("segments_reference.json").map { entry ->
        val name = entry.str("file")
        DynamicTest.dynamicTest(name) { check(name, entry) }
    }

    private fun check(name: String, entry: JsonObject) {
        val session = VboFixture.read(name)
        val laps = deriveSourceLapSession(session)
        val expectedLaps = entry.arr("laps").map { it.jsonObject }
        assertEquals(expectedLaps.map { it.int("lapNumber") }, laps.timedLaps.map { it.number }, name)
        laps.timedLaps.forEachIndexed { index, lap ->
            val want = expectedLaps[index]
            val what = "$name lap ${lap.number}"
            close(lap.startTelemetryTime, want.dbl("start"), what)
            close(lap.endTelemetryTime, want.dbl("end"), what)
            val review = computeSegmentReview(
                session, laps, lap.number, lap.startTelemetryTime, lap.endTelemetryTime, splitCornerChains = false,
            )
            val unavailable = want["unavailable"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            if (unavailable == "noTrace") {
                assertEquals(SegmentReviewUnavailable.NoTrace, review.unavailable, what)
                return@forEachIndexed
            }
            val axis = want.obj("axis")
            assertEquals(axis.bool("valid"), review.axis.valid, what)
            assertEquals(axis.int("pointCount"), review.axis.points.size, what)
            close(review.axis.lengthMeters, axis.dbl("lengthMeters"), what)
            close(review.axis.spacingMeters, axis.dbl("spacingMeters"), what)
            if (unavailable == "noAxis") {
                assertEquals(SegmentReviewUnavailable.NoAxis, review.unavailable, what)
                return@forEachIndexed
            }
            assertNull(unavailable, what)
            val features = want.obj("features")
            assertEquals(features.int("sampleCount"), review.features.samples.size, what)
            strided(features.arr("samples"), review.features.samples.size, 50) { i, row ->
                close(review.features.samples[i].progressMeters, row.d(1), "$what feature $i")
                close(review.features.samples[i].curvaturePerMeter, row.d(2), "$what curvature $i")
            }
            assertEquals(
                want.arr("traceSizes").map { it.jsonPrimitive.content.toInt() },
                review.lapTrace.map { it.samples.size },
                what,
            )
            expectRanges(review.gaps, want.arr("gaps"), what)
            expectProposals(review.proposals, want.obj("proposals"), what)
        }

        if (!entry.containsKey("referenceLap")) return
        val gate = laps.startGate!!
        val eligible = laps.timedLaps.filter { it.referenceEligible }.map { it.number }.toSet()
        val trace = laps.lapTraces.first { it.lapNumber in eligible }
        assertEquals(entry.int("referenceLap"), trace.lapNumber, name)
        val origin = GeoCoordinate(
            (gate.endpointA.latitudeDegrees + gate.endpointB.latitudeDegrees) / 2.0,
            (gate.endpointA.longitudeDegrees + gate.endpointB.longitudeDegrees) / 2.0,
        )
        val axis = buildProgressAxis(trace, origin, gate)
        val features = computeTrackFeatures(axis, SegmentReviewSmoothingMeters)
        val cross = entry.arr("crossLaps").map { it.jsonObject }
        assertEquals(laps.timedLaps.size, cross.size, name)
        laps.timedLaps.forEachIndexed { index, lap ->
            val what = "$name cross lap ${lap.number}"
            assertEquals(cross[index].int("lapNumber"), lap.number, what)
            val gaps = coverageGaps(
                projectLapTrace(axis, session, lap.startTelemetryTime, lap.endTelemetryTime),
                axis.lengthMeters,
            )
            expectRanges(gaps, cross[index].arr("gaps"), what)
            expectProposals(proposeTrackSegments(axis, features, gaps), cross[index].obj("proposals"), what)
        }
        for (variant in entry.arr("variants").map { it.jsonObject }) {
            val values = variant.arr("options").map { it.jsonPrimitive.content.toDouble() }
            val options = SegmentProposalOptions(values[0], values[1], values[2], values[3])
            val gaps = variant.arr("gaps").map { ProgressRange(it.jsonArray.d(0), it.jsonArray.d(1)) }
            expectProposals(
                proposeTrackSegments(axis, features, gaps, options),
                variant.obj("proposals"),
                "$name options $values gaps $gaps",
            )
        }
    }

    private fun expectRanges(actual: List<ProgressRange>, want: JsonArray, what: String) {
        assertEquals(want.size, actual.size, "$what gap count")
        actual.forEachIndexed { i, range ->
            close(range.startMeters, want[i].jsonArray.d(0), "$what gap $i")
            close(range.endMeters, want[i].jsonArray.d(1), "$what gap $i")
        }
    }

    private fun expectProposals(actual: TrackSegmentProposals, want: JsonObject, what: String) {
        assertEquals(want.bool("valid"), actual.valid, what)
        assertEquals(want.str("unresolvedReason"), actual.unresolvedReason?.jsonName ?: "", what)
        val proposals = want.arr("proposals").map { it.jsonObject }
        assertEquals(
            proposals.map { "${it.str("type")} ${it.str("name")}" },
            actual.proposals.map { "${it.type.jsonName} ${it.name}" },
            what,
        )
        actual.proposals.forEachIndexed { i, proposal ->
            val other = proposals[i]
            val where = "$what ${proposal.name}"
            fun boundary(b: SegmentProposalBoundary, expected: JsonElement) {
                val values = expected.jsonArray
                close(b.progressMeters, values.d(0), where)
                close(b.toleranceMeters, values.d(1), where)
                assertEquals(
                    values[2].jsonArray.map { it.jsonPrimitive.content },
                    b.uncertaintyReasons.map { it.jsonName },
                    where,
                )
            }
            boundary(proposal.start, other.getValue("start"))
            boundary(proposal.end, other.getValue("end"))
            close(proposal.lengthMeters, other.dbl("lengthMeters"), where)
            close(proposal.turnRadians, other.dbl("turnRadians"), where)
            close(proposal.peakCurvaturePerMeter, other.dbl("peakCurvaturePerMeter"), where)
            assertEquals(other.int("chainedCorners"), proposal.chainedCorners, where)
        }
    }
}
