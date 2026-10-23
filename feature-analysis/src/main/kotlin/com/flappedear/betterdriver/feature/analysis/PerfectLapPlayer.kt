package com.flappedear.betterdriver.feature.analysis

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flappedear.betterdriver.analysis.line.PerfectLapScene
import com.flappedear.betterdriver.core.ui.TrackTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Test tags: the button on "Where to find time" (UI-184 A) and the player. */
const val PerfectLapButtonTag = "perfect-lap-3d"
const val PerfectLapPlayerTag = "perfect-lap-player"

/** The picture is 16:9, as the saved video. */
private const val PictureAspect = 16f / 9f

private const val NanosPerSecond = 1e9

/** The player's two speeds: as driven, and half (to see where the pedals change). */
private val Speeds = listOf(1.0 to "1×", 0.5 to "½×")

/** Where the video went: [inMovies] false on Android 8 and 9, where it stays in the app's own folder, to send. */
internal data class SavedVideo(val uri: Uri, val inMovies: Boolean)

/** What saving the video is doing. */
private sealed interface Saving {
    data object Idle : Saving

    data class Running(val progress: Float) : Saving

    data class Saved(val video: SavedVideo) : Saving

    data class Failed(val why: String) : Saving
}

/**
 * The perfect lap in 3D (FBBD-308, round 44): the perfect lap driven from above each corner
 * (UI-181 C), its line coloured by the pedals (UI-182 A), the best lap as a see-through car at its
 * own speed (UI-183 C); play, pause, half speed, corner to corner, and Save video (UI-180 A).
 * Opened full screen from "Where to find time"; [onClose] closes it.
 */
@Composable
internal fun PerfectLapPlayer(scene: PerfectLapScene, startCorner: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val c = TrackTheme.colors
        val palette = ScenePalette(
            ground = GroundColor, road = c.surface.toArgb(), edge = c.textDim.toArgb(),
            braking = c.loss.toArgb(), coasting = c.textDim.toArgb(), accelerating = c.gain.toArgb(),
            car = MaterialTheme.colorScheme.primary.toArgb(), text = c.text.toArgb(), textDim = c.textDim.toArgb(),
            panel = PanelColor,
        )
        val painter = remember(scene, palette) { PerfectLapPainter(scene, palette) }
        var seconds by remember(scene) { mutableDoubleStateOf(scene.corners[startCorner].startSeconds) }
        var playing by remember { mutableStateOf(true) }
        var speed by remember { mutableDoubleStateOf(1.0) }
        LaunchedEffect(playing, speed) {
            if (!playing) return@LaunchedEffect
            var last = withFrameNanos { it }
            while (playing) {
                val now = withFrameNanos { it }
                seconds = (seconds + (now - last) / NanosPerSecond * speed).coerceAtMost(scene.durationSeconds)
                last = now
                if (seconds >= scene.durationSeconds) playing = false
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
                .testTag(PerfectLapPlayerTag),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("‹ Back") }
                Text("Perfect lap in 3D", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Dim("${lineLapTime(scene.perfectSeconds)} · your best lap ${lineLapTime(scene.bestSeconds)}")
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val wide = maxWidth / maxHeight > PictureAspect
                val fit = if (wide) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()
                Canvas(fit.aspectRatio(PictureAspect, matchHeightConstraintsFirst = wide)) {
                    if (size.width >= 1f && size.height >= 1f) {
                        drawIntoCanvas {
                            painter.draw(it.nativeCanvas, seconds, size.width.toInt(), size.height.toInt())
                        }
                    }
                }
            }
            Slider(
                value = seconds.toFloat(),
                onValueChange = { seconds = it.toDouble() },
                valueRange = 0f..scene.durationSeconds.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            CornerTicks(scene, seconds)
            Controls(scene, painter, seconds, playing, speed, { seconds = it }, { playing = it }, { speed = it })
        }
    }
}

/** The corners under the slider, at their place in the lap; the current one in the accent. */
@Composable
private fun CornerTicks(scene: PerfectLapScene, seconds: Double) {
    val at = scene.cornerAt(seconds)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth
        scene.corners.forEachIndexed { i, corner ->
            val x = w * ((corner.startSeconds / scene.durationSeconds).toFloat())
            Text(
                cornerTag(corner.name),
                Modifier.padding(start = x),
                color = if (i == at) MaterialTheme.colorScheme.primary else TrackTheme.colors.textDim,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Suppress("LongParameterList") // The player's state and its setters, kept in the player.
@Composable
private fun Controls(
    scene: PerfectLapScene,
    painter: PerfectLapPainter,
    seconds: Double,
    playing: Boolean,
    speed: Double,
    seek: (Double) -> Unit,
    play: (Boolean) -> Unit,
    setSpeed: (Double) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf<Saving>(Saving.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    val at = scene.cornerAt(seconds)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { seek(scene.corners[(at - 1).coerceAtLeast(0)].startSeconds) }) { Text("‹") }
        Button(onClick = {
            if (!playing && seconds >= scene.durationSeconds) seek(0.0)
            play(!playing)
        }) { Text(if (playing) "Pause" else "Play") }
        OutlinedButton(onClick = { seek(scene.corners[(at + 1).coerceAtMost(scene.corners.lastIndex)].startSeconds) }) {
            Text("›")
        }
        Spacer(Modifier.width(4.dp))
        for ((value, label) in Speeds) {
            FilterChip(selected = speed == value, onClick = { setSpeed(value) }, label = { Text(label) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (val s = saving) {
            is Saving.Running -> {
                Column(Modifier.weight(1f)) {
                    Dim("Making the video…")
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = { job?.cancel() }) { Text("Stop") }
            }
            else -> {
                OutlinedButton(onClick = {
                    play(false)
                    job = scope.launch { saving = save(context, scene, painter) { saving = it } }
                }) { Text("Save video") }
                if (s is Saving.Saved) {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
                            .putExtra(Intent.EXTRA_STREAM, s.video.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "Send the perfect lap"))
                    }) { Text("Send") }
                }
            }
        }
    }
    when (val s = saving) {
        is Saving.Saved ->
            Dim(if (s.video.inMovies) "Saved to Movies/BetterDriver." else "Saved in the app. Send it to keep it.")
        is Saving.Failed -> Dim("The video was not saved: ${s.why}", color = TrackTheme.colors.loss)
        else -> Unit
    }
}

/** Makes and saves the video, saying how far it got through [update]; what it ended as. */
private suspend fun save(
    context: Context,
    scene: PerfectLapScene,
    painter: PerfectLapPainter,
    update: (Saving) -> Unit,
): Saving {
    update(Saving.Running(0f))
    return try {
        val video = withContext(Dispatchers.Default) {
            // Its own painter: the player keeps drawing with the other while the video is made.
            val name = "perfect-lap-${System.currentTimeMillis()}"
            savePerfectLapVideo(context, scene, painter.copy(), name) { update(Saving.Running(it)) }
        }
        Saving.Saved(video)
    } catch (e: CancellationException) {
        update(Saving.Idle)
        throw e
    } catch (e: IOException) {
        Saving.Failed(e.message ?: "the video could not be written")
    } catch (e: IllegalStateException) {
        Saving.Failed(e.message ?: "the phone's video encoder refused")
    } catch (e: IllegalArgumentException) {
        Saving.Failed(e.message ?: "the phone's video encoder refused this size")
    }
}

/** The grass round the road, and the panels behind the picture's text, as ARGB. */
private const val GroundColor = 0xFF14231A.toInt()
private const val PanelColor = 0xB8050709.toInt()
