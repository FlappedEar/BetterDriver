package com.flappedear.betterdriver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.flappedear.betterdriver.core.ble.obd.bluetoothObdTransport
import com.flappedear.betterdriver.obd.ObdTransport
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.log.ReachLog
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.shell.AppPrefs
import com.flappedear.betterdriver.shell.Reach
import com.flappedear.betterdriver.shell.SetupRepo

/**
 * Whether each listed item can be reached now (UI-138 B), for Settings › Equipment: while the page is
 * open it looks for Bluetooth LE devices, as Add equipment does. Only what can be told: a Classic
 * device is never heard by the scan, so it is Paired; a Wi-Fi one is not looked for. Without the
 * Bluetooth permissions nothing is looked for and the Bluetooth items get no line. While a session
 * records the page does not look; the session's items say so. Each state it shows goes to the
 * continuous log when it changes ([ReachLog], FBBD-280), so the drive's checks can tell whether it
 * was right.
 */
@Composable
internal fun rememberEquipmentReach(prefs: AppPrefs, repo: SetupRepo): Map<String, Reach> {
    val context = LocalContext.current
    val recorder = RecorderClient.get(context)
    val recording = recorder.state.recording || recorder.state.starting
    val searching = remember(recording) { !recording && prefs.hasRequiredPermissions() }
    val found by rememberNearby(searching)
    val heard = found.map { it.address.uppercase() }.toSet()
    val setup = if (recording) repo.forSession() else repo.setup
    val inSession = setup.active().let { a -> listOfNotNull(a.gnss) + a.cameras + a.sensors + a.carData }
        .map { it.id }.toSet()
    // Bonded devices are read once per list: a Classic OBD-II adapter is not heard by a scan.
    val classicObd = remember(setup.equipment) {
        setup.equipment.filter { it.model == EquipmentModel.OBD_BLUETOOTH && it.address != null }
            .filter { bluetoothObdTransport(context, it.address.orEmpty()) == ObdTransport.BluetoothClassic }
            .map { it.id }.toSet()
    }
    val byId = setup.equipment.mapNotNull { e ->
        val reach = if (recording) {
            Reach.InSession.takeIf { e.id in inSession }
        } else {
            reachOf(e, classic = e.model in ClassicModels || e.id in classicObd, heard = heard.takeIf { searching })
        }
        reach?.let { e.id to it }
    }.toMap()
    LogReach(searching, setup.equipment, byId)
    return byId
}

/** Logs when the page starts and stops looking, and each item's state when it changes (FBBD-280). */
@Composable
private fun LogReach(searching: Boolean, equipment: List<Equipment>, reach: Map<String, Reach>) {
    DisposableEffect(searching) {
        if (searching) DebugLog.i(ReachLog.TAG, ReachLog.look(true, equipment.size))
        onDispose { if (searching) DebugLog.i(ReachLog.TAG, ReachLog.look(false, 0)) }
    }
    val shown = equipment.associate { e -> e.id to ReachLog.Shown(e.model.name, e.address, reach[e.id]?.name) }
    val logged = remember { mutableMapOf<String, ReachLog.Shown>() }
    LaunchedEffect(shown) {
        ReachLog.changes(logged.toMap(), shown).forEach { DebugLog.i(ReachLog.TAG, it) }
        logged.clear()
        logged.putAll(shown)
    }
}

/** [heard] is the addresses the scan heard, null while it does not look. */
private fun reachOf(e: Equipment, classic: Boolean, heard: Set<String>?): Reach? = when {
    e.model == EquipmentModel.PHONE_CAMERA -> Reach.BuiltIn
    !e.model.controlledByApp -> Reach.ByHand
    e.model in MadeUpModels -> Reach.MadeUp
    e.model in WifiModels -> Reach.WiFi
    classic -> Reach.Paired
    heard == null || e.address == null -> null
    e.address.orEmpty().uppercase() in heard -> Reach.InRange
    else -> Reach.NotSeen
}

private val MadeUpModels = setOf(EquipmentModel.DEMO_GNSS, EquipmentModel.DEMO_TRACK)
private val WifiModels = setOf(EquipmentModel.TCP_GNSS, EquipmentModel.OBD_WIFI)

/** Bluetooth Classic receivers, paired in Android's settings and never heard by a scan. */
private val ClassicModels = setOf(EquipmentModel.NMEA_GNSS, EquipmentModel.VBOX_SPORT)
