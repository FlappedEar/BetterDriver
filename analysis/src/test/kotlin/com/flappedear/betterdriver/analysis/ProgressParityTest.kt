@file:Suppress("MagicNumber", "LongMethod")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.progress.DeltaPoint
import com.flappedear.betterdriver.analysis.progress.DeltaTiming
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.ProjectedSample
import com.flappedear.betterdriver.analysis.progress.buildProgressAxis
import com.flappedear.betterdriver.analysis.progress.computeDeltaSeries
import com.flappedear.betterdriver.analysis.progress.computeTimedDeltaSeries
import com.flappedear.betterdriver.analysis.progress.computeTrackFeatures
import com.flappedear.betterdriver.analysis.progress.progressAtTime
import com.flappedear.betterdriver.analysis.progress.projectLapTrace
import com.flappedear.betterdriver.analysis.progress.timeAtProgress
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The track-progress port against FlappedEar Telemetry's parity reference (itself the output of
 * FlappedEar Overlays' C++ over the same corpus): lap detection, the axis, features, every lap's
 * projection and the delta series. Counts and indices must match exactly; doubles to 1e-9.
 */
class ProgressParityTest {
    @TestFactory
    fun progress(): List<DynamicTest> = Parity.files("progress_reference.json").map { entry ->
        val name = entry.str("file")
        DynamicTest.dynamicTest(name) { check(name, entry) }
    }

