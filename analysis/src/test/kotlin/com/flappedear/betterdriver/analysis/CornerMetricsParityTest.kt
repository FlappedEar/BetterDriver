@file:Suppress("MagicNumber", "LongMethod", "TooManyFunctions")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.corners.BrakingMetrics
import com.flappedear.betterdriver.analysis.corners.BrakingMetricsOptions
import com.flappedear.betterdriver.analysis.corners.BrakingOnsetDetection
import com.flappedear.betterdriver.analysis.corners.BrakingOnsetOptions
import com.flappedear.betterdriver.analysis.corners.BrakingThreshold
import com.flappedear.betterdriver.analysis.corners.CornerLapMetrics
import com.flappedear.betterdriver.analysis.corners.CornerLapObservation
import com.flappedear.betterdriver.analysis.corners.CornerPhasePoint
import com.flappedear.betterdriver.analysis.corners.CornerSpeedValue
import com.flappedear.betterdriver.analysis.corners.CornerSpeeds
import com.flappedear.betterdriver.analysis.corners.CornerVariability
import com.flappedear.betterdriver.analysis.corners.DrivingVariabilityAlgorithm
import com.flappedear.betterdriver.analysis.corners.ExitMetrics
import com.flappedear.betterdriver.analysis.corners.ExitMetricsOptions
import com.flappedear.betterdriver.analysis.corners.compareBrakingMetrics
import com.flappedear.betterdriver.analysis.corners.compareCornerSpeeds
import com.flappedear.betterdriver.analysis.corners.compareExitMetrics
import com.flappedear.betterdriver.analysis.corners.computeBrakingMetrics
import com.flappedear.betterdriver.analysis.corners.computeExitMetrics
import com.flappedear.betterdriver.analysis.corners.cornerFromSegment
import com.flappedear.betterdriver.analysis.corners.detectBrakingOnsets
import com.flappedear.betterdriver.analysis.corners.lateralOffsetMeters
import com.flappedear.betterdriver.analysis.corners.measureCornerLap
import com.flappedear.betterdriver.analysis.corners.proposeCornerGeometryPhases
import com.flappedear.betterdriver.analysis.corners.summarizeCornerVariability
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.ProjectedSample
import com.flappedear.betterdriver.analysis.progress.computeTrackFeatures
import com.flappedear.betterdriver.analysis.progress.projectLapTrace
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.ConsistencySummary
import com.flappedear.betterdriver.analysis.segments.SegmentReviewSmoothingMeters
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Corner Analyzer (phases, speeds, braking, exit, comparisons, observations and variability)
 * against FlappedEar Telemetry's parity reference (FlappedEar Overlays' C++): every corpus file
 * with its segments, shifted segments and every other segment, on the dump's own axis, plus
 * hand-made sessions, projections and observations.
 */
class CornerMetricsParityTest {
    private val reference = Parity.load("corner_metrics_reference.json")
    private val cases = reference.obj("cases")

    @Test
    fun referenceCoversMeasuredInferredAndMissingChannels() {
        val corners = reference.arr("files").flatMap { file ->
            file.jsonObject.arr("variants").flatMap { it.jsonObject.arr("corners").map { c -> c.jsonObject } }
        }
        assertTrue(corners.map { it.obj("braking").str("method") }.toSet()
            .containsAll(listOf("measuredBrake", "inferredDeceleration", "")))
        assertTrue(corners.map { it.obj("exit").obj("pickup").str("method") }.toSet()
            .containsAll(listOf("measuredThrottle", "inferredAcceleration", "")))
        assertTrue(corners.count { it.obj("braking")["point"] !is JsonNull } > 30)
    }

    @TestFactory
    fun days(): List<DynamicTest> = reference.arr("files").map { it.jsonObject }.map { entry ->
        val name = entry.str("file")
        DynamicTest.dynamicTest(name) { checkDay(name, entry) }
    }

