package com.flappedear.betterdriver.debug

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.flappedear.betterdriver.camera.CameraService
import com.flappedear.betterdriver.camera.CameraTestState
import com.flappedear.betterdriver.camera.CameraTestState.Status
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.shell.DimText
import com.flappedear.betterdriver.shell.ListRow
import com.flappedear.betterdriver.shell.SectionLabel
import com.flappedear.betterdriver.shell.ShellCard
import com.flappedear.betterdriver.shell.ShellTopBar
import com.flappedear.betterdriver.shell.allGranted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val PollMs = 1_000L
private const val MillisPerSecond = 1_000L
private const val SecondsPerMinute = 60L
private const val StaleMs = 60_000L
private const val MillisPerPart = CameraTestState.PartMinutes * SecondsPerMinute * MillisPerSecond

/**
 * Settings › Diagnostics › Camera test (FBBD-278, UI-150 A, UI-151 A, UI-152 A): 1080p · 60 for
 * 12 minutes with the MP4, then 12 without ([com.flappedear.betterdriver.camera.CameraTest], in the
 * camera's process). While it runs: the part, its progress and the camera's rate per part; at the
 * end both parts side by side, and Share log, where the full timing is.
 */
@Composable
fun CameraTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<CameraTestState?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // The end of a test is shown only to whoever watched it run; opened later, the page starts afresh.
    var watching by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            state = withContext(Dispatchers.IO) { CameraTestState.read(context) }?.let(::unlessStale)
            now = System.currentTimeMillis()
            if (state?.status == Status.Running) watching = true
            delay(PollMs)
        }
    }
    val current = state
    val running = current?.status == Status.Running
    KeepScreenOn(running)
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("Camera test", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                current != null && running -> Running(current, now) { stopTest(context) }
                current != null && watching -> Ended(current)
                else -> Start(starting = watching) {
                    watching = true
                    scope.launch {
                        withContext(Dispatchers.IO) { CameraTestState.clear(context) }
                        state = null
                        startTest(context)
                    }
                }
            }
        }
    }
}

@Composable
private fun Start(starting: Boolean, onStart: () -> Unit) {
    val context = LocalContext.current
    val camera = remember { context.allGranted(listOf(Manifest.permission.CAMERA)) }
    val sound = remember { context.allGranted(listOf(Manifest.permission.RECORD_AUDIO)) }
    val minutes = CameraTestState.PartMinutes
    DimText(
        "Records 1080p · 60 twice with the same settings, ${if (sound) "sound included" else "without sound"}. " +
            "Part 1 writes the MP4 as " +
            "normal. Part 2 runs both encoders but writes no file. Every step is timed in the log.",
    )
    ShellCard {
        ListRow("Part 1 · with MP4", "$minutes min", divider = false)
        ListRow("Part 2 · without MP4", "$minutes min")
        ListRow("Total", "${minutes * CameraTestState.Parts} min")
    }
    DimText(
        "Leave the phone in its mount, screen on. No session is recorded meanwhile; part 1's video is " +
            "deleted afterwards.",
    )
    if (!camera) DimText("The camera permission is off.", color = TrackTheme.colors.loss)
    // As in a session: without the microphone permission neither part records sound.
    if (!sound) DimText("The microphone permission is off, so neither part records sound.")
    Button(onClick = onStart, enabled = camera && !starting, modifier = Modifier.fillMaxWidth()) {
        Text(if (starting) "Starting…" else "Start test")
    }
}

@Composable
private fun Running(state: CameraTestState, now: Long, onStop: () -> Unit) {
    val left = (state.partEndsAtMillis - now).coerceIn(0, MillisPerPart)
    SectionLabel(
        "Part ${state.part} of ${CameraTestState.Parts} · ${if (state.withMp4) "with MP4" else "without MP4"}",
    )
    LinearProgressIndicator(
        progress = { 1f - left.toFloat() / MillisPerPart },
        modifier = Modifier.fillMaxWidth(),
    )
    val seconds = left / MillisPerSecond
    DimText("${seconds / SecondsPerMinute}:${(seconds % SecondsPerMinute).toString().padStart(2, '0')} left")
    ShellCard {
        state.first?.let {
            val label = if (state.part == 1) "Part 1 so far" else "Part 1 · with MP4"
            ListRow(label, divider = false, supporting = it.text)
        }
        if (state.part == 2) state.second?.let { ListRow("Part 2 so far", supporting = it.text) }
    }
    OutlinedButton(
        onClick = onStop,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, TrackTheme.colors.loss),
    ) {
        Text("Stop test", color = TrackTheme.colors.loss)
    }
}

@Composable
private fun Ended(state: CameraTestState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    SectionLabel(
        when (state.status) {
            Status.Done -> "Done"
            Status.Stopped -> "Stopped"
            else -> "Could not run"
        },
    )
    ShellCard {
        ListRow("Part 1 · with MP4", divider = false, supporting = state.first?.text ?: "no result")
        ListRow("Part 2 · without MP4", supporting = state.second?.text ?: "no result")
    }
    state.note?.let { DimText(it.replaceFirstChar(Char::uppercase) + ".") }
    DimText("The full timing is in the log. Share it from Diagnostics.")
    Button(onClick = {
        if (!busy) {
            busy = true
            problem = null
            scope.launch {
                val zip = try {
                    withContext(Dispatchers.IO) { DebugLogShare.prepare(context) }
                } catch (e: IOException) {
                    DebugLog.w("CameraTest", "could not prepare the log", e)
                    problem = "Could not prepare the log: ${e.message}"
                    null
                }
                busy = false
                zip?.let { DebugLogShare.share(context, it) }
            }
        }
    }, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "Preparing…" else "Share log")
    }
    problem?.let { DimText(it, color = TrackTheme.colors.loss) }
}

/** The screen stays on while the test runs, as the start page asks. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * A test still marked running a minute after its part should have ended: the camera's process ended
 * without finishing it (parts follow each other within seconds). Shown as stopped, so a new one can start.
 */
private fun unlessStale(state: CameraTestState): CameraTestState =
    if (state.status == Status.Running && System.currentTimeMillis() > state.partEndsAtMillis + StaleMs) {
        state.copy(status = Status.Stopped, note = "the test ended early")
    } else {
        state
    }

private fun startTest(context: Context) {
    try {
        ContextCompat.startForegroundService(
            context,
            Intent(context, CameraService::class.java).setAction(CameraService.ACTION_TEST),
        )
    } catch (e: IllegalStateException) {
        DebugLog.w("CameraTest", "camera test not started", e)
        CameraTestState.write(
            context,
            CameraTestState(Status.Failed, 1, note = "the camera service could not start"),
        )
    }
}

private fun stopTest(context: Context) {
    try {
        context.startService(Intent(context, CameraService::class.java).setAction(CameraService.ACTION_TEST_STOP))
    } catch (e: IllegalStateException) {
        DebugLog.w("CameraTest", "camera test not stopped", e)
    }
}
