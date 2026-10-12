package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.AnalysisProvenance
import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.compare.LapComparison
import com.flappedear.betterdriver.analysis.confidence.bestGapConfidence
import com.flappedear.betterdriver.analysis.confidence.lossConfidence
import com.flappedear.betterdriver.analysis.focus.FocusAreaKind
import com.flappedear.betterdriver.analysis.focus.sessionFocus
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.analysis.laps.LapReferenceIssue
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.recorder.Provenance
import kotlin.math.roundToLong

/** What the analysis screen shows while it loads, when it cannot, and when it can. */
sealed interface AnalysisState {
    data object Loading : AnalysisState

    /**
     * Nothing to analyse: [message] says why, [action] (if any) labels the fix the screen offers.
     * [acceleration] still shows the session's acceleration runs, which need no laps.
     * [quality] is the session's data quality (FBBD-183), null while it is worked out.
     */
    data class Unavailable(
        val message: String,
        val action: String? = null,
        val acceleration: AccelerationUi? = null,
        val quality: QualityUi? = null,
    ) : AnalysisState

    data class Ready(val ui: AnalysisUi) : AnalysisState
}

/** One timed lap in the lap table. */
data class LapRow(
    val index: Int,
    val number: Int,
    val millis: Long,
    /** To the fastest eligible lap; null for a lap that is not counted. */
    val deltaSeconds: Double?,
    val best: Boolean,
    /** Why the lap is not counted, or empty. */
    val note: String,
)

/** One corner or straight of the theoretical best, with the compared lap's time through it. */
data class SectorRow(
    val name: String,
    val corner: Boolean,
    val bestSeconds: Double?,
    val bestLapNumber: Int?,
    val lapSeconds: Double?,
    /** The compared lap's time minus the best; null when either is missing. */
    val lossSeconds: Double?,
    /** Interquartile range of the counted laps' times here; null with fewer than three. */
    val spreadSeconds: Double? = null,
)

/**
 * A corner or straight where the fastest lap is slower than the best time through it. [cornerId] is
 * the segment id of a corner, null for a straight. [confidence]: how far the time to gain there can
 * be trusted (FBBD-177).
 */
data class GainRow(
    val name: String,
    val lossSeconds: Double,
    val bestLapNumber: Int?,
    val cornerId: String? = null,
    val confidence: ConfidenceUi = ConfidenceUi.High,
) {
    /** The time to gain as shown: no number below High confidence. */
    fun lossText(format: (Double) -> String): String = timeText(lossSeconds, confidence, format)
}

/** One lap's loss against the fastest lap in one corner or straight; [cornerId] and [confidence] as in [GainRow]. */
data class LossRow(
    val lapIndex: Int,
    val lapNumber: Int,
    val name: String,
    val lossSeconds: Double,
    val cornerId: String? = null,
    val confidence: ConfidenceUi = ConfidenceUi.High,
) {
    /** The loss as shown: no number below High confidence. */
    fun lossText(format: (Double) -> String): String = timeText(lossSeconds, confidence, format)
}

/**
 * One area to look at before the next session: the measured [observation], kept apart from the
 * [hypothesis] of what may be worth comparing. [compareIndex] is the lap of the pair to compare
 * with the fastest, or null when both are the fastest.
 */
data class FocusRow(
    val kind: String,
    val name: String,
    val observation: String,
    val hypothesis: String,
    val compareIndex: Int?,
    val compareNumber: Int?,
    /** The segment id when the area is a corner, null for a straight. */
    val cornerId: String? = null,
    /** How far the observation can be trusted (FBBD-177); below High it is worded without numbers. */
    val confidence: ConfidenceUi = ConfidenceUi.High,
)

/**
 * A corner or straight left out of the focus areas (FBBD-177): what it showed rests on too little
 * ([why]: one lap, or positions too weak to place there).
 */
data class WithheldRow(val name: String, val why: String)

/** A corner along the distance axis, shaded behind the charts. */
data class CornerBand(val name: String, val startMeters: Double, val endMeters: Double)

/**
 * The two laps the charts compare. The time delta is worked out by distance ([DeltaProvenance]);
 * the speeds are the speed channel's, of [speedProvenance] (FBBD-174).
 */
data class CompareUi(
    val referenceNumber: Int,
    val otherNumber: Int,
    val lengthMeters: Double,
    val delta: List<List<ChartPoint>>,
    val referenceSpeed: List<List<ChartPoint>>,
    val otherSpeed: List<List<ChartPoint>>,
    val corners: List<CornerBand>,
    val speedProvenance: Provenance? = null,
)

/** The theoretical best and the time to gain to it: put together from the best of each corner. */
val TheoreticalProvenance = Provenance.CALCULATED

/** The time delta between two laps: their times compared by distance along the lap. */
val DeltaProvenance = Provenance.CALCULATED

