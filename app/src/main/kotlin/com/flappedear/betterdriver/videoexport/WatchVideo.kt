package com.flappedear.betterdriver.videoexport

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.flappedear.betterdriver.analysis.optimal.OptimalClipPlan
import com.flappedear.betterdriver.analysis.optimal.OptimalOverlayText
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.feature.review.SessionVideo
import com.flappedear.betterdriver.format.formatRunningTime
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.shell.DimText
import com.flappedear.betterdriver.shell.ShellTopBar
import com.flappedear.betterdriver.timing.overlay.CutPosition
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.OverlayFrame
import com.flappedear.betterdriver.timing.overlay.VideoCut
import com.flappedear.betterdriver.timing.overlay.outputMicrosAt
import com.flappedear.betterdriver.timing.overlay.positionAt
import com.flappedear.betterdriver.timing.overlay.sessionMicrosAt
import com.flappedear.betterdriver.timing.overlay.totalMicros
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A session's video to watch in the app (FBBD-341): every clip back to back, as Video with data
 * cuts it, and the data the overlay draws. A composite lap's ([optimal], its plan, whose cuts are
 * [cuts] in the same order) overlay names the lap each part comes from over the composite's running
 * time, as its saved video does (FBBD-163, FBBD-178). Made by [VideoExports.watch].
 */
class WatchSource internal constructor(
    val cuts: List<VideoCut>,
    val video: SessionVideo,
    val data: OverlayData,
    val optimal: OptimalClipPlan? = null,
) {
    val totalMicros: Long = cuts.totalMicros()

    /** What the overlay shows at [at]; null before the first cut or past the last. */
    internal fun momentAt(at: CutPosition): HudMoment? {
        val sessionMicros = cuts.sessionMicrosAt(at) ?: return null
        val composite = optimal?.cuts?.getOrNull(at.index)
            ?.let { optimal.overlayAt(it, sessionMicros, runs = data.pointToPoint) }
        return HudMoment(sessionMicros, data.at(sessionMicros), composite)
    }
}

/** What the watch screen shows: the source being read, the source, or that it has no video to play. */
sealed interface WatchLoad {
    data object Loading : WatchLoad
    data object NoVideo : WatchLoad
    class Ready(val source: WatchSource) : WatchLoad
}

/**
 * Watch video (FBBD-341): a session's whole video with the data overlay of Video with data (FBBD-152,
 * Overlays' Tech HUD, FBBD-347) drawn live over the picture: speed, lap and delta where the session
 * has laps, G and the map with the car. A drive with no saved track (a commute) has no laps, so the
 * lap tile shows dashes and the map is the driven path. Play/Pause and a seek bar; the screen stays on while it plays.
 */
@Composable
fun WatchVideoScreen(title: String, load: WatchLoad, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        ShellTopBar("Watch video", onBack = onBack)
        Column(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title.isNotEmpty()) DimText(title)
            when (load) {
                WatchLoad.Loading -> {
                    DimText("Reading video and data…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                WatchLoad.NoVideo -> DimText("This session's video could not be read.", color = TrackTheme.colors.loss)
                is WatchLoad.Ready -> Watch(load.source)
            }
        }
    }
}

/** The picture takes the height left after the controls, so they stay on screen in landscape too. */
@Composable
private fun ColumnScope.Watch(source: WatchSource) {
    val context = LocalContext.current
    val w = remember(source) { WatchPlayer(context, source) }
    DisposableEffect(w) { onDispose { w.release() } }
    // Home, the lock button or another app: the video stops, as the lap review's does.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, w) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) w.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Follows the player every display frame only while it plays (FBBD-169: no frame loop when still).
    LaunchedEffect(w, w.playing) {
        while (w.playing) {
            withFrameNanos { }
            w.refresh()
        }
    }
    val view = LocalView.current
    DisposableEffect(view, w.playing) {
        view.keepScreenOn = w.playing
        onDispose { view.keepScreenOn = false }
    }
    val fonts = remember(context) { HudFonts.load(context) }
    val painter = remember(source) { TechHudPainter(fonts, source.data) }
    val moment = source.momentAt(w.at)
    Picture(w, painter, moment, Modifier.weight(1f, fill = false))
    Transport(w, source.totalMicros)
}

/** The video, letterboxed, with the data overlay (Overlays' Tech HUD, FBBD-347) over it as in the export. */
@Composable
private fun Picture(w: WatchPlayer, painter: TechHudPainter, moment: HudMoment?, modifier: Modifier) {
    Box(modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
        // As wide as it can be, else as tall as the space it has.
        Box(Modifier.aspectRatio(w.aspect)) {
            AndroidView(
                factory = { SurfaceView(it).also { v -> w.player.setVideoSurfaceView(v) } },
                onRelease = { v -> w.player.clearVideoSurfaceView(v) },
                modifier = Modifier.fillMaxSize(),
            )
            val described = Modifier.semantics {
                contentDescription = overlayDescription(moment?.frame, moment?.optimal)
            }
            Canvas(Modifier.fillMaxSize().then(described)) {
                val width = size.width.roundToInt()
                val height = size.height.roundToInt()
                if (moment == null || width <= 0 || height <= 0) return@Canvas
                val l = painter.layout(width, height)
                drawIntoCanvas { c ->
                    painter.draw(c.nativeCanvas, l, moment.sessionMicros, moment.frame, moment.optimal)
                }
            }
        }
    }
}

