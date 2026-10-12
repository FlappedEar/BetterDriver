package com.flappedear.betterdriver.analysis.corners

import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

// A corner pass on the session's video (FBBD-153): telemetry seconds to the session clock the
// video clips are placed on, then to the clip and media position that show it.

private const val MicrosPerSecond = 1_000_000.0
private const val MicrosPerMilli = 1_000L

/** Seconds of video before the corner entry when the pass has no braking point to start at. */
const val CornerLeadSeconds = 1.0

/** Seconds of video before the braking point, so the approach to it shows. */
const val BrakingLeadSeconds = 0.5

/** At most this much video before the entry; a braking point further back is left out. */
const val MaxCornerLeadSeconds = 6.0

/** A lap of another lap list starts within this of the same crossing of the line. */
const val SameLapToleranceMicros = 1_500_000L

/**
 * The session-clock time (microseconds, as on the recorder's samples and video clips) of telemetry
 * time [seconds]: telemetry time 0 is the session's first sample, at [originMicros].
 */
fun sessionMicros(originMicros: Long, seconds: Double): Long = originMicros + (seconds * MicrosPerSecond).roundToLong()

/** Where a corner pass is on video: [clip] from [startMillis] to [endMillis] of its media. */
data class CornerClip(val clip: VideoClip, val startMillis: Long, val endMillis: Long)

/**
 * How long before the corner entry the pass's video starts: from just before its braking point, so
 * the braking shows, else [CornerLeadSeconds]; never more than [MaxCornerLeadSeconds].
 */
fun cornerLeadSeconds(pass: CornerPass): Double {
    val entry = pass.entryTime ?: return 0.0
    val braking = pass.brakingTime?.let { entry - it }?.takeIf { it >= 0.0 }
    return min(braking?.plus(BrakingLeadSeconds) ?: CornerLeadSeconds, MaxCornerLeadSeconds)
}

/**
 * The video of [pass] in [camera]'s clips of [timeline], whose session clock is [originMicros] at
 * telemetry time 0: the clip showing the corner entry, from [cornerLeadSeconds] before it (or the
 * clip's start) to the corner exit (or the clip's end). Null when the pass has no entry or exit, or
 * no clip shows its entry.
 */
@Suppress("ReturnCount")
fun cornerClip(pass: CornerPass, timeline: VideoTimeline, camera: String, originMicros: Long): CornerClip? {
    val entry = pass.entryTime ?: return null
    val exit = pass.exitTime ?: return null
    val entryMicros = sessionMicros(originMicros, entry)
    val at = timeline.locate(camera, entryMicros) ?: return null
    val clip = at.clip
    // As VideoTimeline.locate: a clip with no known end runs until the camera's next clip starts.
    val clipEnd = clip.endMicros ?: timeline.clips(camera).getOrNull(at.index + 1)?.startMicros
    val from = maxOf(sessionMicros(originMicros, entry - cornerLeadSeconds(pass)), clip.startMicros)
    val to = sessionMicros(originMicros, exit).let { if (clipEnd == null) it else min(it, clipEnd) }
    return if (to > entryMicros) {
        CornerClip(clip, clip.mediaMicrosAt(from) / MicrosPerMilli, clip.mediaMicrosAt(to) / MicrosPerMilli)
    } else {
        null
    }
}

/**
 * The lap of [starts] (lap number to the session-clock time of its start) that starts at
 * [startMicros], within [SameLapToleranceMicros]: the same lap in another list of the session's
 * laps, such as the lap review's, which numbers laps on its own. Null when none does.
 */
fun sameLap(startMicros: Long, starts: Map<Int, Long>): Int? = starts.entries
    .filter { abs(it.value - startMicros) <= SameLapToleranceMicros }
    .minByOrNull { abs(it.value - startMicros) }
    ?.key