    private fun check(name: String, entry: JsonObject) {
        val session = VboFixture.read(name)
        val laps = deriveSourceLapSession(session)
        val gate = laps.startGate!!
        val origin = GeoCoordinate(
            (gate.endpointA.latitudeDegrees + gate.endpointB.latitudeDegrees) / 2.0,
            (gate.endpointA.longitudeDegrees + gate.endpointB.longitudeDegrees) / 2.0,
        )
        val eligible = laps.timedLaps.filter { it.referenceEligible }.map { it.number }.toSet()
        val referenceTrace = laps.lapTraces.first { it.lapNumber in eligible }
        assertEquals(entry.int("referenceLap"), referenceTrace.lapNumber, "$name reference lap")

        for (expected in entry.arr("sampled")) {
            val o = expected.jsonObject
            val actual = session.sampledSegments(o.str("channel"), o.dbl("start"), o.dbl("end"), o.int("maximumPoints"))
            val sizes = o.arr("segmentSizes").map { it.jsonPrimitive.content.toInt() }
            assertEquals(sizes, actual.map { it.size }, "$name sampled")
            var p = 0
            var ordinal = 0
            val points = o.arr("points")
            actual.forEachIndexed { s, seg ->
                seg.forEachIndexed { i, point ->
                    if (ordinal++ % 10 == 0) {
                        val want = points[p++].jsonArray
                        assertEquals(listOf(want.i(0), want.i(1)), listOf(s, i))
                        close(point.time, want.d(2), "$name sampled time")
                        close(point.value, want.d(3), "$name sampled value")
                    }
                }
            }
            assertEquals(points.size, p)
        }

        val axis = buildProgressAxis(referenceTrace, origin, gate)
        val axisWant = entry.obj("axis")
        assertEquals(axisWant.bool("valid"), axis.valid, "$name axis valid")
        assertEquals(axisWant.int("pointCount"), axis.points.size, "$name axis points")
        close(axis.lengthMeters, axisWant.dbl("lengthMeters"), "$name axis length")
        close(axis.spacingMeters, axisWant.dbl("spacingMeters"), "$name axis spacing")
        strided(axisWant.arr("points"), axis.points.size, 50) { i, want ->
            close(axis.points[i].eastMeters, want.d(1), "$name axis east $i")
            close(axis.points[i].northMeters, want.d(2), "$name axis north $i")
            close(axis.cumulative[i], want.d(3), "$name axis cumulative $i")
        }

        val features = computeTrackFeatures(axis, 15.0)
        val featuresWant = entry.obj("features")
        assertEquals(featuresWant.bool("valid"), features.valid)
        assertEquals(featuresWant.int("sampleCount"), features.samples.size)
        strided(featuresWant.arr("samples"), features.samples.size, 50) { i, want ->
            val s = features.samples[i]
            close(s.progressMeters, want.d(1), "$name feature progress $i")
            close(s.headingRadians, want.d(2), "$name feature heading $i")
            close(s.curvaturePerMeter, want.d(3), "$name feature curvature $i")
        }

        val projections = entry.arr("projections")
        assertEquals(projections.size, laps.timedLaps.size, "$name lap count")
        val projected = laps.timedLaps.mapIndexed { i, lap ->
            projectLapTrace(axis, session, lap.startTelemetryTime, lap.endTelemetryTime).also {
                checkProjection(name, lap, it, projections[i].jsonObject)
            }
        }

        val fastestWant = entry["fastestLapIndex"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toInt()
        assertEquals(fastestWant, laps.fastestLapIndex)
        val delta = entry["delta"]?.takeUnless { it is JsonNull }?.jsonObject ?: return
        val fastest = laps.fastestLapIndex!!
        checkDelta(name, computeDeltaSeries(projected.first(), projected[fastest], 5.0), delta)
        val first = laps.timedLaps.first()
        val best = laps.timedLaps[fastest]
        val timing = DeltaTiming(
            first.startTelemetryTime, first.endTelemetryTime,
            best.startTelemetryTime, best.endTelemetryTime, axis.lengthMeters,
        )
        val timed = computeTimedDeltaSeries(projected.first(), projected[fastest], 5.0, timing)
        checkDelta(name, timed, entry.obj("timedDelta"))
    }

    private fun checkProjection(name: String, lap: TimedLap, segments: List<ProgressSegment>, want: JsonObject) {
        val what = "$name lap ${lap.number}"
        assertEquals(want.int("lapNumber"), lap.number, what)
        val wantSegments = want.arr("segments").map { it.jsonObject }
        val sizes = wantSegments.map { it.int("sampleCount") }
        assertEquals(sizes, segments.map { it.samples.size }, "$what segment sizes")
        segments.forEachIndexed { s, segment ->
            val w = wantSegments[s]
            fun expectSample(sample: ProjectedSample, values: List<Double>) {
                assertTrue(sample.valid, what)
                close(sample.telemetryTime, values[0], "$what time")
                close(sample.progressMeters, values[1], "$what progress")
            }
            expectSample(segment.samples.first(), w.arr("first").map { it.jsonPrimitive.content.toDouble() })
            expectSample(segment.samples.last(), w.arr("last").map { it.jsonPrimitive.content.toDouble() })
            strided(w.arr("samples"), segment.samples.size, 25) { i, row ->
                expectSample(segment.samples[i], listOf(row.d(1), row.d(2)))
            }
        }
        for (row in want.arr("timeAtProgress")) {
            val r = row.jsonArray
            closeOrNull(timeAtProgress(segments, r.d(0)), r[1], "$what time at ${r.d(0)}")
        }
        for (row in want.arr("progressAtTime")) {
            val r = row.jsonArray
            closeOrNull(progressAtTime(segments, r.d(0)), r[1], "$what progress at ${r.d(0)}")
        }
    }

    private fun checkDelta(name: String, series: List<List<DeltaPoint>>, want: JsonObject) {
        val wantSeries = want.arr("series").map { it.jsonObject }
        assertEquals(wantSeries.map { it.int("pointCount") }, series.map { it.size }, "$name delta sizes")
        series.forEachIndexed { s, points ->
            val w = wantSeries[s]
            fun expectPoint(p: DeltaPoint, v: List<Double>) {
                close(p.progressMeters, v[0], "$name delta progress")
                close(p.deltaSeconds, v[1], "$name delta seconds")
            }
            expectPoint(points.first(), w.arr("first").map { it.jsonPrimitive.content.toDouble() })
            expectPoint(points.last(), w.arr("last").map { it.jsonPrimitive.content.toDouble() })
            strided(w.arr("points"), points.size, 50) { i, row -> expectPoint(points[i], listOf(row.d(1), row.d(2))) }
        }
    }

    private fun closeOrNull(actual: Double?, expected: JsonElement?, what: String) {
        if (expected == null || expected is JsonNull) {
            assertNull(actual, what)
        } else {
            assertNotNull(actual, what)
            close(actual, expected.jsonPrimitive.content.toDouble(), what)
        }
    }
}

/** Reads Telemetry's parity reference files and compares like its tests do. */
internal object Parity {
    fun load(file: String): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(
        Parity::class.java.getResourceAsStream("/parity/$file")!!.readBytes().decodeToString(),
    ).jsonObject

    fun files(file: String): List<JsonObject> = load(file).arr("files").map { it.jsonObject }
}

internal fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
internal fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.content.toInt()
internal fun JsonObject.dbl(key: String) = getValue(key).jsonPrimitive.content.toDouble()
internal fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.content.toBooleanStrict()
internal fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
internal fun JsonArray.d(i: Int) = this[i].jsonPrimitive.content.toDouble()
internal fun JsonArray.i(i: Int) = this[i].jsonPrimitive.content.toInt()

/** Checks every [stride]-th of [count] items against [want], whose rows start with the index. */
internal fun strided(want: JsonArray, count: Int, stride: Int, check: (Int, JsonArray) -> Unit) {
    var p = 0
    var i = 0
    while (i < count) {
        val row = want[p++].jsonArray
        assertEquals(row.i(0), i)
        check(i, row)
        i += stride
    }
    assertEquals(want.size, p)
}

/** Equal to 1e-9 relative, or 1e-9 absolute near zero, as Telemetry's parity tests compare. */
internal fun close(actual: Double, expected: Double, what: String) {
    val tolerance = maxOf(1e-9, 1e-9 * kotlin.math.abs(expected))
    assertTrue(kotlin.math.abs(actual - expected) <= tolerance, "$what: expected $expected, got $actual")
}
