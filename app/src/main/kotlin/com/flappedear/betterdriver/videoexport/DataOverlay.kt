package com.flappedear.betterdriver.videoexport

import android.graphics.Canvas
import android.graphics.PorterDuff
import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import com.flappedear.betterdriver.analysis.optimal.OptimalOverlayText
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.OverlayFrame
import com.flappedear.betterdriver.timing.overlay.TechHudLayout
import com.flappedear.betterdriver.timing.overlay.VideoCut

/**
 * The data overlay of one cut of the export (FBBD-152) in Overlays' Tech HUD layout (FBBD-347),
 * drawn for every frame from the session data at that frame's session time. The exporter stamps each
 * frame with its time in the export; [cut] turns that into session time.
 */
@OptIn(UnstableApi::class)
internal fun dataOverlays(data: OverlayData, cut: VideoCut, painter: TechHudPainter): List<TextureOverlay> =
    List(MaxStrips) { strip ->
        HudStrip(painter, strip) { t -> cut.sessionMicrosAt(t).let { HudMoment(it, data.at(it)) } }
    }

/** The Tech HUD has widgets along the top and along the bottom of the picture: two strips at most. */
internal const val MaxStrips = 2

/** What one frame shows: the data at [sessionMicros] and, for a composite lap (FBBD-163), its lap tile. */
internal class HudMoment(val sessionMicros: Long, val frame: OverlayFrame, val optimal: OptimalOverlayText? = null)

/**
 * Strip [strip] of the Tech HUD (FBBD-347), as wide as the picture, so a 4K export uploads the strips
 * with widgets, not a whole frame, per frame: [at] gives what a frame shows from its time in the
 * export. A picture with fewer strips leaves this one a single clear pixel. Drawn on the
 * exporter's GL thread.
 */
@OptIn(UnstableApi::class)
internal class HudStrip(
    private val painter: TechHudPainter,
    private val strip: Int,
    private val at: (Long) -> HudMoment,
) : CanvasOverlay(false) {
    private var layout: TechHudLayout? = null
    private var top = 0
    private var settings: OverlaySettings = StaticOverlaySettings.Builder().build()

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        val l = painter.layout(videoSize.width, videoSize.height)
        val range = l.strips.getOrNull(strip)
        layout = l.takeIf { range != null }
        top = range?.first ?: 0
        val height = range?.let { (it.last - it.first).coerceAtLeast(1) } ?: 1
        setCanvasSize(if (range != null) videoSize.width else 1, height)
        // The strip's top edge on its row of the picture (NDC: +1 at the top, -1 at the bottom).
        settings = StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, 1f)
            .setBackgroundFrameAnchor(0f, 1f - 2f * top / videoSize.height)
            .build()
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val l = layout ?: return
        val m = at(presentationTimeUs)
        canvas.save()
        canvas.translate(0f, -top.toFloat())
        painter.draw(canvas, l, m.sessionMicros, m.frame, m.optimal)
        canvas.restore()
    }
}
