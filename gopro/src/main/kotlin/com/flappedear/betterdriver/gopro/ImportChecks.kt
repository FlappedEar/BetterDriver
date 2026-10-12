package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.log.CheckLog
import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.gpmf.GpsSample
import com.flappedear.betterdriver.video.sync.AlignmentResult
import com.flappedear.betterdriver.video.sync.GpsAligner
import com.flappedear.betterdriver.video.sync.SpeedPoint

/** Length of the stretches of an imported file aligned on their own, for its drift (FBBD-82). */
internal const val ProbeStretchUs = 120_000_000L

/** One stretch of a file aligned on its own: its middle on the session clock, and its offset. */
internal data class Probe(val middleUs: Long, val offsetUs: Long)

/**
 * Each [ProbeStretchUs] stretch of [clip]'s GPS aligned on its own against the session's [speeds],
 * starting from the whole file's offset. A stretch whose speeds give no match (the car parked) is
 * left out. How far the stretches' offsets move along the file is the camera clock's drift.
 */
internal fun probes(aligner: GpsAligner, gps: List<GpsSample>, speeds: List<SpeedPoint>, clip: VideoClip): List<Probe> {
    if (clip.alignment.method != AlignmentMethod.GPS_MATCH || gps.isEmpty()) return emptyList()
    val prior = AlignmentResult(clip.sessionOffsetUs, Alignment(AlignmentMethod.GPS_MATCH, 0))
    return gps.groupBy { it.videoTimeUs / ProbeStretchUs }.toSortedMap().mapNotNull { (index, stretch) ->
        val result = aligner.align(stretch, speeds, prior)?.takeIf { it.alignment.method == AlignmentMethod.GPS_MATCH }
        result?.let { Probe(clip.sessionOffsetUs + index * ProbeStretchUs + ProbeStretchUs / 2, it.sessionOffsetUs) }
    }
}

/** The import of [clip] into recording [session], and its probes, in the drive's log (FBBD-183). */
internal fun logImport(session: String, source: String, clip: VideoClip, probes: List<Probe>) {
    val placed = "camera=$source file=${clip.fileName} result=aligned method=${clip.alignment.method} " +
        "offset_us=${clip.sessionOffsetUs} duration_us=${clip.durationUs ?: 0}"
    CheckLog.event(session, maxOf(0L, clip.sessionOffsetUs), EventKinds.VIDEO_IMPORT, placed)
    probes.forEach { p ->
        val text = "camera=$source file=${clip.fileName} offset_us=${p.offsetUs} clip_offset_us=${clip.sessionOffsetUs}"
        CheckLog.event(session, p.middleUs, EventKinds.VIDEO_PROBE, text)
    }
}

/** A file that could not be placed in [session], in the drive's log. */
internal fun logRejected(session: String, source: String, file: String, why: String) {
    val text = "camera=$source file=$file result=rejected why=${why.replace(' ', '_')}"
    CheckLog.event(session, 0L, EventKinds.VIDEO_IMPORT, text)
}
