package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.log.CheckLog
import com.flappedear.betterdriver.video.VideoClip

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