    private fun checkDay(name: String, entry: JsonObject) {
        val session = VboFixture.read(name)
        val laps = deriveSourceLapSession(session)
        val byNumber = laps.timedLaps.associateBy { it.number }
        val population = entry.arr("population").map { it.jsonObject }.map {
            val lap = byNumber.getValue(it.int("lapNumber"))
            Triple("${it.str("runId")}#${lap.number}", lap.startTelemetryTime, lap.endTelemetryTime)
        }
        val axis = cppAxis(entry.obj("axis"))
        val features = computeTrackFeatures(axis, SegmentReviewSmoothingMeters)
        val actualBest = entry.str("actualBest")
        for (variant in entry.arr("variants").map { it.jsonObject }) {
            val what = "$name ${variant.str("name")}"
            assertEquals("", variant.str("error"), what)
            val canonical = variant.str("canonicalRunId")
            val approved = approved(variant.obj("segments").getValue(canonical), variant.str("revision"))

            val phases = variant.arr("phases").map { it.jsonObject }
            assertEquals(approved.segments.size, phases.size, what)
            approved.segments.zip(phases).forEach { (segment, want) ->
                val label = "$what ${segment.id}"
                assertEquals(want.str("segmentId"), segment.id, label)
                val corner = cornerFromSegment(axis, features, segment)
                val geometry = proposeCornerGeometryPhases(axis, features, corner)
                assertEquals(want.str("cornerType"), corner.type.jsonName, label)
                close(corner.turnRadians, want.dbl("turn"), "$label turn")
                close(corner.peakCurvaturePerMeter, want.dbl("peak"), "$label peak")
                close(corner.lengthMeters, want.dbl("lengthMeters"), "$label length")
                assertEquals(want.bool("valid"), geometry.valid, label)
                expectPhase(geometry.entry, want.obj("entry"), "$label entry")
                expectPhase(geometry.apex, want.obj("apex"), "$label apex")
                expectPhase(geometry.exit, want.obj("exit"), "$label exit")
                expectNumbers(geometry.apexCandidatesMeters, want.arr("candidates"), "$label candidates")
            }

            val byLap = LinkedHashMap<String, LinkedHashMap<String, CornerLapMetrics>>()
            for ((reference, start, end) in population) {
                val trace = projectLapTrace(axis, session, start, end)
                for (segment in approved.segments.filter { it.type == TrackSegmentType.Corner }) {
                    byLap.getOrPut(reference) { LinkedHashMap() }[segment.id] = measureCornerLap(
                        axis, features, approved, segment.id, segment.startProgressMeters,
                        segment.endProgressMeters, trace, session, start, end, reference,
                    )
                }
            }
            val corners = variant.arr("corners").map { it.jsonObject }
            assertEquals(corners.size, byLap.values.sumOf { it.size }, what)
            for (corner in corners) {
                val label = "$what ${corner.str("lap")} ${corner.str("segmentId")}"
                val metrics = byLap.getValue(corner.str("lap")).getValue(corner.str("segmentId"))
                expectSpeeds(metrics.speeds, corner.obj("speeds"), label)
                expectBraking(metrics.braking, corner.obj("braking"), label)
                expectExit(metrics.exit, corner.obj("exit"), label)
            }
            for (comparison in variant.arr("comparisons").map { it.jsonObject }) {
                val id = comparison.str("segmentId")
                val label = "$what ${comparison.str("lap")} $id comparison"
                val a = byLap.getValue(comparison.str("lap")).getValue(id)
                val b = byLap.getValue(actualBest).getValue(id)
                val speeds = compareCornerSpeeds(a.speeds, b.speeds)
                val wantSpeeds = comparison.obj("speeds")
                assertEquals(wantSpeeds.bool("valid"), speeds.valid, label)
                assertEquals(wantSpeeds.str("reason"), speeds.unavailableReason, label)
                closeOrNull(speeds.entryDelta, wantSpeeds["entry"], "$label entry")
                closeOrNull(speeds.apexDelta, wantSpeeds["apex"], "$label apex")
                closeOrNull(speeds.minimumDelta, wantSpeeds["minimum"], "$label minimum")
                closeOrNull(speeds.exitDelta, wantSpeeds["exit"], "$label exit")
                val braking = compareBrakingMetrics(a.braking, b.braking)
                val wantBraking = comparison.obj("braking")
                assertEquals(wantBraking.bool("valid"), braking.valid, label)
                assertEquals(wantBraking.str("reason"), braking.unavailableReason, label)
                closeOrNull(braking.brakingPointDeltaMeters, wantBraking["point"], "$label point")
                closeOrNull(braking.brakingSecondsDelta, wantBraking["seconds"], "$label seconds")
                closeOrNull(braking.brakingDistanceDeltaMeters, wantBraking["distance"], "$label distance")
                closeOrNull(braking.peakDecelerationDelta, wantBraking["peak"], "$label peak")
                closeOrNull(braking.meanDecelerationDelta, wantBraking["mean"], "$label mean")
                val exit = compareExitMetrics(a.exit, b.exit)
                val wantExit = comparison.obj("exit")
                assertEquals(wantExit.bool("valid"), exit.valid, label)
                assertEquals(wantExit.str("pickupReason"), exit.pickupUnavailableReason, label)
                closeOrNull(exit.pickupDeltaMeters, wantExit["pickup"], "$label pickup")
                closeOrNull(exit.exitSpeedDelta, wantExit["exitSpeed"], "$label exit speed")
                closeOrNull(exit.intervalEndSpeedDelta, wantExit["endSpeed"], "$label end speed")
                closeOrNull(exit.elapsedSecondsDelta, wantExit["elapsed"], "$label elapsed")
            }
            for (observation in variant.arr("observations").map { it.jsonObject }) {
                val metrics = byLap.getValue(observation.str("lap")).getValue(observation.str("segmentId"))
                expectObservation(metrics.observation, observation, "$what ${observation.str("lap")}")
            }

            // Every corner gets a variability row, summarized over the population in its order.
            val variability = variant.arr("variability").map { it.jsonObject }.associateBy { it.str("segmentId") }
            val cornerSegments = approved.segments.filter { it.type == TrackSegmentType.Corner }
            assertEquals(variability.keys, cornerSegments.map { it.id }.toSet(), what)
            for (segment in cornerSegments) {
                val observations = population.mapNotNull { byLap[it.first]?.get(segment.id)?.observation }
                expectVariability(
                    summarizeCornerVariability(segment.id, segment.name, observations),
                    variability.getValue(segment.id),
                    "$what ${segment.id} variability",
                )
            }
            assertEquals(variant.str("variabilityAlgorithm"), DrivingVariabilityAlgorithm, what)
        }
    }