/** Everything the analysis screen draws for one session. */
data class AnalysisUi(
    val title: String,
    val laps: List<LapRow>,
    val bestLapNumber: Int,
    val bestLapMillis: Long,
    val theoreticalMillis: Long?,
    /** Fastest lap minus theoretical best, seconds. */
    val gainSeconds: Double?,
    val sectors: List<SectorRow>,
    /** Why there are no sectors, or null. */
    val sectorsNote: String?,
    val compare: CompareUi?,
    /** Where the fastest lap is slowest against the best of each segment, largest first. */
    val gains: List<GainRow> = emptyList(),
    /** The largest single losses of the other counted laps against the fastest, largest first. */
    val losses: List<LossRow> = emptyList(),
    /** Braking, speeds and pickup of each corner on the fastest and the compared lap. */
    val corners: List<CornerDetail> = emptyList(),
    /** Up to three areas to look at before the next session. */
    val focus: List<FocusRow> = emptyList(),
    /** Corners and straights not among [focus] because their evidence is too weak (FBBD-177). */
    val withheld: List<WithheldRow> = emptyList(),
    /** This session against the earlier sessions at its track; null while they are read. */
    val progress: SessionProgress? = null,
    /** The compared lap on the map and its G-G diagram; null without GPS on the session's axis. */
    val map: MapUi? = null,
    /** Corners (segment ids) that can be picked for the coach's next session (FBBD-148); none while read. */
    val coachPickable: Set<String> = emptySet(),
    /** Of [coachPickable], the corners picked at this track, in this session or another. */
    val coachPicked: Set<String> = emptySet(),
    /** The session's acceleration runs; null when it has none. */
    val acceleration: AccelerationUi? = null,
    /** The session's data quality (FBBD-183); null while it is worked out, or when it cannot be. */
    val quality: QualityUi? = null,
    /** Where the channels the analysis read come from (FBBD-174). */
    val sources: AnalysisProvenance = AnalysisProvenance(),
)

/** Losses below this are GPS timing noise and not listed. */
const val MeaningfulLossSeconds = 0.05

/** At most this many rows in each loss list. */
const val LossRows = 5

private const val MillisPerSecond = 1000.0

private fun millis(seconds: Double): Long = (seconds * MillisPerSecond).roundToLong()

private fun note(issue: LapReferenceIssue, userReason: String): String = when {
    userReason.isNotEmpty() -> userReason
    issue == LapReferenceIssue.GpsGap -> "GPS gap"
    issue == LapReferenceIssue.InvalidGps -> "Bad GPS"
    issue == LapReferenceIssue.ImplausibleLap -> "Not a full lap"
    else -> ""
}

/**
 * The screen model of [analysis], with [comparison] (lap [LapComparison.otherIndex] against the
 * fastest) for the charts and the sector column. Null when the session has no counted lap.
 */
@Suppress("LongMethod")
fun analysisUi(title: String, analysis: SessionAnalysis, comparison: LapComparison?): AnalysisUi? {
    val laps = analysis.laps.timedLaps
    val fastestIndex = analysis.laps.fastestLapIndex ?: return null
    val fastest = laps[fastestIndex]
    val rows = laps.mapIndexed { i, lap ->
        LapRow(
            index = i,
            number = lap.number,
            millis = millis(lap.durationSeconds),
            deltaSeconds = if (lap.referenceEligible) lap.durationSeconds - fastest.durationSeconds else null,
            best = i == fastestIndex,
            note = note(lap.referenceIssue, lap.userExclusionReason),
        )
    }
    val best = analysis.theoreticalBest
    val compared = comparison?.let { analysis.lapTimes.getOrNull(it.otherIndex) }
    val summary = analysis.summary
    val sectors = best?.sectors.orEmpty().map { sector ->
        val lapSeconds = compared?.sector(sector.segmentId)?.seconds
        SectorRow(
            name = sector.name,
            corner = sector.type == TrackSegmentType.Corner,
            bestSeconds = sector.seconds,
            bestLapNumber = sector.sourceLapReference as? Int,
            lapSeconds = lapSeconds,
            lossSeconds = if (lapSeconds != null && sector.seconds != null) lapSeconds - sector.seconds!! else null,
            spreadSeconds = summary?.sectors?.firstOrNull { it.segmentId == sector.segmentId }
                ?.consistency?.interquartileRange,
        )
    }
    val total = best?.totalSeconds
    val sectorsNote = when {
        analysis.segments == null && analysis.consensus.disagrees ->
            "No corners: the best lap's GPS line is far from the other laps somewhere."
        analysis.segments == null -> "No corners: this lap could not be split into corners and straights."
        total == null -> "No theoretical best: a sector has no fully covered time on any counted lap."
        else -> null
    }
    val corners = analysis.segments?.segments.orEmpty()
        .filter { it.type == TrackSegmentType.Corner }
        .map { CornerBand(it.name, it.startProgressMeters, it.endProgressMeters) }
    val next = nextSession(analysis)
    return AnalysisUi(
        title = title,
        laps = rows,
        bestLapNumber = fastest.number,
        bestLapMillis = millis(fastest.durationSeconds),
        theoreticalMillis = total?.let(::millis),
        gainSeconds = total?.let { fastest.durationSeconds - it },
        sectors = sectors,
        sectorsNote = sectorsNote,
        gains = gains(analysis),
        losses = losses(analysis),
        corners = cornerDetails(analysis, fastestIndex, comparison?.otherIndex),
        focus = next.focus,
        withheld = next.withheld,
        compare = comparison?.let {
            CompareUi(
                referenceNumber = laps[it.referenceIndex].number,
                otherNumber = laps[it.otherIndex].number,
                lengthMeters = it.lengthMeters,
                delta = it.delta,
                referenceSpeed = it.referenceSpeed,
                otherSpeed = it.otherSpeed,
                corners = corners,
                speedProvenance = analysis.provenance.speed,
            )
        },
        sources = analysis.provenance,
    )
}

