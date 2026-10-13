package com.flappedear.betterdriver.analysis.coaching

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.confidence.segmentSeconds
import com.flappedear.betterdriver.analysis.focus.nextSessionPlan
import com.flappedear.betterdriver.analysis.focus.sessionFocus
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.wrapLengthMeters
import com.flappedear.betterdriver.recorder.Confidence
import kotlinx.serialization.Serializable

// The coaching effectiveness history (FBBD-180) keeps, per analysed session at a track, what it
// recommended (the next-session focus, FBBD-20) and what was driven through each corner, under the
// corner's CornerId (FBBD-172). Whether a recommendation was attempted, changed the driving,
// improved the corner, worked repeatedly and persisted is worked out from these records
// ([coachingHistory]), so it always follows the records that are there: none is a stored verdict
// that could go stale. The records hold no positions, only times, distances and speeds per corner.

const val CoachingHistoryAlgorithm = "coaching-history-v1"

/**
 * One counted lap's pass through a corner, as kept for the history. [seconds] is the corner time;
 * [confidence] the lap's in that corner (FBBD-177), `HIGH`, `MEDIUM` or `LOW`: a Low pass is kept
 * but never counted. [brakingMeters] is where braking started, metres from the corner's start
 * (negative before it), found as [brakingProvenance] says (`measured` or `inferred`); placed by the
 * corner's start, which the corner identity puts at the same place in every session, so it compares
 * across sessions. Speeds are in the corner's [CornerRecord.speedUnit].
 */
@Serializable
data class CornerPassRecord(
    val lap: Int,
    val seconds: Double,
    val confidence: String,
    val brakingMeters: Double? = null,
    val brakingProvenance: String = "",
    val minimumSpeed: Double? = null,
    val exitSpeed: Double? = null,
) {
    init {
        require(seconds.isFinite() && seconds > 0) { "corner time not finite" }
        require(Confidence.parse(confidence) != null) { "unknown confidence: $confidence" }
        require(brakingMeters?.isFinite() != false) { "braking point not finite" }
        require(minimumSpeed?.isFinite() != false && exitSpeed?.isFinite() != false) { "speed not finite" }
    }

    val level: Confidence get() = Confidence.parse(confidence) ?: Confidence.LOW
}

/**
 * One corner of an analysed session. [speedMeasured] and [brakeMeasured]: whether the speed and the
 * brake channels were measured (FBBD-174), so a change in them may carry its number.
 * [focusRank] is its place among the session's top three focus areas (1 to 3), null when not among
 * them; [withheld] when its evidence was too weak to rank it at all (Low, FBBD-177). [cues] counts
 * the coach's corner cues there during the session.
 */
@Serializable
data class CornerRecord(
    val cornerId: String,
    val name: String,
    val speedUnit: String = "",
    val speedMeasured: Boolean = false,
    val brakeMeasured: Boolean = false,
    val focusRank: Int? = null,
    val withheld: Boolean = false,
    val cues: Int = 0,
    val passes: List<CornerPassRecord> = emptyList(),
) {
    init {
        require(cornerId.isNotBlank()) { "no corner id" }
        require(cues >= 0) { "negative cue count" }
        require(focusRank == null || focusRank >= 1) { "bad focus rank" }
    }
}

/**
 * One change a recommendation asked for at its corner: [metric] is `brakingPoint`, `minimumSpeed`
 * or `exitSpeed` (FocusMetric codes); [direction] +1 for later braking or more speed, -1 for the other way.
 */
@Serializable
data class AskedChange(val metric: String, val direction: Int) {
    init {
        require(direction == 1 || direction == -1) { "bad direction" }
    }
}

/** One corner a session recommended working on next (FBBD-20): the primary or a secondary focus. */
@Serializable
data class FocusAsk(
    val cornerId: String,
    val name: String,
    /** `primary` or `secondary`. */
    val role: String,
    /** The focus's confidence, `HIGH` or `MEDIUM`. */
    val confidence: String,
    val changes: List<AskedChange> = emptyList(),
) {
    init {
        require(cornerId.isNotBlank()) { "no corner id" }
        require(Confidence.parse(confidence) != null) { "unknown confidence: $confidence" }
    }

    val primary: Boolean get() = role == "primary"
}

/**
 * What one analysed session at a track holds for the coaching history (FBBD-180): its corners,
 * under the CornerIds of [identityRevision] of the track's corner identity, what it recommended
 * working on next ([focus]), and the session whose focus the coach worked on during it
 * ([coachedFrom], from the coach's own event; null when the coach did not).
 */
