package com.flappedear.betterdriver.videoexport

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.TextureOverlay
import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.optimal.OptimalClipPlan
import com.flappedear.betterdriver.analysis.optimal.OptimalCut
import com.flappedear.betterdriver.analysis.optimal.OptimalVideo
import com.flappedear.betterdriver.analysis.optimal.optimalVideo
import com.flappedear.betterdriver.feature.review.SessionVideo
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.video.mp4.Mp4Reader
import java.io.IOException

/**
 * The data overlay of one cut of a composite lap's video (FBBD-163, FBBD-178): the Tech HUD of
 * [dataOverlays] (FBBD-347), drawn from the session data at the frame's session time, with the lap
 * tile showing the lap the part comes from over the theoretical lap's running time
 * ([OptimalClipPlan.overlayAt]).
 */
@OptIn(UnstableApi::class)
internal fun optimalOverlays(
    data: OverlayData,
    plan: OptimalClipPlan,
    cut: OptimalCut,
    painter: TechHudPainter,
): List<TextureOverlay> = List(MaxStrips) { strip ->
    HudStrip(painter, strip) { t ->
        val at = cut.cut.sessionMicrosAt(t)
        HudMoment(at, data.at(at), plan.overlayAt(cut, at, runs = data.pointToPoint))
    }
}

/**
 * The composite laps of [analysis] on [video], whose session clock is [originMicros] at telemetry
 * time 0; [durationOf] is a clip file's length. Reads files: never on the main thread. The choice
 * itself is shared with the iOS app (FBBD-370).
 */
internal fun optimalVideo(
    analysis: SessionAnalysis?,
    video: SessionVideo?,
    originMicros: Long,
    durationOf: (VideoClip) -> Long?,
): OptimalVideo = optimalVideo(analysis, video?.clips, originMicros, durationOf)

/** Each clip file's length, read from its index once; null when it cannot be read. */
internal fun fileDurations(video: SessionVideo): (VideoClip) -> Long? {
    val read = HashMap<String, Long?>()
    return { clip ->
        read.getOrPut(clip.file) {
            try {
                Mp4Reader.read(video.file(clip)).durationUs.takeIf { it > 0 }
            } catch (e: IOException) {
                DebugLog.w("OptimalVideo", "unreadable video ${clip.file}", e)
                null
            }
        }
    }
}
