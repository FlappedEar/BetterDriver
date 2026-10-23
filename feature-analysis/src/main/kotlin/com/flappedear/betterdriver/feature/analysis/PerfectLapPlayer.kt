package com.flappedear.betterdriver.feature.analysis

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * The perfect lap in 3D (FBBD-308, round 44; FBBD-316, round 47): the perfect lap followed from
 * behind and above the car (UI-200 A) over the satellite photo of [LocalPerfectLapGround]
 * (UI-201 B, drawn road without one), its line a car-wide band coloured by the pedals with BRAKE
 * and GAS bars across the road (UI-202 C), the best lap as a see-through car at its own speed
 * (UI-183 C); play, pause, half speed and corner to corner in one bar on the picture (UI-204 A),
 * and Save video (UI-180 A). Opened full screen from "Where to find time"; [onClose] closes it.
 */
@Composable
internal fun PerfectLapPlayer(scene: PerfectLapScene, startCorner: Int, onClose: () -> Unit) {
    val ground = LocalPerfectLapGround.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val c = TrackTheme.colors
        val palette = ScenePalette(
            ground = GrassColor, road = RoadColor, edge = EdgeColor,
            braking = c.loss.toArgb(), coasting = c.textDim.toArgb(), accelerating = c.gain.toArgb(),
            car = MaterialTheme.colorScheme.primary.toArgb(), text = c.text.toArgb(), textDim = c.textDim.toArgb(),
            panel = PanelColor,
        )
        val painter = remember(scene, palette, ground) {
            PerfectLapPainter(scene, palette, ground?.let { g -> g::tile }, ground?.attribution)
        }
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text("‹ Back") }
                Column(Modifier.weight(1f)) {
                    Text("Perfect lap in 3D", style = MaterialTheme.typography.titleLarge)
                    Dim("${lineLapTime(scene.perfectSeconds)} · your best lap ${lineLapTime(scene.bestSeconds)}")
                }
                // Its own painter, fetching its map tiles itself: the player keeps drawing with the other.
                val videoPainter = remember(painter, ground) { painter.copy(ground?.let { g -> g::load }) }
                SaveVideo(scene, videoPainter) { playing = false }
            }
            Picture(painter, ground, seconds, Modifier.weight(1f).fillMaxWidth()) { bar ->
                PlayerBar(
                    scene, seconds, playing, speed,
                    seek = { seconds = it },
                    play = {
                        if (it && seconds >= scene.durationSeconds) seconds = 0.0
                        playing = it
                    },
                    setSpeed = { speed = it },
                    modifier = bar,
                )
            }
        }
    }
}

/** The 16:9 picture at [seconds], as large as fits, with [controls] on it at the bottom (UI-204 A). */
@Composable
private fun Picture(
    painter: PerfectLapPainter,
    ground: PerfectLapGround?,
    seconds: Double,
    modifier: Modifier,
    controls: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val wide = maxWidth / maxHeight > PictureAspect
        val fit = if (wide) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()
        Box(fit.aspectRatio(PictureAspect, matchHeightConstraintsFirst = wide)) {
            val barPx = with(LocalDensity.current) { (BarHeight + BarMargin * 2).toPx() }
            // Read here so the picture is drawn again as each map tile arrives.
            val tilesLoaded = ground?.loaded ?: 0
            Canvas(Modifier.fillMaxSize()) {
                if (tilesLoaded >= 0 && size.width >= 1f && size.height >= 1f) {
                    drawIntoCanvas {
                        painter.draw(it.nativeCanvas, seconds, size.width.toInt(), size.height.toInt(), barPx)
                    }
                }
            }
            controls(Modifier.align(Alignment.BottomCenter).padding(BarMargin))
        }
    }
}

/**
 * The player's controls in one bar on the picture (UI-204 A), as in Arek's reference: speed,
 * previous corner, play, next corner, the time, the lap to drag with a mark at each corner, and
 * the lap time.
 */
