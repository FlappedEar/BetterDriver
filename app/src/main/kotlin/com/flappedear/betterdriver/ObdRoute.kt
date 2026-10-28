package com.flappedear.betterdriver

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.flappedear.betterdriver.core.ble.obd.ObdLink
import com.flappedear.betterdriver.obd.CarProfile as ObdCarProfile
import com.flappedear.betterdriver.obd.CarScan
import com.flappedear.betterdriver.obd.ObdStatus
import com.flappedear.betterdriver.obd.ownProfile
import com.flappedear.betterdriver.obd.toRecord
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.obdAdapterLabel
import com.flappedear.betterdriver.recording.obdTargets
import com.flappedear.betterdriver.recording.obdWatch
import com.flappedear.betterdriver.setup.CarParameter
import com.flappedear.betterdriver.setup.CarProfile
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.shell.ObdScreen
import com.flappedear.betterdriver.shell.ObdView
import com.flappedear.betterdriver.shell.SetupRepo
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

private const val RefreshMs = 500L

/**
 * The car data page (FBBD-261, UI-104 B), titled with the next session's car (UI-134 B), or "Car
 * data" when none is chosen. Its view comes from [rememberObdView].
 */
@Composable
internal fun ObdRoute(recorder: RecorderClient, onBack: () -> Unit) {
    val repo = SetupRepo.get(LocalContext.current)
    val recording = recorder.state.recording || recorder.state.starting
    val car = (if (recording) repo.forSession() else repo.setup).active().car
    ObdScreen(rememberObdView(recorder), car?.name ?: "Car data", onBack)
}

/**
 * What the car data page and the car's page show (FBBD-261, UI-133 B). While a session records it
 * shows the recorder's OBD connection. Otherwise it opens its own [ObdLink] to the listed adapters (FBBD-121, UI-105 C)
 * while it is shown, and lets go as soon as a session starts: the adapter takes one connection at
 * a time. With none listed it connects to nothing.
 */
@Composable
internal fun rememberObdView(recorder: RecorderClient): ObdView = rememberCarLink(recorder, car = null).view

/**
 * The page's connection to [car], the next session's car (FBBD-344): what [rememberObdView] shows,
 * reading the car's own parameters too, and with [draft], a parameter being added, as one more
 * ([DraftKey]); a new draft connects again so it is read. [CarLink.scan] scans what the car offers
 * (UI-303 B), and the result is stored with the car.
 */
@Composable
internal fun rememberCarLink(recorder: RecorderClient, car: CarProfile?, draft: CarParameter? = null): CarLink {
    val context = LocalContext.current
    val recording = recorder.state.recording || recorder.state.starting
    val repo = SetupRepo.get(context)
    val carData = (if (recording) repo.forSession() else repo.setup).active().carData
    val adapter = remember(carData) { obdAdapterLabel(context, carData.firstOrNull()) }
    val profile by rememberUpdatedState(ownProfile(car?.let { c -> draft?.let { c.withParameter(it) } ?: c }))
    val run = remember { ScanRun() }
    val link = rememberPageLink(recording, carData, { profile }, run.asked::get)
    LaunchedEffect(link, draft) {
        if (draft != null) link?.restart()
    }
    var view by remember { mutableStateOf(ObdView(recording, link?.status(), adapter)) }
    val carId = car?.id
    LaunchedEffect(link, recording, adapter, carId) {
        while (true) {
            val status = if (link != null) link.status() else recorder.state.obd
            view = ObdView(recording, status, adapter)
            run.check(status) { scan -> if (carId != null) saveScan(repo, carId, scan) }
            delay(RefreshMs)
        }
    }
    val scan = if (link == null || car == null) null else ({ run.start(link) })
    return CarLink(view, scanning = run.since != null, scanNote = run.note, scan = scan)
}

/**
 * The page's own [ObdLink] to the listed adapters [carData] while it is shown, or null while a
 * session records or none is listed. [profile] and [scan] are read at each connection.
 */
@Composable
private fun rememberPageLink(
    recording: Boolean,
    carData: List<Equipment>,
    profile: () -> ObdCarProfile,
    scan: () -> Boolean,
): ObdLink? {
    val context = LocalContext.current
    val link = remember(recording, carData) {
        if (recording || carData.isEmpty()) {
            null
        } else {
            val app = context.applicationContext
            ObdLink(
                targets = { obdTargets(app, carData) },
                owner = "page",
                watch = { obdWatch(app, it) },
                profile = profile,
                scan = scan,
            )
        }
    }
    DisposableEffect(link) {
        link?.start()
        onDispose { link?.stop() }
    }
    return link
}

/** One scan as the page runs it (UI-303 B): asked for, running since when, why the last one failed. */
private class ScanRun {
    val asked = AtomicBoolean(false)
    var since by mutableStateOf<Long?>(null)
    var note by mutableStateOf<String?>(null)
    private var stored: CarScan? = null

    fun start(link: ObdLink) {
        asked.set(true)
        since = SystemClock.elapsedRealtime()
        note = null
        link.restart()
    }

    /** A new scan in [status] goes to [onScan]; one running past [ScanGiveUpMs] gives up and says why. */
    fun check(status: ObdStatus?, onScan: (CarScan) -> Unit) {
        val scan = status?.scan
        val started = since
        if (scan != null && scan !== stored) {
            stored = scan
            finish(null)
            onScan(scan)
        } else if (started != null && SystemClock.elapsedRealtime() - started > ScanGiveUpMs) {
            finish("The scan did not finish: ${status?.detail ?: "no adapter"}. Switch the ignition on and try again.")
        }
    }

    private fun finish(why: String?) {
        asked.set(false)
        since = null
        note = why
    }
}

/** The page's connection (FBBD-344): what it shows, whether a scan runs, why the last one failed, how to start one. */
internal data class CarLink(
    val view: ObdView,
    val scanning: Boolean = false,
    val scanNote: String? = null,
    val scan: (() -> Unit)? = null,
)

/** The key of a parameter being tried before it is saved: its value arrives as channel "custom_draft". */
internal const val DraftKey = "draft"

private fun saveScan(repo: SetupRepo, carId: String, scan: CarScan) {
    val record = scan.toRecord(System.currentTimeMillis())
    DebugLog.i(
        "Car",
        "scan stored: protocol ${record.protocol}, ${record.supported.size} PIDs, ${record.canIds.size} CAN ids",
    )
    repo.update { s -> s.car(carId)?.let { s.updateCar(it.copy(scan = record)) } ?: s }
}

/** A scan takes about 15 s once connected; past this the adapter or the car is not answering. */
private const val ScanGiveUpMs = 60_000L
