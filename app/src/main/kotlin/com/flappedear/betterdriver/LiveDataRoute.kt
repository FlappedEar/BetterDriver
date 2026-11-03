package com.flappedear.betterdriver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import com.flappedear.betterdriver.core.ble.DiagHeaderText
import com.flappedear.betterdriver.core.ble.diy.DiyBleSource
import com.flappedear.betterdriver.core.ble.dragy.DragySource
import com.flappedear.betterdriver.core.ble.hr.HeartRateSource
import com.flappedear.betterdriver.core.ble.lns.LnsSource
import com.flappedear.betterdriver.core.ble.nmea.NmeaSource
import com.flappedear.betterdriver.core.ble.nmea.TcpNetwork
import com.flappedear.betterdriver.core.ble.nmea.TcpNmeaSource
import com.flappedear.betterdriver.core.ble.tpms.TpmsSource
import com.flappedear.betterdriver.core.ble.vbox.VboxSportSource
import com.flappedear.betterdriver.gopro.GoProBleLink
import com.flappedear.betterdriver.gopro.GoProLive
import com.flappedear.betterdriver.gopro.GoProLiveText
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import com.flappedear.betterdriver.recorder.live.EquipmentLiveData
import com.flappedear.betterdriver.recorder.live.EquipmentLiveText
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recording.DemoReplay
import com.flappedear.betterdriver.recording.PhoneGnssSource
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.onNetwork
import com.flappedear.betterdriver.recording.wifi
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.TcpAddress
import com.flappedear.betterdriver.shell.DeviceState
import com.flappedear.betterdriver.shell.DiagHeader
import com.flappedear.betterdriver.shell.DiagnosticsView
import com.flappedear.betterdriver.shell.LivePageText
import com.flappedear.betterdriver.shell.RaceBoxDiagnosticsScreen
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay

private const val RefreshMs = 250L
private const val LogTag = "LiveData"

private val UtcClock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.S").withZone(ZoneOffset.UTC)

/** The live data page's text around the shared rows; [RaceBoxDiagnosticsScreen] shows it. */
internal val LiveText = LivePageText(
    title = EquipmentLiveText.TITLE,
    permission = "Allow Nearby devices for BetterDriver in Android settings to connect to it.",
    recording = EquipmentLiveText.RECORDING,
    recordingNote = EquipmentLiveText.RECORDING_NOTE,
    note = EquipmentLiveText.NOTE,
)

private val GoProText = LiveText.copy(note = GoProLiveText.NOTE)

/**
 * An Equipment item's live data without a session (FBBD-440, Arek 2026-11-03), in the RaceBox
 * diagnostics page's look. While open and no session records it runs the device's own recorder
 * source ([liveSource]) into an [EquipmentLiveData], or for a paired GoPro a read-only [GoProLive];
 * a session starting takes the device back, and leaving the page closes the connection.
 */
@Composable
internal fun LiveDataRoute(item: Equipment, recorder: RecorderClient, onBack: () -> Unit) {
    val context = LocalContext.current
    val recording = recorder.state.recording || recorder.state.starting
    var allowed by remember { mutableStateOf(allowed(context, item.model)) }
    // Picks up Nearby devices granted in system settings.
    LifecycleResumeEffect(Unit) {
        allowed = allowed(context, item.model)
        onPauseOrDispose {}
    }
    val connect = allowed && !recording
    var view by remember { mutableStateOf<DiagnosticsView?>(null) }
    if (item.model == EquipmentModel.GOPRO) {
        val live = goProLive(item.address?.takeIf { connect })
        val current by rememberUpdatedState(live)
        LaunchedEffect(recording, allowed) {
            while (true) {
                view = when {
                    recording -> DiagnosticsView.Recording(emptyList())
                    !allowed -> DiagnosticsView.NoPermission
                    else -> current?.snapshot()?.let { s ->
                        DiagnosticsView.Live(header(GoProLiveText.header(item.name, s)), GoProLiveText.sections(s))
                    }
                }
                delay(RefreshMs)
            }
        }
        view?.let { RaceBoxDiagnosticsScreen(it, onBack, text = GoProText) }
        return
    }
    val feed = remember(item.id) { EquipmentLiveData() }
    DisposableEffect(item.id, connect) {
        val holder = if (connect) SourceHolder(context.applicationContext, item, feed).also { it.open() } else null
        onDispose { holder?.close() }
    }
    LaunchedEffect(recording, allowed) {
        while (true) {
            view = when {
                recording -> DiagnosticsView.Recording(emptyList())
                !allowed -> DiagnosticsView.NoPermission
                else -> feed.snapshot().let { s ->
                    DiagnosticsView.Live(
                        header(EquipmentLiveText.header(item.name, s)),
                        EquipmentLiveText.sections(s) { UtcClock.format(Instant.ofEpochMilli(it)) },
                    )
                }
            }
            delay(RefreshMs)
        }
    }
    view?.let { RaceBoxDiagnosticsScreen(it, onBack, text = LiveText) }
}