    @Test
    fun brakingOnsetsInHandMadeSessions() {
        val sessions = sessions()
        val lap = trace(cases.obj("traces").arr("full"))
        for (item in cases.arr("onsets").map { it.jsonObject }) {
            val options = item.obj("options")
            val detection = detectBrakingOnsets(
                sessions.getValue(item.str("session")),
                item.dbl("start"),
                item.dbl("end"),
                BrakingOnsetOptions(
                    measuredBrake = BrakingThreshold(options.dbl("measuredOn"), options.dbl("measuredOff"), "%"),
                    inferredDeceleration = BrakingThreshold(options.dbl("inferredOn"), options.dbl("inferredOff"), "g"),
                    minimumDurationSeconds = options.dbl("minimumDuration"),
                    allowInferred = options.bool("allowInferred"),
                ),
                if (item["trace"]?.jsonPrimitive?.booleanOrNull == true) lap else null,
            )
            expectDetection(detection, item.obj("result"), "${item.str("session")} ${item.str("start")}")
        }
    }

    @Test
    fun brakingAndExitMetricsOnHandMadeProjections() {
        val sessions = sessions()
        val traces = cases.obj("traces").mapValues { trace(it.value.jsonArray) }
        val segments = cases.obj("segments")
        val configuration = cases.str("configuration")
        for (item in cases.arr("metrics").map { it.jsonObject }) {
            val session = sessions.getValue(item.str("session"))
            // The revision is the stored segments' hash, which is storage, not ported: taken as given.
            val revision = item.obj("braking").str("revision")
            val approved = approved(segments.getValue(item.str("segments")), revision, configuration)
            val trace = traces.getValue(item.str("trace"))
            val id = item.str("segmentId")
            val what = "${item.str("session")} ${item.str("segments")} ${item.str("trace")} $id ${item.str("approach")}"
            expectBraking(
                computeBrakingMetrics(
                    2000.0, approved, id, trace, session, 0.0, 20.0,
                    BrakingMetricsOptions(approachMeters = item.dbl("approach")),
                ),
                item.obj("braking"),
                what,
            )
            val options = ExitMetricsOptions(followMeters = item.dbl("follow"))
            expectExit(computeExitMetrics(2000.0, approved, id, trace, session, 20.0, options), item.obj("exit"), what)
            expectExit(
                computeExitMetrics(2000.0, approved, id, trace, session, null, options),
                item.obj("exitNoEnd"),
                "$what without an end",
            )
        }
    }

