package com.flappedear.betterdriver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.flappedear.betterdriver.core.ble.obd.ObdLeScan
import com.flappedear.betterdriver.core.ble.obd.ObdLink
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.obdTargets
import com.flappedear.betterdriver.shell.ObdScreen
import com.flappedear.betterdriver.shell.ObdView
import kotlinx.coroutines.delay

private const val RefreshMs = 500L

/**
 * The OBD-II page (FBBD-261, UI-104 B). While a session records it shows the recorder's OBD
 * connection. Otherwise it opens its own [ObdLink] to the adapter while it is shown, and lets
 * go as soon as a session starts: the adapter takes one connection at a time.
 */
@Composable
internal fun ObdRoute(recorder: RecorderClient, onBack: () -> Unit) {
    val context = LocalContext.current
    val recording = recorder.state.recording || recorder.state.starting
    val link = remember(recording) {
        if (recording) {
            null
        } else {
            val app = context.applicationContext
            ObdLink(targets = { obdTargets(app) }, owner = "page", fallback = ObdLeScan(app)::targets)
        }
    }
    DisposableEffect(link) {
        link?.start()
        onDispose { link?.stop() }
    }
    var view by remember { mutableStateOf(ObdView(recording, link?.status())) }
    LaunchedEffect(link, recording) {
        while (true) {
            view = ObdView(recording, if (link != null) link.status() else recorder.state.obd)
            delay(RefreshMs)
        }
    }
    ObdScreen(view, onBack)
}