/** Play/Pause, where it is of how long, and the seek bar. */
@Composable
private fun Transport(w: WatchPlayer, totalMicros: Long) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging?.let { (it * totalMicros).roundToLong() } ?: w.outputMicros
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = w::playPause, modifier = Modifier.width(104.dp)) {
            Text(if (w.playing) "Pause" else "Play")
        }
        Text(
            "${clockText(shown)} / ${clockText(totalMicros)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = if (totalMicros > 0) (shown.toFloat() / totalMicros).coerceIn(0f, 1f) else 0f,
        onValueChange = { v ->
            dragging = v
            w.seek((v * totalMicros).roundToLong(), exact = false)
        },
        onValueChangeFinished = {
            dragging?.let { w.seek((it * totalMicros).roundToLong(), exact = true) }
            dragging = null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The player of a [WatchSource]: one clipped item per cut, played one after the other. [at] is
 * where it is, refreshed every frame while it plays and on every seek. Main thread only.
 */
@OptIn(UnstableApi::class)
private class WatchPlayer(context: Context, private val source: WatchSource) : Player.Listener {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    /** It plays, or will as soon as it can: Play/Pause follows this. */
    var playing by mutableStateOf(false)
        private set

    var at by mutableStateOf(CutPosition(0, 0))
        private set

    /** Width over height of the picture. */
    var aspect by mutableFloatStateOf(DefaultAspect)
        private set

    val outputMicros: Long get() = source.cuts.outputMicrosAt(at) ?: 0L

    init {
        val items = source.cuts.map { cut ->
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionUs(cut.fromMicros)
                .setEndPositionUs(cut.toMicros)
                .build()
            MediaItem.Builder()
                .setUri(Uri.fromFile(source.video.file(cut.clip)))
                .setClippingConfiguration(clipping)
                .build()
        }
        player.addListener(this)
        player.setSeekParameters(SeekParameters.EXACT)
        player.setMediaItems(items)
        player.prepare()
    }

    fun refresh() {
        at = CutPosition(player.currentMediaItemIndex, player.currentPosition * MicrosPerMilli)
    }

    fun playPause() {
        if (!player.playWhenReady && player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
        player.playWhenReady = !player.playWhenReady
    }

    /** Goes to [outputMicros] of the whole video: to the nearest key frame while dragging, exactly when let go. */
    fun seek(outputMicros: Long, exact: Boolean) {
        val p = source.cuts.positionAt(outputMicros) ?: return
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(p.index, p.intoMicros / MicrosPerMilli)
        at = p
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun release() {
        player.removeListener(this)
        player.release()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        playing = playWhenReady && player.playbackState != Player.STATE_ENDED
        refresh()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            player.playWhenReady = false
            playing = false
        }
        refresh()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) = refresh()

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.width > 0 && videoSize.height > 0) {
            aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        DebugLog.w(Tag, "playing the session's video failed", error)
        playing = false
    }
}

/**
 * What the overlay shows, in words, for TalkBack and the emulator tests: "Data: 54 km/h, lap 3 1:23.4";
 * on a composite lap ([optimal]), its caption and running time in place of the lap's:
 * "Data: 54 km/h, REALISTIC OPTIMAL · LAP 2 · CORNER 1 0:12.3".
 */
internal fun overlayDescription(frame: OverlayFrame?, optimal: OptimalOverlayText? = null): String {
    if (frame == null) return "Data: none"
    val speed = frame.speedKmh?.let { "${it.roundToInt()} km/h" } ?: "no speed"
    val lap = if (optimal != null) {
        ", ${optimal.captions.first()} ${formatRunningTime(optimal.theoreticalMicros / MicrosPerMilli)}"
    } else {
        frame.lap?.let { ", lap ${it.number} ${formatRunningTime(it.lapMicros / MicrosPerMilli)}" }.orEmpty()
    }
    return "Data: $speed$lap"
}

/** "4:05", "1:02:03": where the video is, to the second. */
internal fun clockText(micros: Long): String {
    val s = (micros.coerceAtLeast(0) / MicrosPerSecond).toInt()
    val h = s / SecondsPerHour
    val m = s / SecondsPerMinute % MinutesPerHour
    val sec = s % SecondsPerMinute
    return if (h > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
    } else {
        String.format(Locale.ROOT, "%d:%02d", m, sec)
    }
}

private const val Tag = "WatchVideo"
private const val DefaultAspect = 16f / 9f
private const val MicrosPerMilli = 1_000L
private const val MicrosPerSecond = 1_000_000L
private const val SecondsPerMinute = 60
private const val MinutesPerHour = 60
private const val SecondsPerHour = 3_600