/**
 * [item]'s source while the page shows it, opened and closed on a thread of its own: a source can read
 * files as it starts (the Demo track's drive) and waits for its thread as it stops.
 */
private class SourceHolder(
    private val context: Context,
    private val item: Equipment,
    private val feed: EquipmentLiveData,
) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "live-data").apply { isDaemon = true } }
    private var source: DataSource? = null

    fun open() = worker.execute {
        try {
            source = liveSource(context, item)?.also {
                DebugLog.i(LogTag, "open item=${item.id} model=${item.model}")
                it.start(feed)
            }
        } catch (e: IOException) {
            feed.event("live_error", e.message ?: "cannot connect")
        }
    }

    fun close() {
        worker.execute {
            source?.let {
                it.stop()
                DebugLog.i(LogTag, "close item=${item.id} model=${item.model}")
            }
            source = null
            // After the stop, so no late value of this connection is left for the next one.
            feed.reset()
        }
        worker.shutdown()
    }
}

/**
 * The source that records [item] in a session, made the way the recorder makes it (Sources.create),
 * or null for a model with no source of its own. Kept apart from the recorder's code so that code is
 * untouched (FBBD-440 shipped before the Moto Park track day).
 */
@Suppress("CyclomaticComplexMethod") // One branch per equipment model.
internal fun liveSource(context: Context, item: Equipment): DataSource? {
    val app = context.applicationContext
    val address = item.address.orEmpty()
    // The device's advertised name, as the recorder logs it, whatever Arek calls it.
    val name = item.advertisedName ?: item.name
    return when (item.model) {
        EquipmentModel.NMEA_GNSS -> NmeaSource(app, address, name)
        EquipmentModel.LNS_GNSS -> LnsSource(app, address, name)
        EquipmentModel.DIY_GNSS -> DiyBleSource(app, address, name)
        EquipmentModel.TCP_GNSS -> {
            val at = TcpAddress.parse(address) ?: throw IOException("Not a host and port: $address")
            TcpNmeaSource(at.host, at.port, name, network = { wifi(app)?.let(::onNetwork) ?: TcpNetwork.Default })
        }
        EquipmentModel.VBOX_SPORT -> VboxSportSource(app, address, name)
        EquipmentModel.DRAGY -> DragySource(app, address, name)
        EquipmentModel.PHONE_GPS -> PhoneGnssSource(app)
        EquipmentModel.DEMO_GNSS -> FakeGnssSource()
        EquipmentModel.DEMO_TRACK -> FakeGnssSource(DemoReplay.script(app))
        EquipmentModel.HEART_RATE -> HeartRateSource(app, address, name)
        EquipmentModel.TPMS_BRIDGE -> TpmsSource(app, address, name)
        else -> null
    }
}

/** A read-only GoPro status link to [address] while it is non-null; let go when it changes or the page goes. */
@Composable
private fun goProLive(address: String?): GoProLive? {
    val context = LocalContext.current
    // Outlives the page, so the camera is let go after the page has gone.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    var live by remember { mutableStateOf<GoProLive?>(null) }
    DisposableEffect(address) {
        val l = address?.let { GoProLive(GoProBleLink(context, it), scope).also(GoProLive::start) }
        if (l != null) DebugLog.i(LogTag, "open gopro")
        live = l
        onDispose {
            l?.stop()
            live = null
        }
    }
    return live
}

private fun header(h: DiagHeaderText) =
    DiagHeader(if (h.ok) DeviceState.Ready else DeviceState.Attention, h.title, h.detail)

/** Bluetooth devices need Nearby devices; Wi-Fi, the phone's GPS (asks on its own) and the demos do not. */
private fun allowed(context: Context, model: EquipmentModel): Boolean {
    val bluetooth = model !in setOf(
        EquipmentModel.TCP_GNSS,
        EquipmentModel.PHONE_GPS,
        EquipmentModel.DEMO_GNSS,
        EquipmentModel.DEMO_TRACK,
    )
    return !bluetooth || Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
}