    @Test
    fun lateralOffsetsAndVariabilityOfFixedInputs() {
        val axis = ProgressAxis(
            points = listOf(MetricPoint(0.0, 0.0), MetricPoint(10.0, 0.0), MetricPoint(10.0, 10.0),
                MetricPoint(0.0, 10.0), MetricPoint(0.0, 0.0)),
            cumulative = doubleArrayOf(0.0, 10.0, 20.0, 30.0, 40.0),
            lengthMeters = 40.0,
            spacingMeters = 10.0,
            valid = true,
        )
        for (item in cases.arr("offsets").map { it.jsonObject }) {
            closeOrNull(
                lateralOffsetMeters(axis, item.dbl("progress"), MetricPoint(item.dbl("east"), item.dbl("north"))),
                item["offset"],
                "offset $item",
            )
        }
        val observations = cases.arr("observations").map { it.jsonObject }.map {
            CornerLapObservation(
                lapReference = it.str("lap"),
                brakingPointMeters = optional(it["braking"]),
                brakingProvenance = it.str("brakingProvenance"),
                apexSpeed = optional(it["apex"]),
                minimumSpeed = optional(it["minimum"]),
                exitSpeed = optional(it["exit"]),
                pickupMeters = optional(it["pickup"]),
                pickupProvenance = it.str("pickupProvenance"),
                lineOffsetMeters = optional(it["line"]),
                gpsAccuracyMeters = optional(it["accuracy"]),
            )
        }
        val three = cases.obj("variability")
        val variability = summarizeCornerVariability("c1", "C1", observations)
        assertEquals(listOf(three.str("segmentId"), three.str("name")), listOf(variability.segmentId, variability.name))
        expectVariability(variability, three, "variability")
        expectVariability(
            summarizeCornerVariability("c1", "C1", observations, minimumSamples = 2),
            cases.obj("variabilityTwo"),
            "variability of two",
        )
    }

    private fun sessions(): Map<String, TelemetrySession> =
        cases.arr("sessions").map { it.jsonObject }.associate(::session)

    private fun session(spec: JsonObject): Pair<String, TelemetrySession> {
        val channels = LinkedHashMap<String, TelemetryChannel>()
        for (channel in spec.arr("channels").map { it.jsonObject }) {
            val name = channel.str("name")
            channels[name] = TelemetryChannel(
                name,
                channel.arr("times").map { it.jsonPrimitive.content.toDouble() }.toDoubleArray(),
                // Stored as float32, as Telemetry stores samples.
                channel.arr("values").map { (optional(it) ?: Double.NaN).toFloat().toDouble() }.toDoubleArray(),
                channel.str("unit"),
            )
        }
        val aliases = spec.obj("aliases").mapValues { it.value.jsonPrimitive.content }
        return spec.str("name") to TelemetrySession(channels, aliases)
    }

    private fun approved(stored: JsonElement, revision: String, configuration: String? = null) = ApprovedSegments(
        stored.jsonArray.map { it.jsonObject }
            .filter { configuration == null || it.str("trackConfigurationReference") == configuration }
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
    )

    private fun cppAxis(value: JsonObject) = ProgressAxis(
        points = value.arr("points").map { MetricPoint(it.jsonArray.d(0), it.jsonArray.d(1)) },
        cumulative = value.arr("cumulative").map { it.jsonPrimitive.content.toDouble() }.toDoubleArray(),
        lengthMeters = value.dbl("lengthMeters"),
        spacingMeters = value.dbl("spacingMeters"),
        origin = GeoCoordinate(value.dbl("originLatitude"), value.dbl("originLongitude")),
        valid = value["valid"]?.jsonPrimitive?.booleanOrNull != false,
    )

