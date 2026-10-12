package com.flappedear.betterdriver.videoexport

import com.flappedear.betterdriver.analysis.optimal.OptimalClipPlan
import com.flappedear.betterdriver.analysis.optimal.OptimalLapResult
import com.flappedear.betterdriver.analysis.optimal.OptimalLapUnavailable
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import kotlin.math.roundToLong

/** The part of a video export that is the optimal lap (FBBD-163): the theoretical best, filmed. */
const val OptimalLap: VideoPart = -1

/** How much of one part of the optimal lap the cameras have. */
enum class PartOnVideo { Whole, Partly, None }

/** One part of the optimal lap as the sheet lists it: "Corner 3", from lap [lapNumber], [millis] long. */
data class OptimalPartRow(val name: String, val lapNumber: Int, val millis: Long, val video: PartOnVideo)

/**
 * The optimal lap's video as the picker and the lap analysis offer it (FBBD-163): the theoretical
 * best ([totalMillis]) and its [parts], from [laps]. [unavailable] says why it cannot be made; null
 * when it can (some part is on video).
 */
data class OptimalOption(
    val totalMillis: Long?,
    val parts: List<OptimalPartRow>,
    val laps: List<Int>,
    val unavailable: String?,
) {
    val enabled: Boolean get() = unavailable == null

    /** Parts the film leaves out: no video at all. */
    val missing: List<OptimalPartRow> get() = parts.filter { it.video == PartOnVideo.None }

    /** Parts with a stretch the cameras missed, which the film leaves out. */
    val partly: List<OptimalPartRow> get() = parts.filter { it.video == PartOnVideo.Partly }
}

/**
 * What can be offered for the optimal lap: [result] is the session's optimal lap (null when the
 * session has no laps at a saved track), [plan] its video (null when the session has none).
 */
fun optimalOption(result: OptimalLapResult?, plan: OptimalClipPlan?): OptimalOption {
    val lap = result?.lap
    val parts = plan?.parts?.map { p ->
        val video = when {
            p.missing -> PartOnVideo.None
            p.partial -> PartOnVideo.Partly
            else -> PartOnVideo.Whole
        }
        OptimalPartRow(p.part.name, p.part.lapNumber, p.partMicros / MicrosPerMilli, video)
    } ?: lap?.parts?.map { OptimalPartRow(it.name, it.lapNumber, millis(it.seconds), PartOnVideo.None) }.orEmpty()
    val why = when {
        result == null -> "No theoretical best: the session has no counted laps at a saved track."
        result.why == OptimalLapUnavailable.NoSegments ->
            "No theoretical best: the corners could not be found on the best lap."
        lap == null -> "No theoretical best: a corner has no fully timed pass on any counted lap."
        plan == null -> "This session has no video."
        !plan.onVideo -> "No part of the theoretical best is on video."
        else -> null
    }
    return OptimalOption(lap?.totalSeconds?.let(::millis), parts, lap?.laps.orEmpty(), why)
}

/**
 * The picker's line: "Optimal lap · 1:41.20" over where it comes from and what is missing, or why
 * it cannot be made.
 */
fun optimalOptionText(o: OptimalOption): Pair<String, String> {
    val name = "Optimal lap" + (o.totalMillis?.let { " · ${formatLapTime(it)}" } ?: "")
    val note = o.unavailable ?: listOfNotNull(
        "Best of each corner from ${lapsText(o.laps)}",
        missingText(o),
    ).joinToString(" · ")
    return name to note
}

/**
 * The parts left out, for the sheet before the video is made: "No video, skipped: Corner 3 (lap 5),
 * Straight 2 (lap 6)", then those only partly filmed; null when every part is whole on video.
 */
fun missingText(o: OptimalOption): String? = listOfNotNull(
    o.missing.takeIf { it.isNotEmpty() }?.let { "No video, skipped: ${partsText(it)}" },
    o.partly.takeIf { it.isNotEmpty() }?.let { "Partly on video: ${partsText(it)}" },
).joinToString(" · ").ifEmpty { null }

/** "lap 4", "laps 2 and 4", "laps 2, 4 and 7". */
internal fun lapsText(laps: List<Int>): String = when (laps.size) {
    0 -> "no lap"
    1 -> "lap ${laps[0]}"
    else -> "laps ${laps.dropLast(1).joinToString(", ")} and ${laps.last()}"
}

/** "Corner 3 (lap 5), Straight 2 (lap 6)": a segment over the line, which is two parts, named once. */
private fun partsText(parts: List<OptimalPartRow>) =
    parts.map { "${it.name} (lap ${it.lapNumber})" }.distinct().joinToString(", ")

/** The sheet's line for a part: "Lap 5 · 0:05.12", then "No video" or "Partly on video". */
fun optimalPartText(p: OptimalPartRow): Pair<String, String?> = "Lap ${p.lapNumber} · ${formatLapTime(p.millis)}" to
    when (p.video) {
        PartOnVideo.Whole -> null
        PartOnVideo.Partly -> "Partly on video"
        PartOnVideo.None -> "No video"
    }

private fun millis(seconds: Double): Long = (seconds * MillisPerSecond).roundToLong()

private const val MicrosPerMilli = 1_000L
private const val MillisPerSecond = 1_000.0
