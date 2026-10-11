package com.flappedear.betterdriver.feature.review

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.io.File
import kotlin.math.abs

/**
 * One lap's video (FBBD-93): an ExoPlayer that follows a target frame given every display frame
 * ([follow]). While scrubbing it seeks to the nearest key frame, which keeps dragging smooth; when
 * the finger lifts it seeks to the exact frame. Use from the main thread only.
 */
@OptIn(UnstableApi::class)
internal class LapVideoPlayer(context: Context, muted: Boolean) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        if (muted) volume = 0f
        setSeekParameters(SeekParameters.EXACT)
    }

    /** There is video at the current place of the lap. */
    var showing by mutableStateOf(false)
        private set

    /** Width over height of the picture. */
    var aspect by mutableFloatStateOf(DefaultAspect)
        private set

    private var file: File? = null
    private var lastSeekMillis = Long.MIN_VALUE
    private var lastSeekNanos = 0L
    private var speed = 1f
    private var exact = true

    init {
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
        })
    }

    /**
     * Moves the player towards [target] of [video]. [rate] is how fast the target moves against real
     * time; [nowNanos] is the frame time, used to space out seeks.
     */
    @Suppress("LongParameterList") // One call per display frame with everything it needs.
    fun follow(
        target: VideoTarget?,
        video: SessionVideo?,
        rate: Double,
        playing: Boolean,
        scrubbing: Boolean,
        nowNanos: Long,
    ) {
        showing = target != null && video != null
        if (target == null || video == null) {
            player.playWhenReady = false
            return
        }
        val f = video.file(target.clip)
        if (f != file) {
            file = f
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)), target.mediaMillis)
            player.prepare()
            lastSeekMillis = target.mediaMillis
            lastSeekNanos = nowNanos
        }
        precise(!scrubbing)
        val step = followStep(player.currentPosition, target.mediaMillis, rate, playing)
        val gap = if (playing) PlayingSeekGapNanos else ScrubSeekGapNanos
        step.seekMillis?.let { ms ->
            if (abs(ms - lastSeekMillis) > PausedToleranceMillis && nowNanos - lastSeekNanos >= gap) {
                player.seekTo(ms)
                lastSeekMillis = ms
                lastSeekNanos = nowNanos
            }
        }
        if (step.speed != speed) {
            speed = step.speed
            player.setPlaybackSpeed(speed)
        }
        if (player.playWhenReady != playing) player.playWhenReady = playing
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun release() = player.release()

    /** Exact seeks when still, key-frame seeks while scrubbing; going exact seeks the frame again. */
    private fun precise(on: Boolean) {
        if (on == exact) return
        exact = on
        player.setSeekParameters(if (on) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        if (on) lastSeekMillis = Long.MIN_VALUE
    }

    private companion object {
        const val DefaultAspect = 16f / 9f
        const val PlayingSeekGapNanos = 300_000_000L
        const val ScrubSeekGapNanos = 50_000_000L
    }
}

/** The picture of [video], letterboxed in the space it gets, with a note where the lap has none. */
@Composable
internal fun LapVideoView(video: LapVideoPlayer, hasVideo: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { SurfaceView(it).also { v -> video.player.setVideoSurfaceView(v) } },
            onRelease = { v -> video.player.clearVideoSurfaceView(v) },
            modifier = Modifier.aspectRatio(video.aspect),
        )
        if (!video.showing) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text(
                    if (hasVideo) "No video here" else "No video for this lap",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
