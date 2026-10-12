package com.flappedear.betterdriver.feature.analysis

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * The one player of the Corner laps screen (FBBD-153): it plays one lap's pass through the corner
 * at a time, as a clipped media item, so it starts at the pass and stops at the corner exit by
 * itself. Use from the main thread only.
 */
internal class CornerPlayer(context: Context) {
    private val player: ExoPlayer = ExoPlayer.Builder(context).build()

    /** The pass loaded, or null before the first. */
    var video by mutableStateOf<CornerVideoUi?>(null)
        private set

    /** Paused by a tap, or not yet started. */
    var paused by mutableStateOf(true)
        private set

    /** The pass played to the corner exit. */
    var ended by mutableStateOf(false)
        private set

    /** Width over height of the picture. */
    var aspect by mutableFloatStateOf(DefaultAspect)
        private set

    init {
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                ended = playbackState == Player.STATE_ENDED
            }
        })
    }

    /** Plays [v] from its start; the pass played before stops. */
    fun play(v: CornerVideoUi) {
        if (v != video) {
            video = v
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(v.startMillis)
                .setEndPositionMs(v.endMillis)
                .build()
            player.setMediaItem(
                MediaItem.Builder().setUri(Uri.fromFile(File(v.path))).setClippingConfiguration(clipping).build(),
            )
            player.prepare()
        } else {
            player.seekTo(0)
        }
        player.playWhenReady = true
    }

    /** Pauses, resumes, or plays the pass again once it has ended. */
    fun toggle() {
        val v = video ?: return
        when {
            ended -> play(v)
            player.playWhenReady -> player.playWhenReady = false
            else -> player.playWhenReady = true
        }
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun attach(view: SurfaceView) = player.setVideoSurfaceView(view)

    fun detach(view: SurfaceView) = player.clearVideoSurfaceView(view)

    fun release() = player.release()

    private companion object {
        const val DefaultAspect = 16f / 9f
    }
}

/** The screen's player, released when the screen goes and paused when the app is left. */
@Composable
internal fun rememberCornerPlayer(): CornerPlayer {
    val context = LocalContext.current
    val player = remember { CornerPlayer(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    return player
}

/**
 * [video] in [player]'s picture, letterboxed: it starts playing when shown, and a tap pauses,
 * resumes or plays it again.
 */
@Composable
internal fun CornerClipView(player: CornerPlayer, video: CornerVideoUi, modifier: Modifier = Modifier) {
    LaunchedEffect(video) { if (player.video != video) player.play(video) }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { SurfaceView(it).also(player::attach) },
            onRelease = player::detach,
            modifier = Modifier.aspectRatio(player.aspect),
        )
        // Over the picture, so the tap never reaches the SurfaceView.
        Box(Modifier.matchParentSize().clickable { player.toggle() })
        val label = when {
            player.video != video -> null
            player.ended -> "Again"
            player.paused -> "Play"
            else -> null
        }
        label?.let {
            Text(
                it,
                Modifier
                    .background(Color.Black.copy(alpha = OverlayAlpha), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private const val OverlayAlpha = 0.6f
