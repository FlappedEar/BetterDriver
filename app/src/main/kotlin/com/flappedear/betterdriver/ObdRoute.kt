package com.flappedear.betterdriver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.flappedear.betterdriver.core.ble.obd.ObdLink
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.obdAdapterLabel
import com.flappedear.betterdriver.recording.obdTargets
import com.flappedear.betterdriver.recording.obdWatch
import com.flappedear.betterdriver.shell.ObdScreen
import com.flappedear.betterdriver.shell.ObdView
import com.flappedear.betterdriver.shell.SetupRepo
import kotlinx.coroutines.delay

private const val RefreshMs = 500L

/**
 * The OBD-II page (FBBD-261, UI-104 B). While a session records it shows the recorder's OBD
 * connection. Otherwise it opens its own [ObdLink] to the listed adapters (FBBD-121, UI-105 C)
 * while it is shown, and lets go as soon as a session starts: the adapter takes one connection at
 * a time. With none listed it connects to nothing.
 */
@Composable
internal fun ObdRoute(recorder: RecorderClient, onBack: () -> Unit) {
    val context = LocalContext.current
    val recording = recorder.state.recording || recorder.state.starting
    val repo = SetupRepo.get(context)
    val carData = (if (recording) repo.forSession() else repo.setup).active().carData
    val adapter = remember(carData) { obdAdapterLabel(context, carData.firstOrNull()) }
    val link = remember(recording, carData) {
        if (recording || carData.isEmpty()) {
            null
        } else {
            val app = context.applicationContext
            ObdLink(targets = { obdTargets(app, carData) }, owner = "page", watch = { obdWatch(app, it) })
        }
    }
    DisposableEffect(link) {
        link?.start()
        onDispose { link?.stop() }
    }
    var view by remember { mutableStateOf(ObdView(recording, link?.status(), adapter)) }
    LaunchedEffect(link, recording, adapter) {
        while (true) {
            view = ObdView(recording, if (link != null) link.status() else recorder.state.obd, adapter)
            delay(RefreshMs)
        }
    }
    ObdScreen(view, onBack)
}
