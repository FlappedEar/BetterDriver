package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.corners.CornerPass
import com.flappedear.betterdriver.analysis.corners.CornerTraces
import com.flappedear.betterdriver.analysis.corners.cornerPasses
import com.flappedear.betterdriver.recorder.Provenance
import java.util.Locale

/** A corner pass on video, for the inline player: the file at [path] from [startMillis] to [endMillis]. */
data class CornerVideoUi(val path: String, val startMillis: Long, val endMillis: Long)

/** One counted lap through the corner (FBBD-153). */
data class CornerLapRow(
    /** In timed-lap order, as [AnalysisScreen]'s onCompare takes it. */
    val lapIndex: Int,
    val lapNumber: Int,
    /** Time through the corner; null where the lap's GPS does not cover all of it. */
    val seconds: Double?,
    /** To the fastest pass; null for a pass without a time. */
    val deltaSeconds: Double?,
    val best: Boolean,
    val brakingMeters: Double?,
    /** The braking point is inferred from deceleration (written ≈). */
    val brakingInferred: Boolean,
    val minimumSpeed: Double?,
    /** Null when the lap has no video of the corner. */
    val video: CornerVideoUi?,
    /** The lap can be opened in the lap review, to compare. */
    val reviewable: Boolean,
    /**
     * How far this pass's numbers can be trusted (FBBD-177). Below High the row shows no time,
     * braking point or speed, only the pass itself, its trace and its video.
     */
    val confidence: ConfidenceUi = ConfidenceUi.High,
    /** The pass of the session's best actual lap. */
    val bestLap: Boolean = false,
    /** The pass the realistic optimal lap (FBBD-178) takes through this corner. */
    val optimal: Boolean = false,
)

/** Every counted lap through one corner, fastest first, for the Corner laps screen. */
data class CornerLapsUi(
    val cornerId: String,
    val name: String,
    val speedUnit: String,
    val rows: List<CornerLapRow>,
    /** Where the minimum speeds come from (FBBD-174); null when not known. */
    val speedProvenance: Provenance? = null,
    /** Speed, brake and throttle of every pass over the corner (FBBD-93); null when they cannot be read. */
    val traces: CornerTraces? = null,
    /** The corner is not one of this session's corners: the track's corners were changed since. */
    val notInSession: Boolean = false,
) {
    val anyVideo: Boolean get() = rows.any { it.video != null }

    /** Some value is shown with [NotMeasuredMark]: an inferred braking point, or a speed not measured. */
    val anyMarked: Boolean
        get() = rows.any { it.brakingInferred && it.brakingMeters != null } ||
            (speedProvenance.notMeasured && rows.any { it.minimumSpeed != null })

    fun row(lapIndex: Int): CornerLapRow? = rows.firstOrNull { it.lapIndex == lapIndex }
}

/**
 * What stands out among a corner's passes: the best actual lap's, the realistic optimal's, and how
 * far each pass's numbers can be trusted.
 */
class PassMarks(
    val bestLapIndex: Int? = null,
    val optimalLapIndex: Int? = null,
    val confidence: (CornerPass) -> ConfidenceUi = { ConfidenceUi.High },
)

/**
 * The screen model of corner [cornerId] ([name]) from its [passes] (fastest first, as
 * [com.flappedear.betterdriver.analysis.corners.cornerPasses] lists them), with each pass's video
 * ([video]) and whether the lap review has its lap ([reviewable]). [speedProvenance] is where the
 * session's speed channel comes from.
 */
@Suppress("LongParameterList") // The corner, its passes and what is known about each.
fun cornerLapsUi(
    cornerId: String,
    name: String,
    passes: List<CornerPass>,
    video: (CornerPass) -> CornerVideoUi?,
    reviewable: (CornerPass) -> Boolean,
    speedProvenance: Provenance? = null,
    marks: PassMarks = PassMarks(),
    traces: CornerTraces? = null,
): CornerLapsUi {
    val graded = passes.associate { it.lapIndex to marks.confidence(it) }
    fun precise(p: CornerPass) = graded[p.lapIndex]?.doubtful != true
    // Only a pass whose numbers can be trusted is the fastest, or measured against (FBBD-177).
    val fastest = passes.filter(::precise).mapNotNull { it.seconds }.minOrNull()
    val rows = passes.map { p ->
        val seconds = p.seconds?.takeIf { precise(p) }
        CornerLapRow(
            lapIndex = p.lapIndex,
            lapNumber = p.lapNumber,
            seconds = seconds,
            deltaSeconds = if (seconds != null && fastest != null) seconds - fastest else null,
            best = seconds != null && seconds == fastest,
            brakingMeters = p.brakingBeforeEntryMeters,
            brakingInferred = p.brakingInferred,
            minimumSpeed = p.minimumSpeed,
            video = video(p),
            reviewable = reviewable(p),
            confidence = graded.getValue(p.lapIndex),
            bestLap = p.lapIndex == marks.bestLapIndex,
            optimal = p.lapIndex == marks.optimalLapIndex,
        )
    }
    val unit = passes.firstOrNull { it.speedUnit.isNotEmpty() }?.speedUnit.orEmpty()
    return CornerLapsUi(cornerId, name, unit, rows, speedProvenance, traces)
}

