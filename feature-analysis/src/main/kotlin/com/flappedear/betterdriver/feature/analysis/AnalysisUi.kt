package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.compare.LapComparison
import com.flappedear.betterdriver.analysis.focus.FocusAreaKind
import com.flappedear.betterdriver.analysis.focus.sessionFocusAreas
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.analysis.laps.LapReferenceIssue
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import kotlin.math.roundToLong

/** What the analysis screen shows while it loads, when it cannot, and when it can. */
sealed interface AnalysisState {
    data object Loading : AnalysisState

    /** Nothing to analyse: [message] says why, [action] (if any) labels the fix the screen offers. */
    data class Unavailable(val message: String, val action: String? = null) : AnalysisState

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

/** A corner or straight where the fastest lap is slower than the best time through it. */
data class GainRow(val name: String, val lossSeconds: Double, val bestLapNumber: Int?)

/** One lap's loss against the fastest lap in one corner or straight. */
data class LossRow(val lapIndex: Int, val lapNumber: Int, val name: String, val lossSeconds: Double)

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
)

/** A corner along the distance axis, shaded behind the charts. */
data class CornerBand(val name: String, val startMeters: Double, val endMeters: Double)

/** The two laps the charts compare. */
data class CompareUi(
    val referenceNumber: Int,
    val otherNumber: Int,
    val lengthMeters: Double,
    val delta: List<List<ChartPoint>>,
    val referenceSpeed: List<List<ChartPoint>>,
    val otherSpeed: List<List<ChartPoint>>,
    val corners: List<CornerBand>,
)

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
    /** This session against the earlier sessions at its track; null while they are read. */
    val progress: SessionProgress? = null,
    /** The compared lap on the map and its G-G diagram; null without GPS on the session's axis. */
    val map: MapUi? = null,
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
        focus = focus(analysis),
        compare = comparison?.let {
            CompareUi(
                referenceNumber = laps[it.referenceIndex].number,
                otherNumber = laps[it.otherIndex].number,
                lengthMeters = it.lengthMeters,
                delta = it.delta,
                referenceSpeed = it.referenceSpeed,
                otherSpeed = it.otherSpeed,
                corners = corners,
            )
        },
    )
}

private fun gains(analysis: SessionAnalysis) = analysis.summary?.gains.orEmpty()
    .filter { (it.lossSeconds ?: 0.0) > MeaningfulLossSeconds }
    .take(LossRows)
    .map { GainRow(it.name, it.lossSeconds ?: 0.0, it.sourceLapReference as? Int) }

private fun losses(analysis: SessionAnalysis): List<LossRow> {
    val indexByNumber = analysis.laps.timedLaps.withIndex().associate { it.value.number to it.index }
    return analysis.losses?.losses.orEmpty()
        .filter { it.lossSeconds > MeaningfulLossSeconds }
        .mapNotNull { loss ->
            val number = loss.lapReference as? Int
            val index = number?.let(indexByNumber::get)
            if (number == null || index == null) null else LossRow(index, number, loss.window.name, loss.lossSeconds)
        }
        .take(LossRows)
}

private fun kindLabel(kind: FocusAreaKind) = when (kind) {
    FocusAreaKind.SectorGap -> "Best lap gap"
    FocusAreaKind.RepeatedLoss -> "Repeated loss"
    FocusAreaKind.BrakingSpread -> "Braking point spread"
    FocusAreaKind.MinimumSpeedSpread -> "Minimum speed spread"
}

/**
 * The session's focus areas. The Compare tab sets one lap against the fastest, so an area's pair
 * opens as whichever of its laps is not the fastest (the first, when neither is).
 */
fun focus(analysis: SessionAnalysis): List<FocusRow> {
    val laps = analysis.laps.timedLaps
    val fastest = analysis.laps.fastestLapIndex?.let { laps[it].number }
    val indexByNumber = laps.withIndex().associate { it.value.number to it.index }
    return sessionFocusAreas(analysis).map { area ->
        val number = listOf(area.lap, area.against).filterIsInstance<Int>().firstOrNull { it != fastest }
        FocusRow(
            kind = kindLabel(area.kind),
            name = area.name,
            observation = area.observation,
            hypothesis = area.hypothesis,
            compareIndex = number?.let(indexByNumber::get),
            compareNumber = number,
        )
    }
}

/** The lap compared with the fastest when the screen opens: the second fastest counted lap, or null. */
fun defaultComparedLap(analysis: SessionAnalysis): Int? {
    val fastest = analysis.laps.fastestLapIndex ?: return null
    val counted = analysis.laps.timedLaps.withIndex().filter { it.value.referenceEligible && it.index != fastest }
    return counted.minByOrNull { it.value.durationSeconds }?.index
}