    private fun trace(value: JsonArray): List<ProgressSegment> = value.map { segment ->
        ProgressSegment(
            segment.jsonArray.map { ProjectedSample(it.jsonArray.d(0), it.jsonArray.d(1), true) }.toMutableList(),
        )
    }

    private fun expectPhase(point: CornerPhasePoint, want: JsonObject, what: String) {
        assertEquals(want.str("method"), point.method, what)
        assertEquals(strings(want.arr("uncertainty")), point.uncertaintyReasons, what)
        assertEquals(want.str("reason"), point.unresolvedReason, what)
        close(point.progressMeters, want.dbl("progress"), "$what progress")
        close(point.toleranceMeters, want.dbl("tolerance"), "$what tolerance")
        expectJson(point.evidence, want.obj("evidence"), "$what evidence")
    }

    private fun expectJson(actual: Any?, expected: JsonElement, what: String) {
        when {
            expected is JsonNull -> assertNull(actual, what)
            expected is JsonArray -> {
                assertTrue(actual is List<*>, what)
                assertEquals(expected.size, actual.size, what)
                actual.forEachIndexed { i, item -> expectJson(item, expected[i], what) }
            }
            expected is JsonObject -> {
                assertTrue(actual is Map<*, *>, what)
                assertEquals(expected.keys, actual.keys.map { it.toString() }.toSet(), what)
                for (key in expected.keys) expectJson(actual[key], expected.getValue(key), "$what.$key")
            }
            expected is JsonPrimitive && expected.isString -> assertEquals(expected.content, actual, what)
            expected is JsonPrimitive && expected.booleanOrNull != null ->
                assertEquals(expected.booleanOrNull, actual, what)
            else -> {
                assertTrue(actual is Number, "$what: $actual is not a number")
                close(actual.toDouble(), expected.jsonPrimitive.doubleOrNull!!, what)
            }
        }
    }

    private fun expectSpeed(value: CornerSpeedValue, want: JsonObject, what: String) {
        closeOrNull(value.value, want["value"], "$what value")
        close(value.progressMeters, want.dbl("progress"), "$what progress")
        closeOrNull(value.telemetryTime, want["time"], "$what time")
        assertEquals(want.str("reason"), value.unavailableReason, what)
        assertEquals(strings(want.arr("limitations")), value.limitations, what)
    }

    private fun expectSpeeds(speeds: CornerSpeeds, want: JsonObject, what: String) {
        assertEquals(
            listOf(
                want.bool("valid"), want.str("segmentId"), want.str("name"), want.str("type"), want.str("channel"),
                unitAsTelemetryReadsIt(want.str("unit"), speeds.unit), want.str("provenance"),
                want.str("revision"), want.str("algorithm"),
            ),
            listOf(
                speeds.valid, speeds.segmentId, speeds.name, speeds.type, speeds.channel, speeds.unit,
                speeds.provenance, speeds.stamp.revision, speeds.stamp.calculationAlgorithm,
            ),
            what,
        )
        expectSpeed(speeds.entry, want.obj("entry"), "$what entry")
        expectSpeed(speeds.apex, want.obj("apex"), "$what apex")
        expectSpeed(speeds.minimum, want.obj("minimum"), "$what minimum")
        expectSpeed(speeds.exit, want.obj("exit"), "$what exit")
        close(speeds.lengthMeters, want.dbl("lengthMeters"), "$what length")
        close(speeds.coveredMeters, want.dbl("coveredMeters"), "$what covered")
        close(speeds.meanSampleSpacingMeters, want.dbl("spacing"), "$what spacing")
    }