/** Segment ids of the session's corners. */
private fun cornerIds(analysis: SessionAnalysis): Set<String> =
    analysis.segments?.segments.orEmpty().filter { it.type == TrackSegmentType.Corner }.map { it.id }.toSet()

private fun gains(analysis: SessionAnalysis): List<GainRow> {
    val corners = cornerIds(analysis)
    return analysis.summary?.gains.orEmpty()
        .filter { (it.lossSeconds ?: 0.0) > MeaningfulLossSeconds }
        .take(LossRows)
        .map {
            val corner = it.segmentId.takeIf(corners::contains)
            val loss = it.lossSeconds ?: 0.0
            val confidence = analysis.bestGapConfidence(it.segmentId, loss, it.sourceLapReference).ui()
            GainRow(it.name, loss, it.sourceLapReference as? Int, corner, confidence)
        }
}

private fun losses(analysis: SessionAnalysis): List<LossRow> {
    val indexByNumber = analysis.laps.timedLaps.withIndex().associate { it.value.number to it.index }
    val corners = cornerIds(analysis)
    return analysis.losses?.losses.orEmpty()
        .filter { it.lossSeconds > MeaningfulLossSeconds }
        .mapNotNull { loss ->
            val number = loss.lapReference as? Int
            val index = number?.let(indexByNumber::get)
            val id = loss.window.segmentId
            if (number == null || index == null) {
                null
            } else {
                val confidence = analysis.lossConfidence(id, index, loss.lossSeconds).ui()
                LossRow(index, number, loss.window.name, loss.lossSeconds, id.takeIf(corners::contains), confidence)
            }
        }
        .take(LossRows)
}

private fun kindLabel(kind: FocusAreaKind) = when (kind) {
    FocusAreaKind.SectorGap -> "Best lap gap"
    FocusAreaKind.RepeatedLoss -> "Repeated loss"
    FocusAreaKind.BrakingSpread -> "Braking point spread"
    FocusAreaKind.MinimumSpeedSpread -> "Minimum speed spread"
}

/** The session's focus areas ([focus]) and what was left out of them for weak evidence. */
class NextSession(val focus: List<FocusRow>, val withheld: List<WithheldRow>)

/**
 * The session's focus areas, each with its confidence (FBBD-177), and the corners left out of them.
 * The Compare tab sets one lap against the fastest, so an area's pair opens as whichever of its laps
 * is not the fastest (the first, when neither is).
 */
fun nextSession(analysis: SessionAnalysis): NextSession {
    val laps = analysis.laps.timedLaps
    val fastest = analysis.laps.fastestLapIndex?.let { laps[it].number }
    val indexByNumber = laps.withIndex().associate { it.value.number to it.index }
    val corners = cornerIds(analysis)
    val found = sessionFocus(analysis)
    val focus = found.areas.map { (area, confidence) ->
        val number = listOf(area.lap, area.against).filterIsInstance<Int>().firstOrNull { it != fastest }
        FocusRow(
            kind = kindLabel(area.kind),
            name = area.name,
            observation = area.observation,
            hypothesis = area.hypothesis,
            compareIndex = number?.let(indexByNumber::get),
            compareNumber = number,
            cornerId = area.segmentId.takeIf(corners::contains),
            confidence = confidence.ui(),
        )
    }
    return NextSession(focus, found.withheld.map { WithheldRow(it.name, it.confidence.why) })
}

/**
 * The rows of Where time goes (by index in [AnalysisUi.gains] and [AnalysisUi.losses]) that carry a
 * corner's coach toggle (FBBD-148): the first row each pickable corner appears in, so each has one.
 */
internal class CornerRows(val gains: Set<Int>, val losses: Set<Int>) {
    val any: Boolean get() = gains.isNotEmpty() || losses.isNotEmpty()
}

internal fun firstCornerRows(ui: AnalysisUi): CornerRows {
    val seen = mutableSetOf<String>()
    fun first(id: String?) = id != null && id in ui.coachPickable && seen.add(id)
    val gains = ui.gains.indices.filter { first(ui.gains[it].cornerId) }.toSet()
    val losses = ui.losses.indices.filter { first(ui.losses[it].cornerId) }.toSet()
    return CornerRows(gains, losses)
}

/** The lap compared with the fastest when the screen opens: the second fastest counted lap, or null. */
fun defaultComparedLap(analysis: SessionAnalysis): Int? {
    val fastest = analysis.laps.fastestLapIndex ?: return null
    val counted = analysis.laps.timedLaps.withIndex().filter { it.value.referenceEligible && it.index != fastest }
    return counted.minByOrNull { it.value.durationSeconds }?.index
}
