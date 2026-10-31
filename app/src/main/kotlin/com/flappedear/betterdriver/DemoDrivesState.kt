package com.flappedear.betterdriver

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.flappedear.betterdriver.analysis.demo.DemoSession
import com.flappedear.betterdriver.analysis.demo.DemoTrack
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
 * The Demo track's "Drives" choice (FBBD-235): the shared list ([DemoTrack.choices], as on the iPhone,
 * FBBD-373) of the demo circuit, Arek's Silesia Ring and Jastrząb laps built into the app and every
 * session on the phone that is not recording, newest first, labelled by date and track. Null until the
 * list is read.
 */
@Composable
internal fun rememberDemoDrives(): DemoDrives? {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(DemoReplay.sessionId(context)) }
    val choices by produceState<List<DemoDrive>?>(null) {
        value = withContext(Dispatchers.IO) { demoChoices(context) }
    }
    val list = choices ?: return null
    return DemoDrives(
        current = list.firstOrNull { it.sessionId == chosen } ?: DemoDrives.Circuit,
        choices = list,
        onChoose = { d ->
            DemoReplay.choose(context, d.sessionId)
            chosen = d.sessionId
        },
    )
}

private fun demoChoices(context: Context): List<DemoDrive> {
    val sessions = SessionStore(sessionsDir(context)).list().map {
        DemoSession(it.id, it.info.startUtcMillis, it.info.track, it.state == SessionState.RECORDING)
    }
    return DemoTrack.choices(sessions, ::sessionLabel).map { DemoDrive(it.id, it.label) }
}
