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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.flappedear.betterdriver.core.ble.LinkProbe
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.RaceBoxDiagnostics
import com.flappedear.betterdriver.core.ble.RaceBoxGatt
import com.flappedear.betterdriver.core.ble.RaceBoxLink
import com.flappedear.betterdriver.core.ble.RaceBoxMessages
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.shell.DiagnosticsView
import com.flappedear.betterdriver.shell.RaceBoxDiagnosticsScreen
import com.flappedear.betterdriver.shell.RaceBoxDiagnosticsUi
import java.util.UUID
import kotlinx.coroutines.delay

private const val RefreshMs = 250L

/**
 * RaceBox diagnostics for one equipment item (FBBD-89). While open and no session records, it holds
 * its own link to the RaceBox with a [LinkProbe] that reads the Device Information strings and sends
 * the two read-only queries (receiver settings, memory). It never sends a command that changes the
 * device. Leaving the screen closes the link.
 */
@Composable
internal fun RaceBoxDiagnosticsRoute(item: Equipment, recorder: RecorderClient, onBack: () -> Unit) {
    val context = LocalContext.current
    val address = item.address
    val recording = recorder.state.recording || recorder.state.starting
    val allowed = remember { bluetoothAllowed(context) }
    // Every RaceBox is added as a Micro; a renamed or Mini item is corrected by the Model string.
    val isMicro = !item.name.startsWith("RaceBox Mini")
    val diag = remember(address) { RaceBoxDiagnostics(isMicro) }
    var link by remember(address) { mutableStateOf<RaceBoxLink?>(null) }
    var view by remember { mutableStateOf<DiagnosticsView?>(null) }

    DisposableEffect(address, recording, allowed) {
        val l = if (address != null && allowed && !recording) {
            RaceBoxLink(
                context,
                address,
                onData = { diag.feed(it, SystemClock.elapsedRealtime()) },
                onState = { if (it is LinkState.Reconnecting) diag.reset() },
                probe = probe(diag),
            ).also { it.start() }
        } else {
            null
        }
        link = l
        onDispose {
            l?.stop()
            link = null
        }
    }

    LaunchedEffect(address, recording, allowed) {
        while (true) {
            view = when {
                recording -> DiagnosticsView.Recording
                !allowed -> DiagnosticsView.NoPermission
                else -> {
                    val s = diag.snapshot(SystemClock.elapsedRealtime())
                    val state = link?.state?.value ?: LinkState.Connecting
                    DiagnosticsView.Live(
                        RaceBoxDiagnosticsUi.header(item.name, state, s),
                        RaceBoxDiagnosticsUi.sections(s),
                    )
                }
            }
            delay(RefreshMs)
        }
    }

    view?.let { RaceBoxDiagnosticsScreen(it, onBack) }
}

/** Feeds link facts into [diag] and asks only what this device's firmware answers. */
private fun probe(diag: RaceBoxDiagnostics) = object : LinkProbe {
    override fun onMtu(mtu: Int) = diag.mtu(mtu)

    override fun onRssi(dbm: Int) = diag.rssi(dbm)

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

    override fun queries(): List<ByteArray> {
        val info = diag.snapshot(SystemClock.elapsedRealtime()).info
        return buildList {
            if (info.supportsGnssConfig) add(RaceBoxMessages.gnssConfigQuery())
            // The plain Mini has no memory (p. 10); the Mini S and Micro answer the status query.
            if (info.model != null && info.model != "RaceBox Mini") add(RaceBoxMessages.recordingStatusQuery())
        }
    }
}

private fun bluetoothAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
