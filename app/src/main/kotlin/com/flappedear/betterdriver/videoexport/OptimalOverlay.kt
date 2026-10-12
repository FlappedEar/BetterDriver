package com.flappedear.betterdriver.videoexport

import android.graphics.Canvas
import android.graphics.PorterDuff
import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.optimal.OptimalClipPlan
import com.flappedear.betterdriver.analysis.optimal.OptimalCut
import com.flappedear.betterdriver.analysis.optimal.OptimalLapResult
import com.flappedear.betterdriver.analysis.optimal.optimalClipPlan
import com.flappedear.betterdriver.analysis.optimal.optimalLap
import com.flappedear.betterdriver.feature.review.SessionVideo
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.OverlayLayout
import com.flappedear.betterdriver.video.mp4.Mp4Reader
import java.io.IOException

/**
 * The data overlay of one cut of the optimal lap's video (FBBD-163): the band of [DataOverlay],
 * drawn from the session data at the frame's session time, with the lap slot showing the lap the
 * part comes from over the theoretical lap's running time ([OptimalClipPlan.overlayAt]).
 */
@OptIn(UnstableApi::class)
internal class OptimalOverlay(
    private val data: OverlayData,
    private val plan: OptimalClipPlan,
    private val cut: OptimalCut,
    private val painter: OverlayPainter,
) : CanvasOverlay(false) {
    private var layout: OverlayLayout? = null

    /** As [DataOverlay]: the band's bottom edge on the picture's bottom edge, centred. */
    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, -1f)
        .setOverlayFrameAnchor(0f, -1f)
        .build()

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        val l = OverlayLayout.of(videoSize.width, videoSize.height)
        layout = l
        setCanvasSize(l.width, l.height)
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val l = layout ?: return
        val t = cut.cut.sessionMicrosAt(presentationTimeUs)
        painter.draw(canvas, l, data.at(t), plan.overlayAt(cut, t))
    }
}

/**
 * A session's optimal lap ([result]; null when it has no laps at a saved track) and its video
 * ([plan]; null when it has none).
 */
internal class OptimalVideo(val result: OptimalLapResult?, val plan: OptimalClipPlan?) {
    val option: OptimalOption get() = optimalOption(result, plan)
}

/**
 * The optimal lap of [analysis] on [video], whose session clock is [originMicros] at telemetry
 * time 0; [durationOf] is a clip file's length. Reads files: never on the main thread.
 */
internal fun optimalVideo(
    analysis: SessionAnalysis?,
    video: SessionVideo?,
    originMicros: Long,
    durationOf: (VideoClip) -> Long?,
): OptimalVideo {
    val result = analysis?.takeIf { it.laps.timedLaps.isNotEmpty() }?.let(::optimalLap)
    val lap = result?.lap
    val plan = if (lap != null && video != null) optimalClipPlan(lap, video.clips, durationOf, originMicros) else null
    return OptimalVideo(result, plan)
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
