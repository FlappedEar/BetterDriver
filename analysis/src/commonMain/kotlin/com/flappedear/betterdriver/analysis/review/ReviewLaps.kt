package com.flappedear.betterdriver.analysis.review

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.corners.cornerClip
import com.flappedear.betterdriver.analysis.corners.cornerTraces
import com.flappedear.betterdriver.analysis.corners.sameLap
import com.flappedear.betterdriver.analysis.corners.sessionMicros
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.feature.analysis.CornerLapsUi
import com.flappedear.betterdriver.feature.analysis.CornerVideoUi
import com.flappedear.betterdriver.feature.analysis.cornerLapsUi
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.timing.replay.LapTrace
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track

// Every pass through one corner (FBBD-93, FBBD-153) for the iPad and iPhone, as Android's Corner laps
// screen reads it: the same screen model, the same clips, the same lap matching with the lap review.

/**
 * The laps of [data] at [track] as the lap review numbers them, and the session clock at telemetry
 * time 0 ([originMicros]), to match the analysis's timed laps with them by their start.
 */
internal class ReviewLaps(data: SessionData, track: Track) {
    // As telemetrySessionOf: telemetry time 0 is the first sample.
    val originMicros: Long = data.sampleSets.minOfOrNull { it.tMicros } ?: 0L
    val traces: List<LapTrace> = replayLaps(data.id, data.sampleSets, track).laps
    private val starts = traces.associate { it.lap.number to it.lap.startMicros }

    /** The lap review's lap of timed lap [lapIndex] of [analysis]; null when it has no such lap. */
    fun of(analysis: SessionAnalysis, lapIndex: Int): LapTrace? = analysis.laps.timedLaps.getOrNull(lapIndex)
        ?.let { sameLap(sessionMicros(originMicros, it.startTelemetryTime), starts) }
        ?.let { n -> traces.firstOrNull { it.lap.number == n } }
}

/** The lap review's laps of [data] at [track]; null without its recording or a track. */
internal fun reviewLapsOf(data: SessionData?, track: Track?): ReviewLaps? =
    if (data != null && track != null) ReviewLaps(data, track) else null

/** The video of [data] from its events and clip index [indexed], with files [present] in [dir]. */
fun sessionVideoSource(
    data: SessionData?,
    indexed: List<VideoClip>,
    dir: String,
    present: (String) -> Boolean,
): LapVideoSource? =
    data?.let { LapVideoSource.of(VideoTimeline.from(it.events, indexed), dir, present) }

/**
 * Corner [cornerId] of [analysis] as Android's Corner laps screen shows it: every counted pass, its
 * traces read from [session], and its clip in [video] (the session's video, see [LapVideoSource.of])
 * where there is one. A pass is reviewable when the lap review ([laps]) has its lap.
 */
internal fun everyPassOf(
    analysis: SessionAnalysis,
    session: TelemetrySession?,
    cornerId: String,
    laps: ReviewLaps?,
    video: LapVideoSource?,
): CornerLapsUi {
    val timeline = video?.let { VideoTimeline(it.clips) }
    return cornerLapsUi(
        analysis,
        cornerId,
        video = { pass ->
            if (video == null || timeline == null || laps == null) {
                null
            } else {
                cornerClip(pass, timeline, video.camera, laps.originMicros)?.let {
                    CornerVideoUi(video.path(it.clip), it.startMillis, it.endMillis)
                }
            }
        },
        reviewable = { laps?.of(analysis, it.lapIndex) != null },
        traces = { passes -> session?.let { cornerTraces(analysis, it, cornerId, passes) } },
    )
}

/**
 * Where the lap review of two passes through corner [cornerId] starts: a little before the corner
 * entry, so the approach shows, as a share of the lap (0 to 1). Null when the corner or the lap's
 * length is not known.
 */
fun cornerReviewStart(analysis: SessionAnalysis, cornerId: String): Double? {
    val length = analysis.axis?.lengthMeters
    val entry = analysis.segments?.segments?.firstOrNull { it.id == cornerId }?.startProgressMeters
    return if (length != null && length > 0.0 && entry != null) {
        (entry - ReviewApproachMeters).coerceIn(0.0, length) / length
    } else {
        null
    }
}

/** The review of a corner starts this far before its entry. */
const val ReviewApproachMeters = 150.0