    private fun expectBraking(braking: BrakingMetrics, want: JsonObject, what: String) {
        assertEquals(
            listOf(
                want.bool("valid"), want.str("segmentId"), want.str("reason"), want.str("method"),
                want.str("provenance"), want.str("channel"), want.str("thresholdUnit"),
                strings(want.arr("limitations")), want.str("decelerationChannel"), want.str("decelerationUnit"),
                want.str("decelerationReason"), want.str("revision"), want.str("algorithm"),
            ),
            listOf(
                braking.valid, braking.segmentId, braking.unavailableReason, braking.method, braking.provenance,
                braking.channel, braking.thresholdUnit, braking.limitations, braking.decelerationChannel,
                braking.decelerationUnit, braking.decelerationUnavailableReason, braking.stamp.revision,
                braking.stamp.calculationAlgorithm,
            ),
            what,
        )
        close(braking.intervalStartMeters, want.dbl("intervalStart"), "$what interval start")
        close(braking.intervalEndMeters, want.dbl("intervalEnd"), "$what interval end")
        close(braking.entryMeters, want.dbl("entry"), "$what entry")
        close(braking.onThreshold, want.dbl("onThreshold"), "$what on")
        closeOrNull(braking.brakingPointMeters, want["point"], "$what point")
        closeOrNull(braking.brakingPointTime, want["pointTime"], "$what point time")
        closeOrNull(braking.distanceBeforeEntryMeters, want["beforeEntry"], "$what before entry")
        closeOrNull(braking.brakingSeconds, want["seconds"], "$what seconds")
        closeOrNull(braking.brakingDistanceMeters, want["distance"], "$what distance")
        closeOrNull(braking.peakDeceleration, want["peak"], "$what peak")
        closeOrNull(braking.meanDeceleration, want["mean"], "$what mean")
    }

    private fun expectExit(exit: ExitMetrics, want: JsonObject, what: String) {
        val pickup = want.obj("pickup")
        assertEquals(
            listOf(
                want.bool("valid"), want.str("segmentId"), pickup.str("method"), pickup.str("provenance"),
                pickup.str("channel"), pickup.str("unit"), pickup.str("thresholdUnit"), pickup.str("reason"),
                strings(pickup.arr("limitations")), want.str("source"), want.str("speedChannel"),
                unitAsTelemetryReadsIt(want.str("speedUnit"), exit.speedUnit), want.str("reason"),
                want.str("revision"), want.str("algorithm"),
            ),
            listOf(
                exit.valid, exit.segmentId, exit.pickup.method, exit.pickup.provenance, exit.pickup.channel,
                exit.pickup.unit, exit.pickup.threshold.unit, exit.pickup.unavailableReason, exit.pickup.limitations,
                exit.intervalSource, exit.speedChannel, exit.speedUnit, exit.downstreamUnavailableReason,
                exit.stamp.revision, exit.stamp.calculationAlgorithm,
            ),
            what,
        )
        close(exit.pickup.threshold.on, pickup.dbl("on"), "$what pickup on")
        close(exit.pickup.threshold.off, pickup.dbl("off"), "$what pickup off")
        closeOrNull(exit.pickup.progressMeters, pickup["progress"], "$what pickup")
        closeOrNull(exit.pickup.telemetryTime, pickup["time"], "$what pickup time")
        close(exit.intervalStartMeters, want.dbl("intervalStart"), "$what interval start")
        close(exit.intervalEndMeters, want.dbl("intervalEnd"), "$what interval end")
        closeOrNull(exit.exitSpeed, want["exitSpeed"], "$what exit speed")
        closeOrNull(exit.intervalEndSpeed, want["endSpeed"], "$what end speed")
        closeOrNull(exit.elapsedSeconds, want["elapsed"], "$what elapsed")
    }

