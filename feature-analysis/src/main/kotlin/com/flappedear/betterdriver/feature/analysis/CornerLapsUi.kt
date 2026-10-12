package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.corners.CornerPass
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
) {
    val anyVideo: Boolean get() = rows.any { it.video != null }

    fun row(lapIndex: Int): CornerLapRow? = rows.firstOrNull { it.lapIndex == lapIndex }
}

/**
 * The screen model of corner [cornerId] ([name]) from its [passes] (fastest first, as
 * [com.flappedear.betterdriver.analysis.corners.cornerPasses] lists them), with each pass's video
 * ([video]) and whether the lap review has its lap ([reviewable]).
 */
fun cornerLapsUi(
    cornerId: String,
    name: String,
    passes: List<CornerPass>,
    video: (CornerPass) -> CornerVideoUi?,
    reviewable: (CornerPass) -> Boolean,
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
    return CornerLapsUi(cornerId, name, passes.firstOrNull { it.speedUnit.isNotEmpty() }?.speedUnit.orEmpty(), rows)
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
internal fun brakingText(row: CornerLapRow): String = row.brakingMeters?.let {
    (if (row.brakingInferred) "≈" else "") + formatMeasure(it, CornerMeasure.Meters, "")
} ?: "–"

/** The minimum speed of [row]: "82 km/h", "–" without one. */
internal fun minimumText(row: CornerLapRow, unit: String): String = row.minimumSpeed?.let {
    if (unit.isEmpty()) String.format(Locale.ROOT, "%.0f", it) else String.format(Locale.ROOT, "%.0f %s", it, unit)
} ?: "–"