/**
 * Corner [cornerId] of [analysis] as the Corner laps screen shows it (FBBD-93): every counted pass
 * with its confidence, the best actual lap's and the realistic optimal's passes marked, and the
 * [traces] of the passes. A corner the analysis does not have (the track's corners were changed
 * since the CornerId was taken) is [CornerLapsUi.notInSession].
 */
fun cornerLapsUi(
    analysis: SessionAnalysis,
    cornerId: String,
    video: (CornerPass) -> CornerVideoUi?,
    reviewable: (CornerPass) -> Boolean,
    traces: (List<Int>) -> CornerTraces? = { null },
): CornerLapsUi {
    val corner = analysis.segments?.segments?.firstOrNull { it.id == cornerId }
    val passes = cornerPasses(analysis, cornerId)
    val laps = analysis.laps.timedLaps
    val optimalNumber = analysis.realisticOptimal?.sectors?.firstOrNull { it.segmentId == cornerId }?.lapNumber
    val marks = PassMarks(
        bestLapIndex = analysis.laps.fastestLapIndex,
        optimalLapIndex = optimalNumber?.let { n -> laps.indexOfFirst { it.number == n }.takeIf { it >= 0 } },
        confidence = { analysis.confidence.lap(cornerId, it.lapIndex).ui() },
    )
    val name = corner?.name ?: "Corner"
    val shown = traces(passes.map { it.lapIndex })
    return cornerLapsUi(cornerId, name, passes, video, reviewable, analysis.provenance.speed, marks, shown)
        .copy(notInSession = corner == null)
}

/** The passes the traces draw strongly, lap A then lap B, as [highlightedPasses] picks them. */
data class Highlight(val a: Int?, val b: Int?)

/**
 * Which passes the traces draw strongly: the two [ticked] (the faster first, as the lap review gets
 * them), or the one ticked; with none ticked, the fastest pass through the corner and the best
 * actual lap's pass, so the traces say something before anything is tapped.
 */
fun highlightedPasses(ui: CornerLapsUi, ticked: List<Int>): Highlight {
    val order = ui.rows.map { it.lapIndex }
    val picked = order.filter { it in ticked }
    if (picked.isNotEmpty()) return Highlight(picked[0], picked.getOrNull(1))
    val fastest = ui.rows.firstOrNull { it.best }?.lapIndex
    val bestLap = ui.rows.firstOrNull { it.bestLap }?.lapIndex?.takeIf { it != fastest }
    return if (fastest == null) Highlight(bestLap, null) else Highlight(fastest, bestLap)
}

/** At most this many laps are ticked: the lap review compares two. */
internal const val ComparedLaps = 2

/**
 * The ticked laps after [lapIndex] is tapped: untick it, or tick it; a third tick drops the first
 * ticked, so the latest two stay.
 */
fun toggleTicked(ticked: List<Int>, lapIndex: Int): List<Int> =
    if (lapIndex in ticked) ticked - lapIndex else (ticked + lapIndex).takeLast(ComparedLaps)

/**
 * The two ticked laps for the lap review: the faster through the corner first (lap A), in the
 * order of [ui]'s rows; null unless two are ticked.
 */
fun comparedPair(ui: CornerLapsUi, ticked: List<Int>): Pair<Int, Int>? {
    val laps = ui.rows.map { it.lapIndex }.filter { it in ticked }
    return if (laps.size == ComparedLaps) laps[0] to laps[1] else null
}

/** Ticked laps as saved state: "3,5". */
internal fun encodeTicked(ticked: List<Int>) = ticked.joinToString(",")

internal fun decodeTicked(text: String): List<Int> = text.split(',').mapNotNull { it.toIntOrNull() }

/** The braking point of [row], metres before the corner: "≈42 m" when inferred, "–" without one. */
internal fun brakingText(row: CornerLapRow): String = marked(
    formatMeasure(row.brakingMeters, CornerMeasure.Meters, ""),
    if (row.brakingInferred) Provenance.INFERRED else null,
)

/** The minimum speed of [row]: "82 km/h", "≈82 km/h" from a speed that is not measured, "–" without one. */
internal fun minimumText(row: CornerLapRow, unit: String, provenance: Provenance? = null): String =
    marked(
        row.minimumSpeed?.let {
            val speed = String.format(Locale.ROOT, "%.0f", it)
            if (unit.isEmpty()) speed else "$speed $unit"
        } ?: Missing,
        provenance,
    )
