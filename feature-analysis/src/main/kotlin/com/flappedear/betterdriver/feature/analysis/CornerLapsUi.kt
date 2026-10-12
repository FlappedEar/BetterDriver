package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.corners.CornerPass
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
)

/** Every counted lap through one corner, fastest first, for the Corner laps screen. */
data class CornerLapsUi(
    val cornerId: String,
    val name: String,
    val speedUnit: String,
    val rows: List<CornerLapRow>,
    /** Where the minimum speeds come from (FBBD-174); null when not known. */
    val speedProvenance: Provenance? = null,
) {
    val anyVideo: Boolean get() = rows.any { it.video != null }

    /** Some value is shown with [NotMeasuredMark]: an inferred braking point, or a speed not measured. */
    val anyMarked: Boolean
        get() = rows.any { it.brakingInferred && it.brakingMeters != null } ||
            (speedProvenance.notMeasured && rows.any { it.minimumSpeed != null })

    fun row(lapIndex: Int): CornerLapRow? = rows.firstOrNull { it.lapIndex == lapIndex }
}

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
): CornerLapsUi {
    val fastest = passes.mapNotNull { it.seconds }.minOrNull()
    val rows = passes.map { p ->
        val seconds = p.seconds
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
        )
    }
    val unit = passes.firstOrNull { it.speedUnit.isNotEmpty() }?.speedUnit.orEmpty()
    return CornerLapsUi(cornerId, name, unit, rows, speedProvenance)
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