    private fun expectDetection(detection: BrakingOnsetDetection, want: JsonObject, what: String) {
        assertEquals(
            listOf(
                want.bool("valid"), want.str("method"), want.str("provenance"), want.str("channel"),
                // The unit as the recording declares it, without the spaces round it.
                want.str("channelUnit").trim(), want.str("thresholdUnit"), want.int("rejectedSpikes"),
                want.int("gaps"), want.str("reason"),
            ),
            listOf(
                detection.valid, detection.method, detection.provenance, detection.channel, detection.channelUnit,
                detection.threshold.unit, detection.rejectedSpikes, detection.gaps, detection.unresolvedReason,
            ),
            what,
        )
        close(detection.threshold.on, want.dbl("on"), "$what on")
        close(detection.threshold.off, want.dbl("off"), "$what off")
        close(detection.minimumDurationSeconds, want.dbl("minimumDuration"), "$what minimum duration")
        val candidates = want.arr("candidates").map { it.jsonObject }
        assertEquals(candidates.size, detection.candidates.size, what)
        detection.candidates.zip(candidates).forEachIndexed { i, (candidate, other) ->
            val label = "$what candidate $i"
            close(candidate.telemetryTime, other.dbl("time"), "$label time")
            close(candidate.toleranceSeconds, other.dbl("tolerance"), "$label tolerance")
            close(candidate.durationSeconds, other.dbl("duration"), "$label duration")
            close(candidate.peakValue, other.dbl("peak"), "$label peak")
            closeOrNull(candidate.progressMeters, other["progress"], "$label progress")
            assertEquals(strings(other.arr("uncertainty")), candidate.uncertaintyReasons, label)
        }
    }

    private fun expectObservation(observation: CornerLapObservation, want: JsonObject, what: String) {
        closeOrNull(observation.brakingPointMeters, want["braking"], "$what braking")
        assertEquals(want.str("brakingProvenance"), observation.brakingProvenance, what)
        closeOrNull(observation.apexSpeed, want["apex"], "$what apex")
        closeOrNull(observation.minimumSpeed, want["minimum"], "$what minimum")
        closeOrNull(observation.exitSpeed, want["exit"], "$what exit")
        closeOrNull(observation.pickupMeters, want["pickup"], "$what pickup")
        assertEquals(want.str("pickupProvenance"), observation.pickupProvenance, what)
        closeOrNull(observation.lineOffsetMeters, want["line"], "$what line")
        closeOrNull(observation.gpsAccuracyMeters, want["accuracy"], "$what accuracy")
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

    private fun expectVariability(variability: CornerVariability, want: JsonObject, what: String) {
        expectSummary(variability.brakingPointMeasured, want.obj("brakingPointMeasured"), "$what braking measured")
        expectSummary(variability.brakingPointInferred, want.obj("brakingPointInferred"), "$what braking inferred")
        expectSummary(variability.apexSpeed, want.obj("apexSpeed"), "$what apex")
        expectSummary(variability.minimumSpeed, want.obj("minimumSpeed"), "$what minimum")
        expectSummary(variability.exitSpeed, want.obj("exitSpeed"), "$what exit")
        expectSummary(variability.pickupMeasured, want.obj("pickupMeasured"), "$what pickup measured")
        expectSummary(variability.pickupInferred, want.obj("pickupInferred"), "$what pickup inferred")
        expectSummary(variability.lineOffset, want.obj("lineOffset"), "$what line")
        closeOrNull(variability.typicalGpsAccuracyMeters, want["typicalGpsAccuracyMeters"], "$what accuracy")
        assertEquals(want.bool("lineSpreadResolvable"), variability.lineSpreadResolvable, what)
    }

    private fun expectNumbers(actual: List<Double>, want: JsonArray, what: String) {
        assertEquals(want.size, actual.size, what)
        actual.forEachIndexed { i, value -> close(value, want.d(i), "$what $i") }
    }

    private fun strings(value: JsonArray) = value.map { it.jsonPrimitive.content }

    private fun optional(value: JsonElement?): Double? =
        value?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toDouble()

    private fun closeOrNull(actual: Double?, expected: JsonElement?, what: String) {
        if (expected == null || expected is JsonNull) {
            assertNull(actual, what)
        } else {
            assertNotNull(actual, what)
            close(actual, expected.jsonPrimitive.content.toDouble(), what)
        }
    }

    /**
     * Telemetry reads a unit from the VBO's `[header]` line where Overlays reports the parsed
     * channel's own unit, empty for a VBO (FET-288): the reference's empty unit is met by km/h.
     */
    private fun unitAsTelemetryReadsIt(overlays: String, telemetry: String) =
        if (overlays == "" && telemetry == "km/h") telemetry else overlays
}
