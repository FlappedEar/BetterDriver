package com.flappedear.betterdriver.videoexport

import com.flappedear.betterdriver.analysis.optimal.OptimalClipPlan
import com.flappedear.betterdriver.analysis.optimal.OptimalKind
import com.flappedear.betterdriver.analysis.optimal.OptimalLapResult
import com.flappedear.betterdriver.analysis.optimal.OptimalLapUnavailable
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import kotlin.math.roundToLong

/**
 * The part of a video export that is the realistic optimal lap (FBBD-178), filmed: the composite
 * video's default (FBBD-163).
 */
const val RealisticOptimalLap: VideoPart = -1

/** The part of a video export that is the raw theoretical best, filmed (FBBD-163). */
const val RawTheoreticalLap: VideoPart = -2

/** The name the screens give [kind] (FBBD-178): never "optimal" alone, never one name for both. */
fun kindName(kind: OptimalKind): String = when (kind) {
    OptimalKind.RealisticOptimal -> "Realistic optimal"
    OptimalKind.RawTheoreticalBest -> "Raw theoretical best"
}

/** How much of one part of a composite lap the cameras have. */
enum class PartOnVideo { Whole, Partly, None }

/** One part of a composite lap as the sheet lists it: "Corner 3", from lap [lapNumber], [millis] long. */
data class OptimalPartRow(val name: String, val lapNumber: Int, val millis: Long, val video: PartOnVideo)

/**
 * A composite lap's video ([kind]) as the picker and the lap analysis offer it (FBBD-163,
 * FBBD-178): its time ([totalMillis]) and its [parts], from [laps]. [unavailable] says why it
 * cannot be made; null when it can (some part is on video).
 */
data class OptimalOption(
    val kind: OptimalKind,
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
 * What can be offered for composite lap [kind]: [result] is the session's lap of that kind (null
 * when the session has no laps at a saved track), [plan] its video (null when the session has none).
 */
fun optimalOption(kind: OptimalKind, result: OptimalLapResult?, plan: OptimalClipPlan?): OptimalOption {
    val lap = result?.lap
    val parts = plan?.parts?.map { p ->
        val video = when {
            p.missing -> PartOnVideo.None
            p.partial -> PartOnVideo.Partly
            else -> PartOnVideo.Whole
        }
        OptimalPartRow(p.part.name, p.part.lapNumber, p.partMicros / MicrosPerMilli, video)
    } ?: lap?.parts?.map { OptimalPartRow(it.name, it.lapNumber, millis(it.seconds), PartOnVideo.None) }.orEmpty()
    val no = "No ${kindName(kind).lowercase()}"
    val why = when {
        result == null -> "$no: the session has no counted laps at a saved track."
        result.why == OptimalLapUnavailable.NoSegments -> "$no: the corners could not be found on the best lap."
        result.why == OptimalLapUnavailable.NoCompatibleChain ->
            "$no: no counted laps join compatibly through every corner."
        lap == null -> "$no: a corner has no fully timed pass on any counted lap."
        plan == null -> "This session has no video."
        !plan.onVideo -> "No part of the ${kindName(kind).lowercase()} is on video."
        else -> null
    }
    return OptimalOption(kind, lap?.totalSeconds?.let(::millis), parts, lap?.laps.orEmpty(), why)
}

/** What the composite lap of [kind] is made of, in a line. */
fun kindDescription(kind: OptimalKind, laps: List<Int>): String = when (kind) {
    OptimalKind.RealisticOptimal -> if (laps.size == 1) {
        "No compatible combination beats ${lapsText(laps)}"
    } else {
        "Compatible corners and straights from ${lapsText(laps)}"
    }
    OptimalKind.RawTheoreticalBest -> "Fastest of each corner from ${lapsText(laps)}, joined whatever the joint"
}

/**
 * The picker's line: "Realistic optimal · 1:41.20" over where it comes from and what is missing,
 * or why it cannot be made.
 */
fun optimalOptionText(o: OptimalOption): Pair<String, String> {
    val name = kindName(o.kind) + (o.totalMillis?.let { " · ${formatLapTime(it)}" } ?: "")
    val note = o.unavailable ?: listOfNotNull(kindDescription(o.kind, o.laps), missingText(o)).joinToString(" · ")
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
