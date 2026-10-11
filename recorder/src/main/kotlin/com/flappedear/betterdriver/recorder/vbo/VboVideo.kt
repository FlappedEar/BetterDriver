package com.flappedear.betterdriver.recorder.vbo

import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import java.util.Locale

/** How avisynctime is written. Racelogic does not document it; see [VboVideo]. */
enum class AviSyncFormat {
    /** Whole milliseconds, 9 characters; -1 where there is no video. Matches Racelogic's example rows. */
    MILLIS,

    /** Seconds with 3 decimals; 0 where there is no video. What RaceSyncVideoProcessor writes. */
    SECONDS,
}

/**
 * Video for a VBO export for Circuit Tools (FBBD-25): the clips of one camera, written next to the
 * VBO as `<baseName>0001.MP4`, `<baseName>0002.MP4` and so on, in start order.
 *
 * What is known about the format, and from where:
 * - `[avi]` holds the base name of the video files (Racelogic's VBO file structure page; its
 *   example is `VBOX_20141207085917_`). Two public converters also write the extension, "MP4",
 *   on the next line (RaceChrono_Session_Converter rc2vbo.py, RaceSyncVideoProcessor).
 * - `avifileindex` is the 1-based file number, 4 digits.
 * - `avisynctime` is not defined anywhere public. Racelogic's example rows go from 266 to 1935 over
 *   1.7 s of data and show -1 before the video starts, which fits milliseconds (inferred, medium
 *   confidence). rc2vbo.py also writes milliseconds. Circuit Tools must confirm it (FBBD-51).
 */
data class VboVideo(
    val baseName: String,
    val clips: List<VideoClip>,
    val extension: String = "MP4",
    val syncFormat: AviSyncFormat = AviSyncFormat.MILLIS,
    /** Writes [extension] as a second line of `[avi]`. */
    val extensionLine: Boolean = true,
    /**
     * Rows without video get avifileindex 0 and avisynctime 0, as rc2vbo.py writes them, instead of
     * the next file and -1 (FBBD-51 round 2).
     */
    val noVideoAsZero: Boolean = false,
) {
    val orderedClips: List<VideoClip> = clips.sortedBy { it.startMicros }

    /** The name each clip gets next to the VBO, in avifileindex order. */
    fun fileNames(): List<String> = orderedClips.indices.map(::fileName)

    fun fileName(index: Int): String = String.format(Locale.ROOT, "%s%04d.%s", baseName, index + 1, extension)

    private val timeline = VideoTimeline(orderedClips.map { it.copy(camera = Camera) })

    /** avifileindex and avisynctime values for a data row at [sessionMicros]. */
    internal fun columnsAt(sessionMicros: Long): Pair<Double, Double> {
        val pos = timeline.locate(Camera, sessionMicros)
        if (pos != null && pos.mediaMicros >= 0) {
            val media = pos.mediaMicros / MicrosPerMilli
            val sync = if (syncFormat == AviSyncFormat.MILLIS) media else media / MillisPerSecond
            return (pos.index + 1).toDouble() to sync
        }
        return noVideoAt(sessionMicros)
    }

    private fun noVideoAt(sessionMicros: Long): Pair<Double, Double> {
        if (noVideoAsZero) return 0.0 to 0.0
        // Point at the next file to come (or the last one) with "no time".
        val upcoming = orderedClips.indexOfFirst { it.startMicros > sessionMicros }
        val next = if (upcoming < 0) orderedClips.lastIndex else upcoming
        val none = if (syncFormat == AviSyncFormat.MILLIS) -1.0 else 0.0
        return (next + 1).toDouble() to none
    }

    internal val syncColumn: VboColumn
        get() = VboColumn(
            "avisynctime",
            "avisynctime",
            "",
            if (syncFormat == AviSyncFormat.MILLIS) VboWriter::aviSyncMillis else VboWriter.decimal(SecondsDecimals),
        )

    internal val indexColumn = VboColumn("avifileindex", "avifileindex", "", VboWriter::aviFileIndex)

    private companion object {
        const val Camera = "export"
        const val MicrosPerMilli = 1_000.0
        const val MillisPerSecond = 1_000.0
        const val SecondsDecimals = 3
    }
}
