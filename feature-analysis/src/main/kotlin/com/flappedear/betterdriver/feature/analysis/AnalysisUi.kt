package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.AnalysisProvenance
import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.compare.LapComparison
import com.flappedear.betterdriver.analysis.confidence.bestGapConfidence
import com.flappedear.betterdriver.analysis.confidence.lossConfidence
import com.flappedear.betterdriver.analysis.driving.DrivingStateRun
import com.flappedear.betterdriver.analysis.focus.FocusAreaKind
import com.flappedear.betterdriver.analysis.focus.FocusRole
import com.flappedear.betterdriver.analysis.focus.nextSessionPlan
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.analysis.laps.LapReferenceIssue
import com.flappedear.betterdriver.analysis.map.GgTrace
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.Provenance
import kotlin.math.roundToLong

/** What the analysis screen shows while it loads, when it cannot, and when it can. */
sealed interface AnalysisState {
    data object Loading : AnalysisState

    /**
     * Nothing to analyse: [message] says why, [action] (if any) labels the fix the screen offers.
     * [acceleration] still shows the session's acceleration runs, which need no laps.
     * [quality] is the session's data quality (FBBD-183), null while it is worked out.
     * [gForces] is the whole session's G-G diagram, which needs no laps either; null without movement.
     * [car] is the car's values and the heart rate of the whole drive, which need no laps either.
     */
    data class Unavailable(
        val message: String,
        val action: String? = null,
        val acceleration: AccelerationUi? = null,
        val quality: QualityUi? = null,
        val gForces: GgTrace? = null,
        /** The car's values and the heart rate over the whole drive (UI-162 A); null without any. */
        val car: CarUi? = null,
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
    /** The lap's heart rate (FBBD-225); null when none was recorded during it. */
    val heartRate: LapHeartRate? = null,
)

/** A lap's average and highest heart rate, beats per minute (UI-107 A). */
data class LapHeartRate(val averageBpm: Int, val maxBpm: Int)

/**
 * The session's heart rate (FBBD-225, UI-107 A): average, highest and lowest, from the strap named
 * [device] (null when the session does not name it), over [minutes] of heart rate. Only a session
 * that recorded heart rate has one (UI-109).
 */
data class HeartRateUi(val device: String?, val averageBpm: Int, val maxBpm: Int, val minBpm: Int, val minutes: Int)

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
 * One corner to work on next session (FBBD-20): the [primary] one or a secondary one. [timeSeconds]
 * is what a typical pass there leaves against what the driver has done on two laps; [what] says how
 * the faster passes differed (braking point, minimum speed, exit speed), empty when nothing stood out.
 * The measured [observation] is kept apart from the [hypothesis] of what may be worth comparing.
 * [compareIndex] is the lap of the pair to compare with the fastest, or null when both are the fastest.
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
    val primary: Boolean = false,
    val timeSeconds: Double? = null,
    val what: String = "",
    /** How far [timeSeconds] can be trusted: [confidence], or lower when a pass it rests on is. */
    val timeConfidence: Confidence = confidence.level,
) {
    /** The time available as shown: no number below High confidence; null when unknown. */
    fun timeText(format: (Double) -> String): String? =
        timeSeconds?.let { timeText(it, ConfidenceUi(timeConfidence), format) }

    /** Whether the time available is shown below High, in the dim colour. */
    val timeDoubtful: Boolean get() = timeConfidence != Confidence.HIGH
}

/**
 * A corner or straight left out of the focus areas (FBBD-177): what it showed rests on too little
 * ([why]: one lap, or positions too weak to place there).
 */
data class WithheldRow(val name: String, val why: String)

/** A corner along the distance axis, shaded behind the charts. */
data class CornerBand(val name: String, val startMeters: Double, val endMeters: Double)

