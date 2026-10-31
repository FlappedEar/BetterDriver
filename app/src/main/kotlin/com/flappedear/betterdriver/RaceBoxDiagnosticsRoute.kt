package com.flappedear.betterdriver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.flappedear.betterdriver.core.ble.ImuCalibration
import com.flappedear.betterdriver.core.ble.LinkProbe
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.RaceBoxDiagnostics
import com.flappedear.betterdriver.core.ble.RaceBoxDiagnosticsText
import com.flappedear.betterdriver.core.ble.RaceBoxGatt
import com.flappedear.betterdriver.core.ble.RaceBoxLink
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.imuCalibrations
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.sharing.AppDataGate
import com.flappedear.betterdriver.shell.CalibrationMs
import com.flappedear.betterdriver.shell.DiagnosticsView
import com.flappedear.betterdriver.shell.RaceBoxDiagnosticsScreen
import com.flappedear.betterdriver.shell.RaceBoxDiagnosticsUi
import com.flappedear.betterdriver.shell.recordingRows
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val RefreshMs = 250L

/**
 * RaceBox diagnostics for one equipment item (FBBD-89). While open and no session records, it holds
 * its own link to the RaceBox with a [LinkProbe] that reads the Device Information strings and sends
 * the two read-only queries (receiver settings, memory). It never sends a command that changes the
 * device. Leaving the screen closes the link.
 *
 * Calibrate collects 5 s of still samples and saves the result for this RaceBox, where the recorder
 * picks it up at the next session start. The RaceBox itself has no calibration command.
 */
@Composable
internal fun RaceBoxDiagnosticsRoute(item: Equipment, recorder: RecorderClient, onBack: () -> Unit) {
    val context = LocalContext.current
    val address = item.address
    val recording = recorder.state.recording || recorder.state.starting
    var allowed by remember { mutableStateOf(bluetoothAllowed(context)) }
    // Picks up Nearby devices granted in system settings.
    LifecycleResumeEffect(Unit) {
        allowed = bluetoothAllowed(context)
        onPauseOrDispose {}
    }
    // The advertised name tells a Mini from a Micro even after a rename; the Model string confirms it.
    val model = item.advertisedName ?: item.name
    val diag = remember(address) { RaceBoxDiagnostics(isMicro = !model.startsWith("RaceBox Mini")) }
    val link = diagnosticsLink(address?.takeIf { allowed && !recording }, diag)
    // The polling loop below outlives recompositions; it reads the link as it is now, not as first composed.
    val currentLink by rememberUpdatedState(link)
    val calibration = rememberCalibration(address, diag)
    var view by remember { mutableStateOf<DiagnosticsView?>(null) }

    LaunchedEffect(address, recording, allowed) {
        while (true) {
            view = when {
                recording -> DiagnosticsView.Recording(recordingRows(recorder.state.live))
                !allowed -> DiagnosticsView.NoPermission
                else -> {
                    val s = diag.snapshot(SystemClock.elapsedRealtime())
                    val state = currentLink?.state?.value ?: LinkState.Connecting
                    DiagnosticsView.Live(
                        RaceBoxDiagnosticsUi.header(item.name, state, s),
                        RaceBoxDiagnosticsUi.sections(s, state is LinkState.Streaming, calibration.error),
                    )
                }
            }
            delay(RefreshMs)
        }
    }

    view?.let { RaceBoxDiagnosticsScreen(it, onBack, onCalibrate = calibration::start) }
}

/** The diagnostics link to [address] while it is non-null; closed when it changes or the screen goes. */
@Composable
private fun diagnosticsLink(address: String?, diag: RaceBoxDiagnostics): RaceBoxLink? {
    val context = LocalContext.current
    var link by remember { mutableStateOf<RaceBoxLink?>(null) }
    DisposableEffect(address) {
        val l = address?.let {
            RaceBoxLink(
                context,
                it,
                onData = { bytes -> diag.feed(bytes, SystemClock.elapsedRealtime()) },
                onState = { s -> if (s is LinkState.Reconnecting) diag.reset() },
                probe = probe(diag),
            ).also(RaceBoxLink::start)
        }
        link = l
        onDispose {
            l?.stop()
            link = null
        }
    }
    return link
}

/** A calibration in progress for one RaceBox, and why the last one failed. */
private class CalibrationState {
    var running by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun start() {
        error = null
        running = true
    }
}

@Composable
private fun rememberCalibration(address: String?, diag: RaceBoxDiagnostics): CalibrationState {
    val context = LocalContext.current
    val store = remember { lazy { imuCalibrations(context) } }
    val state = remember(address) { CalibrationState() }
    // The calibration file is read and written off the main thread (FBBD-41).
    LaunchedEffect(address) {
        diag.calibration(withContext(Dispatchers.IO) { address?.let { store.value.load(it) } })
    }
    LaunchedEffect(state.running) {
        if (!state.running) return@LaunchedEffect
        diag.startCapture()
        delay(CalibrationMs)
        state.error = when (val result = ImuCalibration.from(diag.endCapture(), System.currentTimeMillis())) {
            is ImuCalibration.Result.Failed -> result.failure.text
            is ImuCalibration.Result.Ok -> try {
                // Under the restore gate (FBBD-156), so a restore swapping the calibrations never drops it.
                withContext(Dispatchers.IO) {
                    address?.let { AppDataGate.write { store.value.save(it, result.calibration) } }
                }
                diag.calibration(result.calibration)
                null
            } catch (e: IOException) {
                "Could not save the calibration: ${e.message}"
            }
        }
        state.running = false
    }
    return state
}

/** Feeds link facts into [diag] and asks only what this device's firmware answers. */
private fun probe(diag: RaceBoxDiagnostics) = object : LinkProbe {
    override fun onMtu(mtu: Int) = diag.link(mtuBytes = mtu)

    override fun onRssi(dbm: Int) = diag.link(rssiDbm = dbm)

    override fun onDeviceInfo(uuid: UUID, value: String) = diag.deviceInfo { info ->
        when (uuid) {
            RaceBoxGatt.MODEL -> info.copy(model = value)
            RaceBoxGatt.SERIAL -> info.copy(serial = value)
            RaceBoxGatt.FIRMWARE -> info.copy(firmware = value)
            RaceBoxGatt.HARDWARE -> info.copy(hardware = value)
            RaceBoxGatt.MANUFACTURER -> info.copy(manufacturer = value)
            else -> info
        }
    }

    override fun queries(): List<ByteArray> =
        RaceBoxDiagnosticsText.queries(diag.snapshot(SystemClock.elapsedRealtime()).info)
}

private fun bluetoothAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
