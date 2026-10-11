package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.compare.LapComparison
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
)

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
    val sectors = best?.sectors.orEmpty().map { sector ->
        val lapSeconds = compared?.sector(sector.segmentId)?.seconds
        SectorRow(
            name = sector.name,
            corner = sector.type == TrackSegmentType.Corner,
            bestSeconds = sector.seconds,
            bestLapNumber = sector.sourceLapReference as? Int,
            lapSeconds = lapSeconds,
            lossSeconds = if (lapSeconds != null && sector.seconds != null) lapSeconds - sector.seconds!! else null,
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

/** The lap compared with the fastest when the screen opens: the second fastest counted lap, or null. */
fun defaultComparedLap(analysis: SessionAnalysis): Int? {
    val fastest = analysis.laps.fastestLapIndex ?: return null
    val counted = analysis.laps.timedLaps.withIndex().filter { it.value.referenceEligible && it.index != fastest }
    return counted.minByOrNull { it.value.durationSeconds }?.index
}
