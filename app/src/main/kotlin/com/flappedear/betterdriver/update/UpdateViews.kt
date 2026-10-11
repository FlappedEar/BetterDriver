package com.flappedear.betterdriver.update

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.shell.DimText
import com.flappedear.betterdriver.shell.ListRow
import com.flappedear.betterdriver.shell.ShellCard
import com.flappedear.betterdriver.updater.UpdateState

private const val Percent = 100

/**
 * Tells the updater whether a session is recording, starting or not yet saved (FBBD-74), and runs
 * the daily check on launch and after each session. Until the recorder has answered, and while a
 * recovery pass may still find an interrupted session, it counts as recording.
 */
@Composable
fun FollowUpdates(updates: AppUpdates, recorder: RecorderClient, sessions: Sessions) {
    val busy = updatesBlocked(recorder, sessions)
    LaunchedEffect(busy) {
        updates.watch { blockedNow(recorder, sessions) }
        if (!busy) updates.checkIfDue()
    }
}

@Composable
fun updatesBlocked(recorder: RecorderClient, sessions: Sessions): Boolean = blockedNow(recorder, sessions)

/** Reads Compose state, so it is current from any thread; the worker checks it before each step. */
private fun blockedNow(recorder: RecorderClient, sessions: Sessions): Boolean {
    val r = recorder.state
    val unsaved = sessions.list.any { it.state == SessionState.RECORDING || it.state == SessionState.INTERRUPTED }
    return !r.connected || sessions.recovering || r.recording || r.starting || unsaved
}

/** A newer version waits, for the quiet dot on the Settings tab. */
fun hasUpdate(state: UpdateState) = state is UpdateState.Available || state is UpdateState.Ready

/** The Updates row on the App tab of settings. */
@Composable
fun UpdateRow(updates: AppUpdates, blocked: Boolean) {
    val activity = LocalActivity.current
    val state = updates.state
    when {
        !updates.enabled -> ListRow("Updates", "Release builds only")
        blocked -> ListRow("Updates", "After the session")
        else -> when (state) {
            UpdateState.Idle, UpdateState.Paused -> ListRow("Check for updates", onClick = updates::checkNow)
            UpdateState.Checking -> ListRow("Updates", "Checking…")
            UpdateState.UpToDate -> ListRow("Updates", "Up to date", onClick = updates::checkNow)
            is UpdateState.Available -> ListRow("Version ${state.release.version} available") {
                FilledTonalButton(onClick = updates::download) { Text("Download") }
            }
            is UpdateState.Downloading -> ListRow("Updates", downloadText(state))
            is UpdateState.Ready -> ListRow("Version ${state.release.version} ready") {
                FilledTonalButton(onClick = { activity?.let(updates::install) }) { Text("Install") }
            }
            is UpdateState.Failed -> {
                val retry = if (state.release != null) updates::download else updates::checkNow
                ListRow("Updates", "Try again", onClick = retry)
                DimText(state.message, color = TrackTheme.colors.warning)
            }
        }
    }
}

/**
 * A small card above the session list when a newer version waits, shown only once no session is
 * recording or unsaved. Nothing pops up (FBBD-74).
 */
@Composable
fun UpdateCard(updates: AppUpdates, blocked: Boolean, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    val state = updates.state
    if (blocked || !updates.enabled) return
    val install: () -> Unit = { activity?.let(updates::install) }
    val card: Triple<String, String?, (() -> Unit)?> = when (state) {
        is UpdateState.Available ->
            Triple("BetterDriver ${state.release.version} is available", "Download", updates::download)
        is UpdateState.Downloading -> Triple(downloadText(state), null, null)
        is UpdateState.Ready -> Triple("BetterDriver ${state.release.version} is ready to install", "Install", install)
        else -> return
    }
    val (text, label, action) = card
    ShellCard(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DimText(text, Modifier.weight(1f))
            if (label != null && action != null) FilledTonalButton(onClick = action) { Text(label) }
        }
    }
}

private fun downloadText(s: UpdateState.Downloading): String {
    val size = s.release.apk.size
    val pct = if (size > 0) (s.bytes * Percent / size).coerceIn(0, Percent.toLong()) else 0
    return "Downloading ${s.release.version} · $pct%"
}