@Suppress("LongParameterList") // The player's state and its setters, kept in the player.
@Composable
private fun PlayerBar(
    scene: PerfectLapScene,
    seconds: Double,
    playing: Boolean,
    speed: Double,
    seek: (Double) -> Unit,
    play: (Boolean) -> Unit,
    setSpeed: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val at = scene.cornerAt(seconds)
    val next = Speeds.firstOrNull { it.first != speed }?.first ?: Speeds.first().first
    val onBar = Color(BarTextColor)
    Row(
        modifier
            .widthIn(max = BarMaxWidth)
            .fillMaxWidth()
            .height(BarHeight)
            .background(Color(PanelColor), RoundedCornerShape(BarHeight / 2))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Shows the speed now; a tap switches to the other.
        TextButton(onClick = { setSpeed(next) }) {
            Text(Speeds.first { it.first == speed }.second, color = onBar, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { seek(scene.corners[(at - 1).coerceAtLeast(0)].startSeconds) }) {
            Text("⏮", color = onBar)
        }
        FilledIconButton(onClick = { play(!playing) }, modifier = Modifier.semantics {
            contentDescription = if (playing) "Pause" else "Play"
        }) {
            Text(if (playing) "❚❚" else "▶", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { seek(scene.corners[(at + 1).coerceAtMost(scene.corners.lastIndex)].startSeconds) }) {
            Text("⏭", color = onBar)
        }
        Text(lineLapTime(seconds), color = onBar, style = MaterialTheme.typography.labelLarge)
        Column(Modifier.weight(1f)) {
            Slider(
                value = seconds.toFloat(),
                onValueChange = { seek(it.toDouble()) },
                valueRange = 0f..scene.durationSeconds.toFloat(),
                modifier = Modifier.fillMaxWidth().height(24.dp),
            )
            CornerTicks(scene, at)
        }
        Text(
            lineLapTime(scene.durationSeconds),
            color = onBar.copy(alpha = DimAlpha),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** The corners under the lap, at their place in it; the current one in the accent. */
@Composable
private fun CornerTicks(scene: PerfectLapScene, at: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth
        scene.corners.forEachIndexed { i, corner ->
            val x = w * ((corner.startSeconds / scene.durationSeconds).toFloat())
            Text(
                cornerTag(corner.name),
                Modifier.padding(start = x),
                color = if (i == at) MaterialTheme.colorScheme.primary else Color(BarTextColor).copy(alpha = DimAlpha),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Save video in the header (UI-204 A), with its progress, and Send once saved. [pause] stops the player first. */
@Composable
private fun SaveVideo(scene: PerfectLapScene, painter: PerfectLapPainter, pause: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf<Saving>(Saving.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    Column(horizontalAlignment = Alignment.End) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (val s = saving) {
                is Saving.Running -> {
                    Column(Modifier.width(160.dp)) {
                        Dim("Making the video…")
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { job?.cancel() }) { Text("Stop") }
                }
                else -> {
                    if (s is Saving.Saved) {
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
                                .putExtra(Intent.EXTRA_STREAM, s.video.uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "Send the perfect lap"))
                        }) { Text("Send") }
                    }
                    OutlinedButton(onClick = {
                        pause()
                        job = scope.launch { saving = save(context, scene, painter) { saving = it } }
                    }) { Text("Save video") }
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
            val name = "perfect-lap-${System.currentTimeMillis()}"
            savePerfectLapVideo(context, scene, painter, name) { update(Saving.Running(it)) }
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

/** The drawn track where there is no photo (UI-201 A), and the panels behind the picture's text, as ARGB. */
private const val GrassColor = 0xFF2F5E36.toInt()
private const val RoadColor = 0xFF3B3F43.toInt()
private const val EdgeColor = 0xFFF2F4F5.toInt()
private const val PanelColor = 0xC8050709.toInt()
private const val BarTextColor = 0xFFFFFFFF.toInt()
private const val DimAlpha = 0.7f
private val BarHeight = 64.dp
private val BarMargin = 12.dp
private val BarMaxWidth = 900.dp