@Serializable
data class SessionCoachingRecord(
    val format: String = Format,
    val version: Int = Version,
    val trackId: String,
    val sessionId: String,
    val sessionStartUtcMillis: Long,
    val algorithm: String = CoachingHistoryAlgorithm,
    val identityRevision: Int,
    val coachedFrom: String? = null,
    val focus: List<FocusAsk> = emptyList(),
    val corners: List<CornerRecord> = emptyList(),
) {
    init {
        require(identityRevision >= 1) { "bad identity revision" }
        require(corners.map { it.cornerId }.toSet().size == corners.size) { "a corner twice" }
    }

    fun corner(cornerId: String): CornerRecord? = corners.firstOrNull { it.cornerId == cornerId }

    companion object {
        const val Format = "betterdriver-coaching-history"
        const val Version = 1
    }
}

/**
 * What the coach did during a session, from its events: the session whose focus it worked on
 * ([focusFrom]), and how many corner cues it gave at each CornerId ([cues]).
 */
data class CoachedSession(val focusFrom: String? = null, val cues: Map<String, Int> = emptyMap())

/**
 * The coaching history record of session [sessionId] at [trackId], started at [startUtcMillis],
 * from its [analysis]: every corner's counted passes and what it recommended next (its next-session
 * focus, FBBD-20). Null when the analysis's corners are not the track's (no
 * CornerIds to keep them under).
 */
fun sessionCoachingRecord(
    analysis: SessionAnalysis,
    trackId: String,
    sessionId: String,
    startUtcMillis: Long,
    coached: CoachedSession = CoachedSession(),
): SessionCoachingRecord? {
    val revision = analysis.identity?.number?.takeIf { analysis.identified } ?: return null
    val segments = analysis.segments?.segments.orEmpty().filter { it.type == TrackSegmentType.Corner }
    // The session's top three focus areas, as the summary ranks them (FBBD-177).
    val ranked = sessionFocus(analysis).areas.map { it.area.segmentId }.distinct()
    val length = analysis.axis?.let(::wrapLengthMeters) ?: 0.0
    val speedMeasured = analysis.provenance.speed?.measured == true
    val brakeMeasured = analysis.provenance.brake?.measured == true
    val corners = segments.map { corner ->
        val metrics = analysis.cornerMetrics[corner.id].orEmpty()
        val passes = analysis.laps.timedLaps.indices.filter { analysis.laps.timedLaps[it].referenceEligible }
            .mapNotNull { i ->
                val seconds = analysis.segmentSeconds(corner.id, i)?.takeIf { it.isFinite() && it > 0 }
                    ?: return@mapNotNull null
                val o = metrics.getOrNull(i)?.observation
                val braking = o?.brakingPointMeters?.takeIf { it.isFinite() }
                    ?.let { fromStart(it, corner.startProgressMeters, length) }
                CornerPassRecord(
                    lap = analysis.laps.timedLaps[i].number,
                    seconds = seconds,
                    confidence = analysis.confidence.lap(corner.id, i).level.name,
                    brakingMeters = braking,
                    brakingProvenance = if (braking == null) "" else o.brakingProvenance,
                    minimumSpeed = o?.minimumSpeed?.takeIf { it.isFinite() },
                    exitSpeed = o?.exitSpeed?.takeIf { it.isFinite() },
                )
            }
        val units = metrics.map { it.observation.speedUnit }.filter { it.isNotBlank() }.toSet()
        CornerRecord(
            cornerId = corner.id,
            name = corner.name,
            // Speeds in more than one unit are none the history can compare.
            speedUnit = units.singleOrNull().orEmpty(),
            speedMeasured = speedMeasured,
            brakeMeasured = brakeMeasured,
            focusRank = ranked.indexOf(corner.id).takeIf { it >= 0 }?.plus(1),
            withheld = analysis.confidence.segment(corner.id).level == Confidence.LOW,
            cues = coached.cues[corner.id] ?: 0,
            passes = passes,
        )
    }
    val ids = corners.map { it.cornerId }.toSet()
    return SessionCoachingRecord(
        trackId = trackId,
        sessionId = sessionId,
        sessionStartUtcMillis = startUtcMillis,
        identityRevision = revision,
        coachedFrom = coached.focusFrom?.takeIf { it != sessionId },
        focus = nextSessionPlan(analysis).focuses.filter { it.segmentId in ids }.map { f ->
            FocusAsk(
                cornerId = f.segmentId,
                name = f.name,
                role = f.role.code,
                confidence = f.confidence.level.name,
                changes = f.changes.filter { it.delta != 0.0 }
                    .map { AskedChange(it.metric.code, if (it.delta > 0) 1 else -1) },
            )
        },
        corners = corners,
    )
}

/** [meters] along a lap of [length] (0 when not closed) from [start], the nearer way round a closed lap. */
private fun fromStart(meters: Double, start: Double, length: Double): Double {
    var d = meters - start
    if (length > 0) {
        while (d > length / 2) d -= length
        while (d < -length / 2) d += length
    }
    return d
}
