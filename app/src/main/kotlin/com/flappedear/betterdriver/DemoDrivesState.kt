package com.flappedear.betterdriver

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.DemoReplay
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.sessionanalysis.sessionLabel
import com.flappedear.betterdriver.shell.DemoDrive
import com.flappedear.betterdriver.shell.DemoDrives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Demo track's "Drives" choice (FBBD-235): the demo circuit and every session on the phone that
 * is not recording, newest first, labelled by date and track. Null until the list is read.
 */
@Composable
internal fun rememberDemoDrives(): DemoDrives? {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(DemoReplay.sessionId(context)) }
    val sessions by produceState<List<DemoDrive>?>(null) {
        value = withContext(Dispatchers.IO) { sessionDrives(context) }
    }
    val list = sessions ?: return null
    val choices = listOf(DemoDrives.Circuit) + list
    return DemoDrives(
        current = choices.firstOrNull { it.sessionId == chosen } ?: DemoDrives.Circuit,
        choices = choices,
        onChoose = { d ->
            DemoReplay.choose(context, d.sessionId)
            chosen = d.sessionId
        },
    )
}

private fun sessionDrives(context: Context): List<DemoDrive> =
    SessionStore(sessionsDir(context)).list()
        .filter { it.state != SessionState.RECORDING }
        .sortedByDescending { it.info.startUtcMillis }
        .map { s ->
            val label = sessionLabel(s.info.startUtcMillis)
            DemoDrive(s.id, if (s.info.track.isBlank()) label else "$label · ${s.info.track}")
        }
