package com.flappedear.betterdriver.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.format.formatDelta
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import com.flappedear.betterdriver.core.ui.format.formatRunningTime
import com.flappedear.betterdriver.core.ui.format.isDeltaGain
import com.flappedear.betterdriver.core.ui.numberStyle
import java.util.Locale

/** Review speeds, one tap apart: real time, half and quarter. */
private val Speeds = listOf(1.0, 0.5, 0.25)
private val SpeedLabels = listOf("1×", "½×", "¼×")

private const val MicrosPerMilli = 1_000L
private const val MicrosPerSecond = 1_000_000.0

/**
 * Two laps side by side, each with its own video (FBBD-93). Lap A's clock drives everything: lap B's
 * video plays where B was at the same place of the track, so both cars pass each corner together
 * and the delta shows how far apart they were in time. One cursor moves both; drag or tap the chart
 * to scrub. [wide] (the tablet) puts the videos side by side; a phone stacks them.
 */
@Composable
internal fun CompareView(c: Comparison, wide: Boolean, onChangeLaps: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pa = remember { LapVideoPlayer(context, muted = false) }
    val pb = remember { LapVideoPlayer(context, muted = true) }
    DisposableEffect(Unit) {
        onDispose {
            pa.release()
            pb.release()
        }
    }
    val st = remember(c) { ReviewTransport(c) }
    PauseWhenHidden { st.playing = false }
    LaunchedEffect(st) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                st.tick(if (last == 0L) 0L else now - last)
                last = now
                val t = st.cursor
                pa.follow(c.a.videoAt(t), c.a.video, st.speed, st.playing, st.scrubbing, now)
                val rateB = st.speed * c.bRateAt(t)
                pb.follow(c.b.videoAt(c.bMicrosAt(t)), c.b.video, rateB, st.playing, st.scrubbing, now)
            }
        }
    }
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        CompareBar(c, onChangeLaps)
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LapPane(c.a, "A", LapColors.a, pa, Modifier.weight(1f).fillMaxSize())
                LapPane(c.b, "B", LapColors.b, pb, Modifier.weight(1f).fillMaxSize())
            }
            TransportRow(c, st)
            Row(Modifier.fillMaxWidth().height(ChartHeight).padding(bottom = 12.dp)) {
                DeltaChart(c, st::cursorFraction, st.scrub, Modifier.weight(1f).fillMaxSize())
                Spacer(Modifier.width(12.dp))
                LapMap(c, st::cursor, Modifier.width(ChartHeight).fillMaxHeight())
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                LapPane(c.a, "A", LapColors.a, pa, Modifier.fillMaxWidth().height(PhoneVideoHeight))
                Spacer(Modifier.height(8.dp))
                LapPane(c.b, "B", LapColors.b, pb, Modifier.fillMaxWidth().height(PhoneVideoHeight))
                TransportRow(c, st)
                DeltaChart(c, st::cursorFraction, st.scrub, Modifier.fillMaxWidth().height(ChartHeight))
                Spacer(Modifier.height(12.dp))
                LapMap(c, st::cursor, Modifier.fillMaxWidth().height(ChartHeight).padding(bottom = 12.dp))
            }
        }
    }
}

/** The review cursor (a time in lap A), play state and speed. Compose state, changed on the main thread. */
private class ReviewTransport(private val c: Comparison) {
    var cursor by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var scrubbing by mutableStateOf(false)
    var speed by mutableDoubleStateOf(Speeds.first())

    private val length = c.a.trace.lengthMeters

    val scrub = Scrub(
        onMove = { f -> cursor = c.aMicrosAtDistance(f * length) },
        onStart = {
            playing = false
            scrubbing = true
        },
        onEnd = { scrubbing = false },
    )

    fun cursorFraction(): Float = (c.distanceAt(cursor) / length).toFloat()

    fun tick(elapsedNanos: Long) {
        if (!playing) return
        cursor = advance(cursor, elapsedNanos, speed, c.lengthMicros)
        if (cursor >= c.lengthMicros) playing = false
    }

    fun playPause() {
        if (cursor >= c.lengthMicros) cursor = 0
        playing = !playing
    }

    fun nextSpeed() {
        speed = Speeds[(Speeds.indexOf(speed) + 1) % Speeds.size]
    }
}

private val ChartHeight = 180.dp
private val PhoneVideoHeight = 200.dp

/** Pauses when the app goes to the background, so video never plays unseen. */
@Composable
private fun PauseWhenHidden(onPause: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) onPause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun CompareBar(c: Comparison, onChangeLaps: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${lapName(c.a)}  vs  ${lapName(c.b)}",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChangeLaps) { Text("Change laps") }
    }
}

/** "Lap 7 · 1:41.89". */
internal fun lapName(l: ReviewLap) = "Lap ${l.number} · ${formatLapTime(l.timeMillis)}"

@Composable
private fun LapPane(l: ReviewLap, letter: String, color: Color, player: LapVideoPlayer, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("$letter  ${lapName(l)}", style = MaterialTheme.typography.labelLarge, color = color)
            Spacer(Modifier.width(8.dp))
            Text(
                l.sessionTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LapVideoView(player, l.hasVideo, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)))
    }
}

@Composable
private fun TransportRow(c: Comparison, st: ReviewTransport) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(onClick = st::playPause, modifier = Modifier.width(104.dp)) {
            Text(if (st.playing) "Pause" else "Play")
        }
        TextButton(onClick = st::nextSpeed) { Text(SpeedLabels[Speeds.indexOf(st.speed)]) }
        Readout(c, st::cursor, Modifier.weight(1f))
    }
}

/** Both laps' clocks, speeds and the delta at the cursor. Reads [cursor] here only, so only this row recomposes. */
@Composable
private fun Readout(c: Comparison, cursor: () -> Long, modifier: Modifier) {
    val t = cursor()
    val tb = c.bMicrosAt(t)
    val delta = c.alignment.deltaMicrosAt(t) / MicrosPerSecond
    val colors = TrackTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Clock("A", t, c.a.trace.at(t).speedKmh, LapColors.a)
        Clock("B", tb, c.b.trace.at(tb).speedKmh, LapColors.b)
        Spacer(Modifier.weight(1f))
        Text(
            formatDelta(delta),
            style = numberStyle(32.dp),
            color = if (isDeltaGain(delta)) colors.gain else colors.loss,
            modifier = Modifier.background(colors.surface, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
        )
    }
}

@Composable
private fun Clock(letter: String, lapMicros: Long, speedKmh: Double?, color: Color) {
    Column {
        Text("$letter  ${formatRunningTime(lapMicros / MicrosPerMilli)}", style = numberStyle(18.dp), color = color)
        Text(
            speedKmh?.let { String.format(Locale.ROOT, "%.0f km/h", it) } ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
