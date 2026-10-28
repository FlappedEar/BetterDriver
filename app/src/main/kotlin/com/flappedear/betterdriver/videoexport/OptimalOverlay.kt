package com.flappedear.betterdriver.videoexport

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.TextureOverlay
import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.optimal.OptimalClipPlan
import com.flappedear.betterdriver.analysis.optimal.OptimalCut
import com.flappedear.betterdriver.analysis.optimal.OptimalKind
import com.flappedear.betterdriver.analysis.optimal.OptimalLapResult
import com.flappedear.betterdriver.analysis.optimal.optimalClipPlan
import com.flappedear.betterdriver.analysis.optimal.optimalLap
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
 * One composite lap of a session ([kind]): the lap ([result]; null when the session has no laps at
 * a saved track) and its video ([plan]; null when it has none).
 */
internal class OptimalFilm(val kind: OptimalKind, val result: OptimalLapResult?, val plan: OptimalClipPlan?) {
    val option: OptimalOption get() = optimalOption(kind, result, plan)
}

/**
 * A session's two composite laps on video (FBBD-178): the [realistic] optimal, the composite
 * video's default, and the [raw] theoretical best.
 */
internal class OptimalVideo(val realistic: OptimalFilm, val raw: OptimalFilm) {
    fun of(kind: OptimalKind): OptimalFilm = if (kind == OptimalKind.RealisticOptimal) realistic else raw

    companion object {
        /** No composite lap: the session could not be analysed. */
        val None = OptimalVideo(
            OptimalFilm(OptimalKind.RealisticOptimal, null, null),
            OptimalFilm(OptimalKind.RawTheoreticalBest, null, null),
        )
    }
}

/**
 * The composite laps of [analysis] on [video], whose session clock is [originMicros] at telemetry
 * time 0; [durationOf] is a clip file's length. Reads files: never on the main thread.
 */
internal fun optimalVideo(
    analysis: SessionAnalysis?,
    video: SessionVideo?,
    originMicros: Long,
    durationOf: (VideoClip) -> Long?,
): OptimalVideo {
    fun film(kind: OptimalKind): OptimalFilm {
        val result = analysis?.takeIf { it.laps.timedLaps.isNotEmpty() }?.let { optimalLap(it, kind) }
        val lap = result?.lap
        val plan = if (lap != null && video != null) {
            optimalClipPlan(lap, video.clips, durationOf, originMicros)
        } else {
            null
        }
        return OptimalFilm(kind, result, plan)
    }
    return OptimalVideo(film(OptimalKind.RealisticOptimal), film(OptimalKind.RawTheoreticalBest))
}

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
