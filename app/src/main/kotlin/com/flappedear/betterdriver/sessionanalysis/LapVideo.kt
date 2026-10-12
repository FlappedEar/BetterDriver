package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.Routes
import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.corners.cornerClip
import com.flappedear.betterdriver.analysis.corners.cornerPasses
import com.flappedear.betterdriver.analysis.corners.sameLap
import com.flappedear.betterdriver.analysis.corners.sessionMicros
import com.flappedear.betterdriver.feature.analysis.CornerLapsUi
import com.flappedear.betterdriver.feature.analysis.CornerVideoUi
import com.flappedear.betterdriver.feature.analysis.cornerLapsUi
import com.flappedear.betterdriver.feature.review.LapKey
import com.flappedear.betterdriver.feature.review.SessionVideo
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.review.indexedClips
import com.flappedear.betterdriver.review.sessionVideo
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track

/**
 * What the Corner laps screen (FBBD-153) needs besides the analysis: the session's video as the lap
 * review shows it, the session clock at telemetry time 0, and the lap review's laps of the session
 * (lap number to its start on the session clock), which it numbers on its own.
 */
internal class LapVideo(val video: SessionVideo?, val originMicros: Long, val reviewStarts: Map<Int, Long>)

/** [data]'s [LapVideo] at [track]; null when it cannot be read, which only leaves the video out. */
// Must never fail the analysis.
@Suppress("TooGenericExceptionCaught")
internal fun lapVideo(store: SessionStore, data: SessionData, track: Track): LapVideo? = try {
    val dir = store.dir(data.id)
    val video = sessionVideo(dir, VideoTimeline.from(data.events, indexedClips(dir)))
    // As telemetrySessionOf: telemetry time 0 is the first sample.
    val origin = data.sampleSets.minOfOrNull { it.tMicros } ?: 0L
    val laps = replayLaps(data.id, data.sampleSets, track).laps
    LapVideo(video, origin, laps.associate { it.lap.number to it.lap.startMicros })
} catch (e: Exception) {
    DebugLog.w("SessionAnalysis", "reading the video of session ${data.id} failed", e)
    null
}

/** Every counted lap through corner [cornerId] of [analysis], each with its pass on video where there is one. */
internal fun cornerLaps(analysis: SessionAnalysis, cornerId: String, lapVideo: LapVideo?): CornerLapsUi {
    val name = analysis.segments?.segments?.firstOrNull { it.id == cornerId }?.name ?: "Corner"
    val video = lapVideo?.video
    val timeline = video?.let { VideoTimeline(it.clips) }
    return cornerLapsUi(
        cornerId,
        name,
        cornerPasses(analysis, cornerId),
        video = { pass ->
            if (lapVideo == null || video == null || timeline == null) {
                null
            } else {
                cornerClip(pass, timeline, video.camera, lapVideo.originMicros)?.let {
                    CornerVideoUi(video.file(it.clip).absolutePath, it.startMillis, it.endMillis)
                }
            }
        },
        reviewable = { reviewLap(analysis, lapVideo, it.lapIndex) != null },
    )
}

/** The lap review's number for timed lap [lapIndex] of [analysis], or null when the review has no such lap. */
private fun reviewLap(analysis: SessionAnalysis, lapVideo: LapVideo?, lapIndex: Int): Int? {
    val lap = analysis.laps.timedLaps.getOrNull(lapIndex)
    return if (lapVideo == null || lap == null) {
        null
    } else {
        sameLap(sessionMicros(lapVideo.originMicros, lap.startTelemetryTime), lapVideo.reviewStarts)
    }
}

/**
 * The lap review of timed laps [a] against [b] of session [sessionId], its cursor a little before
 * corner [cornerId] so the approach shows; null when the review does not have both laps.
 */
@Suppress("LongParameterList") // The two laps, where, and what they are matched with.
internal fun cornerReviewRoute(
    analysis: SessionAnalysis,
    sessionId: String,
    lapVideo: LapVideo?,
    a: Int,
    b: Int,
    cornerId: String,
): String? {
    val ra = reviewLap(analysis, lapVideo, a)
    val rb = reviewLap(analysis, lapVideo, b)
    if (ra == null || rb == null) return null
    val length = analysis.axis?.lengthMeters
    val entry = analysis.segments?.segments?.firstOrNull { it.id == cornerId }?.startProgressMeters
    val at = if (length != null && length > 0.0 && entry != null) {
        (entry - ReviewApproachMeters).coerceIn(0.0, length) / length
    } else {
        null
    }
    return Routes.review(LapKey(sessionId, ra).encode(), LapKey(sessionId, rb).encode(), at)
}

/** The review starts this far before the corner entry. */
private const val ReviewApproachMeters = 150.0
