package com.flappedear.betterdriver.recording

import android.content.Context
import android.net.ConnectivityManager
import com.flappedear.betterdriver.core.ble.obd.WifiObdTarget
import com.flappedear.betterdriver.core.ble.obd.bluetoothObdTarget
import com.flappedear.betterdriver.core.ble.obd.bluetoothObdTransport
import com.flappedear.betterdriver.obd.ObdTarget
import com.flappedear.betterdriver.obd.ObdTransport
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.SetupFormatException
import com.flappedear.betterdriver.setup.SetupJson
import com.flappedear.betterdriver.setup.TcpAddress
import com.flappedear.betterdriver.shell.ObdAdapterLabel
import com.flappedear.betterdriver.shell.SetupRepo
import java.io.File
import java.io.IOException

/**
 * The OBD-II adapters listed when the running session started (FBBD-121, UI-105 C), read in the
 * recorder's process from the setup the UI saved at START ([SetupRepo.sessionStarted]). Empty when
 * none is listed or the file cannot be read; a recording never waits for car data.
 */
internal fun sessionCarData(context: Context): List<Equipment> {
    val file = File(context.filesDir, SetupRepo.SessionSetupFile)
    return try {
        if (file.isFile) SetupJson.decode(file.readText()).active().carData else emptyList()
    } catch (e: IOException) {
        DebugLog.w("Obd", "session setup unreadable: ${e.message}")
        emptyList()
    } catch (e: SetupFormatException) {
        DebugLog.w("Obd", "session setup unreadable: ${e.message}")
        emptyList()
    }
}

/**
 * Where to try [adapters], in order (FBBD-121): every Bluetooth one first, by the name Android shows
 * for it and over the transport it uses, then every Wi-Fi one (FBBD-15) at the Wi-Fi network's
 * gateway, which is the adapter when the phone is on its access point, and at its listed address.
 * A Wi-Fi adapter is not tried while the phone has no Wi-Fi.
 */
internal fun obdTargets(context: Context, adapters: List<Equipment>): List<ObdTarget> {
    val bluetooth = adapters.filter { it.model == EquipmentModel.OBD_BLUETOOTH && it.address != null }.map {
        bluetoothObdTarget(context, it.advertisedName ?: it.name, it.address.orEmpty())
    }
    val wifiAt = adapters.filter { it.model == EquipmentModel.OBD_WIFI }.mapNotNull { e ->
        e.address?.let { TcpAddress.parse(it) }?.let { e.name to it }
    }
    val network = if (wifiAt.isEmpty()) null else wifi(context)
    if (network == null) return bluetooth
    val gateway = context.getSystemService(ConnectivityManager::class.java)?.getLinkProperties(network)?.routes
        ?.firstOrNull { it.isDefaultRoute && it.gateway?.isAnyLocalAddress == false }
        ?.gateway?.hostAddress
    val tcp = onNetwork(network)
    val wifi = wifiAt.flatMap { (name, at) ->
        listOfNotNull(gateway, at.host).distinct().map { WifiObdTarget(it, at.port, tcp, name) }
    }.distinctBy { it.where }
    return bluetooth + wifi
}

/**
 * How the OBD page and the Settings row name [adapter] (UI-106 A): the name Android shows for a
 * Bluetooth one and how it is reached, or the Wi-Fi adapter's name. Null with none listed.
 */
internal fun obdAdapterLabel(context: Context, adapter: Equipment?): ObdAdapterLabel? = when {
    adapter == null -> null
    adapter.model == EquipmentModel.OBD_WIFI -> ObdAdapterLabel(adapter.name, ObdAdapterLabel.WifiVia)
    else -> ObdAdapterLabel(
        adapter.advertisedName ?: adapter.name,
        bluetoothObdTransport(context, adapter.address.orEmpty())?.label ?: ObdTransport.BluetoothClassic.label,
    )
}

/** How a listed Bluetooth OBD-II adapter is reached, for its page (UI-105 C); null for anything else. */
internal fun obdConnectionLabel(context: Context, item: Equipment): String? {
    if (item.model != EquipmentModel.OBD_BLUETOOTH) return null
    return when (bluetoothObdTransport(context, item.address.orEmpty())) {
        ObdTransport.BluetoothLe -> "Bluetooth LE"
        ObdTransport.BluetoothClassic -> "Paired · Classic"
        else -> null
    }
}
