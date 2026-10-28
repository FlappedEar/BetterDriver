package com.flappedear.betterdriver.timing.overlay

import com.flappedear.betterdriver.recorder.video.VideoClip

/**
 * One piece of the exported video (FBBD-152): media time [fromMicros] up to [toMicros] of [clip]'s
 * file, played from [outputStartMicros] of the export. A lap that runs across two clips (the camera
 * started a new file) is two cuts, one after the other.
 */
data class VideoCut(val clip: VideoClip, val fromMicros: Long, val toMicros: Long, val outputStartMicros: Long) {
    val durationMicros: Long get() = toMicros - fromMicros

    /** Session time of the cut's first frame. */
    val sessionStartMicros: Long get() = clip.startMicros + fromMicros

    /**
     * Session time of the frame at [outputMicros] of the export. The exporter stamps each frame with
     * its time in the export, so this is how the overlay finds the data to draw on it.
     */
    fun sessionMicrosAt(outputMicros: Long): Long = sessionStartMicros + (outputMicros - outputStartMicros)
}

/**
 * The cuts that show session time [startMicros] to [endMicros] from [clips] (one camera's, any
 * order), back to back. Pass [Long.MIN_VALUE] and [Long.MAX_VALUE] for all of the video.
 *
 * Each clip's length is [durationMicros] of its file (read from the file, which is exact), else
 * its logged end, else up to the next clip; a clip with none of these is left out. Where clips
 * overlap, the later one starts where the earlier one ends, so no moment is shown twice; a time
 * no clip covers is skipped. Pieces shorter than [minMicros] are dropped.
 */
fun videoCuts(
    clips: List<VideoClip>,
    durationMicros: (VideoClip) -> Long?,
    startMicros: Long,
    endMicros: Long,
    minMicros: Long = MinCutMicros,
): List<VideoCut> {
    val sorted = clips.sortedBy { it.startMicros }
    val cuts = ArrayList<VideoCut>()
    var shownUntil = startMicros
    var output = 0L
    for ((i, clip) in sorted.withIndex()) {
        val length = durationMicros(clip)
            ?: clip.endMicros?.let { it - clip.startMicros }
            ?: sorted.getOrNull(i + 1)?.let { it.startMicros - clip.startMicros }
            ?: 0L
        val from = maxOf(shownUntil, clip.startMicros)
        val to = minOf(endMicros, clip.startMicros + length)
        if (length > 0 && to - from >= minMicros) {
            val cut = VideoCut(clip, from - clip.startMicros, to - clip.startMicros, output)
            cuts += cut
            output += cut.durationMicros
            shownUntil = to
        }
    }
    return cuts
}

/** The export's length: all its cuts back to back. */
fun List<VideoCut>.totalMicros(): Long = sumOf { it.durationMicros }

/**
 * A place in cuts played one after the other, as a player plays them (FBBD-341): cut [index], and
 * [intoMicros] from that cut's first frame (the player's position in a clipped item).
 */
data class CutPosition(val index: Int, val intoMicros: Long)

/**
 * Where [outputMicros] of these cuts, back to back, falls: before the start, the first frame; past
 * the end, the last cut's end. Null without cuts.
 */
fun List<VideoCut>.positionAt(outputMicros: Long): CutPosition? {
    if (isEmpty()) return null
    val i = indexOfLast { it.outputStartMicros <= outputMicros }.coerceAtLeast(0)
    val c = this[i]
    return CutPosition(i, (outputMicros - c.outputStartMicros).coerceIn(0L, c.durationMicros))
}

/** The time into all the cuts of [at]; null for a cut that is not there. */
fun List<VideoCut>.outputMicrosAt(at: CutPosition): Long? =
    getOrNull(at.index)?.let { it.outputStartMicros + at.intoMicros.coerceIn(0L, it.durationMicros) }

/** The session time shown at [at], for the overlay; null for a cut that is not there. */
fun List<VideoCut>.sessionMicrosAt(at: CutPosition): Long? =
    getOrNull(at.index)?.let { it.sessionStartMicros + at.intoMicros.coerceIn(0L, it.durationMicros) }

/** Shorter pieces are not worth a seam in the export. */
const val MinCutMicros = 200_000L