/**
 * The two laps the charts compare. The time delta is worked out by distance ([DeltaProvenance]);
 * the speeds are the speed channel's, of [speedProvenance] (FBBD-174). Under them, what each lap's
 * car was doing along the lap (FBBD-175): braking, coasting, cornering, accelerating or flat out,
 * and how long each lap coasted, of [statesProvenance]; empty without driving states.
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
    val referenceStates: List<DrivingStateRun> = emptyList(),
    val otherStates: List<DrivingStateRun> = emptyList(),
    val referenceCoastingSeconds: Double? = null,
    val otherCoastingSeconds: Double? = null,
    val statesProvenance: Provenance? = null,
) {
    /** Whether both laps have driving states to draw. */
    val hasStates: Boolean get() = referenceStates.isNotEmpty() && otherStates.isNotEmpty()
}

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
    /** The raw theoretical best (FBBD-178): each segment's fastest time, added up. */
    val theoreticalMillis: Long?,
    /** Fastest lap minus raw theoretical best, seconds. */
    val gainSeconds: Double?,
    /**
     * How far [gainSeconds] can be trusted (FBBD-177): the weakest of the corners and straights it
     * adds up; below High it is shown without its number.
     */
    val gainConfidence: ConfidenceUi = ConfidenceUi.High,
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
    /** What to work on next session (FBBD-20): the primary corner first, then at most two secondary ones. */
    val focus: List<FocusRow> = emptyList(),
    /** Corners and straights not among [focus] because their evidence is too weak (FBBD-177). */
    val withheld: List<WithheldRow> = emptyList(),
    /** This session against the earlier sessions at its track; null while they are read. */
    val progress: SessionProgress? = null,
    /** The compared lap on the map and its G-G diagram; null without GPS on the session's axis. */
    val map: MapUi? = null,
    /** Corners (segment ids) that can be picked for the coach's next session (FBBD-148); none while read. */
    val coachPickable: Set<String> = emptySet(),
    /**
     * The realistic optimal (FBBD-178): the fastest lap the counted laps combine into where
     * neighbouring segments join compatibly; null without a raw theoretical best.
     */
    val realisticMillis: Long? = null,
    /** Fastest lap minus realistic optimal, seconds; shown at [gainConfidence] like [gainSeconds]. */
    val realisticGainSeconds: Double? = null,
    /** The laps the realistic optimal is made of. */
    val realisticLaps: List<Int> = emptyList(),
    /** Of [coachPickable], the corners picked at this track, in this session or another. */
    val coachPicked: Set<String> = emptySet(),
    /**
     * Whether the spoken coach will work on the next-session focus first at this track (FBBD-20): the
     * coach runs there (not point to point) and speaks about three corners only (verbosity Focus).
     */
    val coachFocusFirst: Boolean = false,
    /**
     * The coaching history (FBBD-180): what came of the focus this session worked on, and whether
     * earlier gains held in it; empty when the history has nothing for it.
     */
    val coaching: List<CoachingRow> = emptyList(),
    /** The session's acceleration runs; null when it has none. */
    val acceleration: AccelerationUi? = null,
    /** The session's data quality (FBBD-183); null while it is worked out, or when it cannot be. */
    val quality: QualityUi? = null,
    /** Where the channels the analysis read come from (FBBD-174). */
    val sources: AnalysisProvenance = AnalysisProvenance(),
    /** The whole session's G-G diagram; null without movement. */
    val gForces: GgTrace? = null,
    /** The session's heart rate; null when it recorded none. */
    val heartRate: HeartRateUi? = null,
    /** The Car tab (FBBD-176): pedals, temperatures and heart rate; null until worked out. */
    val car: CarUi? = null,
    /** Where to find time (FBBD-306), in the Map tab: read from every session at the track after the rest. */
    val line: LineState = LineState.Reading,
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
    // Shown only beside the raw theoretical best it can never beat.
    val realistic = analysis.realisticOptimal?.takeIf { total != null }
    val sectorsNote = when {
        analysis.segments == null && analysis.consensus.disagrees ->
            "No corners: the best lap's GPS line is far from the other laps somewhere."
        analysis.segments == null -> "No corners: this lap could not be split into corners and straights."
        total == null -> "No raw theoretical best: a sector has no fully covered time on any counted lap."
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
        gainConfidence = totalGainConfidence(analysis),
        sectors = sectors,
        sectorsNote = sectorsNote,
        gains = gains(analysis),
        losses = losses(analysis),
        corners = cornerDetails(analysis, fastestIndex, comparison?.otherIndex),
        focus = next.focus,
        withheld = next.withheld,
        compare = comparison?.let { compareUi(analysis, it, corners) },
        sources = analysis.provenance,
        realisticMillis = realistic?.totalSeconds?.let(::millis),
        realisticGainSeconds = realistic?.let { fastest.durationSeconds - it.totalSeconds },
        realisticLaps = realistic?.laps.orEmpty(),
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

/** What to work on next session ([focus], the primary first) and what was left out for weak evidence. */
class NextSession(val focus: List<FocusRow>, val withheld: List<WithheldRow>)

/**
 * What to work on next session (FBBD-20): one primary corner and at most two secondary ones, each
 * with its confidence (FBBD-177), its time available and how its faster passes differed, and the
 * corners left out for weak evidence. The Compare tab sets one lap against the fastest, so a focus's
 * pair opens as whichever of its laps is not the fastest (the first, when neither is).
 */
fun nextSession(analysis: SessionAnalysis): NextSession {
    val laps = analysis.laps.timedLaps
    val fastest = analysis.laps.fastestLapIndex?.let { laps[it].number }
    val indexByNumber = laps.withIndex().associate { it.value.number to it.index }
    val corners = cornerIds(analysis)
    val plan = nextSessionPlan(analysis)
    val focus = plan.focuses.map { f ->
        val area = f.area
        val number = listOf(area.lap, area.against).filterIsInstance<Int>().firstOrNull { it != fastest }
        FocusRow(
            kind = kindLabel(area.kind),
            name = area.name,
            observation = area.observation,
            hypothesis = area.hypothesis,
            compareIndex = number?.let(indexByNumber::get),
            compareNumber = number,
            cornerId = area.segmentId.takeIf(corners::contains),
            confidence = f.confidence.ui(),
            primary = f.role == FocusRole.Primary,
            timeSeconds = f.timeAvailableSeconds,
            what = f.what,
            timeConfidence = f.timeConfidence,
        )
    }
    return NextSession(focus, plan.withheld.map { WithheldRow(it.name, it.confidence.why) })
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
