package com.flappedear.betterdriver.analysis.optimal

import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.timing.overlay.MinCutMicros
import com.flappedear.betterdriver.timing.overlay.VideoCut
import com.flappedear.betterdriver.timing.overlay.totalMicros
import com.flappedear.betterdriver.timing.overlay.videoCuts

// The theoretical best lap's video (FBBD-163): each part of the optimal lap found in the session's
// clips and laid back to back, as the lap video export (FBBD-152) lays out one lap.

/**
 * One piece of the optimal lap's video: [cut] (its place in the export and in its file) showing
 * part [partIndex] of the plan, which starts at session time [partStartMicros], [offsetMicros] into
 * the theoretical lap, and lasts [partMicros].
 */
data class OptimalCut(
    val cut: VideoCut,
    val partIndex: Int,
    val partStartMicros: Long,
    val offsetMicros: Long,
    val partMicros: Long,
) {
    /**
     * The theoretical lap's running time at session time [sessionMicros] of this cut: its part's
     * offset plus the time since the part started, never outside the part.
     */
    fun theoreticalMicrosAt(sessionMicros: Long): Long =
        offsetMicros + (sessionMicros - partStartMicros).coerceIn(0L, partMicros)
}

/** How much of [part] ([partMicros] long) is on video: [videoMicros]. */
data class PartVideo(val part: OptimalPart, val partMicros: Long, val videoMicros: Long) {
    /** No video at all: the part is left out of the film. */
    val missing: Boolean get() = videoMicros == 0L

    /** Some video, with a stretch the cameras missed: that stretch is left out. */
    val partial: Boolean get() = !missing && videoMicros < partMicros - PartialToleranceMicros
}

/**
 * What the optimal lap's video is made of: [cuts] back to back, from [lap]'s parts in track order;
 * [parts] says how much of each part is on video. A part with no video is skipped (its time still
 * counts in the running theoretical time, which jumps over it).
 */
class OptimalClipPlan(val lap: OptimalLap, val cuts: List<OptimalCut>, val parts: List<PartVideo>) {
    /** Some part is on video: there is a film to make. */
    val onVideo: Boolean get() = cuts.isNotEmpty()

    /** The film's length. */
    val totalMicros: Long get() = cuts.map { it.cut }.totalMicros()

    val missing: List<OptimalPart> get() = parts.filter { it.missing }.map { it.part }

    val partial: List<OptimalPart> get() = parts.filter { it.partial }.map { it.part }

    /**
     * The theoretical lap minus the fastest lap after each part's predecessors, seconds; null from
     * the first part the fastest lap has no time for.
     */
    private val deltas: List<Double?> = run {
        var sum: Double? = 0.0
        lap.parts.map { p ->
            sum.also { s -> sum = p.referenceSeconds?.let { r -> s?.plus(p.seconds - r) } }
        }
    }

    /**
     * What the overlay shows at session time [sessionMicros] of [cut]; the laps are called runs on a
     * point-to-point track ([runs]).
     */
    fun overlayAt(cut: OptimalCut, sessionMicros: Long, runs: Boolean = false): OptimalOverlayText {
        val part = lap.parts[cut.partIndex]
        val from = "${if (runs) "RUN" else "LAP"} ${part.lapNumber}"
        val name = part.name.uppercase()
        return OptimalOverlayText(
            captions = listOf("${captionOf(lap.kind)} · $from · $name", "$from · $name", from),
            theoreticalMicros = cut.theoreticalMicrosAt(sessionMicros),
            deltaSeconds = deltas[cut.partIndex],
        )
    }
}

/** The overlay's name for a composite lap: never "optimal" alone (FBBD-178). */
fun captionOf(kind: OptimalKind): String = when (kind) {
    OptimalKind.RealisticOptimal -> "REALISTIC OPTIMAL"
    OptimalKind.RawTheoreticalBest -> "RAW THEORETICAL"
}

/**
 * The optimal lap overlay's text at one moment: the lap slot's caption (the first of [captions],
 * longest first, that fits) over the theoretical lap's running time [theoreticalMicros]; the delta
 * slot shows [deltaSeconds], what the theoretical lap has gained on the fastest lap over the parts
 * already driven (negative is a gain).
 */
data class OptimalOverlayText(val captions: List<String>, val theoreticalMicros: Long, val deltaSeconds: Double?)

/**
 * The plan of [lap]'s video from [clips] (one camera's), whose session clock is [originMicros] at
 * telemetry time 0. Each part is cut from the clips as the lap export cuts a lap ([videoCuts]:
 * [durationMicros] is a clip file's length, a gap between clips is skipped, pieces under
 * [minMicros] are dropped), then the cuts of all the parts are laid one after the other.
 */
fun optimalClipPlan(
    lap: OptimalLap,
    clips: List<VideoClip>,
    durationMicros: (VideoClip) -> Long?,
    originMicros: Long,
    minMicros: Long = MinCutMicros,
): OptimalClipPlan {
    val cuts = ArrayList<OptimalCut>()
    val parts = ArrayList<PartVideo>()
    var output = 0L
    var offset = 0L
    for ((i, part) in lap.parts.withIndex()) {
        val range = part.sessionRange(originMicros)
        val partMicros = range.last - range.first
        val pieces = videoCuts(clips, durationMicros, range.first, range.last, minMicros)
        for (piece in pieces) {
            cuts += OptimalCut(piece.copy(outputStartMicros = output), i, range.first, offset, partMicros)
            output += piece.durationMicros
        }
        parts += PartVideo(part, partMicros, pieces.totalMicros())
        offset += partMicros
    }
    return OptimalClipPlan(lap, cuts, parts)
}

/** A part this much short on video is still whole: the clips' ends are not exact to the frame. */
const val PartialToleranceMicros = 500_000L
