package com.flappedear.betterdriver.recording

import android.content.Context
import android.net.ConnectivityManager
import com.flappedear.betterdriver.core.ble.obd.WifiObdTarget
import com.flappedear.betterdriver.core.ble.obd.bluetoothObdTarget
import com.flappedear.betterdriver.core.ble.obd.bluetoothObdTransport
import com.flappedear.betterdriver.obd.CarProfile
import com.flappedear.betterdriver.obd.ObdTarget
import com.flappedear.betterdriver.obd.ObdTransport
import com.flappedear.betterdriver.obd.PollSet
import com.flappedear.betterdriver.obd.ownProfile
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.SetupFormatException
import com.flappedear.betterdriver.setup.SetupJson
import com.flappedear.betterdriver.setup.TcpAddress
import com.flappedear.betterdriver.setup.TileIds
import com.flappedear.betterdriver.setup.TrackScreens
import com.flappedear.betterdriver.shell.ObdAdapterLabel
import com.flappedear.betterdriver.shell.SetupRepo
import java.io.File
import java.io.IOException

/**
 * The OBD-II adapters listed when the running session started (FBBD-121, UI-105 C), read in the
 * recorder's process from the setup the UI saved at START ([SetupRepo.sessionStarted]). Empty when
 * none is listed or the file cannot be read; a recording never waits for car data.
 */
internal fun sessionCarData(context: Context): List<Equipment> = sessionSetup(context)?.active()?.carData.orEmpty()

/**
 * What the running session reads from the car (FBBD-283): [PollSet.Driver] plus every OBD-II channel
 * the track screen's tiles show, on the main screen or the second one, from the setup saved at START
 * and updated when a tile is chosen during the session. The file is read again only when it changed.
 * [PollSet.broadcastCar] follows the session's car (FBBD-284), and [PollSet.pids] the PIDs switched
 * on for it (FBBD-344, UI-301 B).
 */
internal fun sessionPollSet(context: Context): () -> PollSet {
    val file = File(context.filesDir, SetupRepo.SessionSetupFile)
    var stamp = Long.MIN_VALUE
    var set = PollSet.Driver
    return {
        val modified = file.lastModified()
        if (modified != stamp) {
            stamp = modified
            val setup = sessionSetup(context)
            val tiles = setup?.trackScreens?.let(::obdTileChannels).orEmpty()
            val ft86 = setup?.active()?.car?.name?.let(::isBroadcastCar) == true
            val pids = setup?.active()?.car?.recordedPids?.toSet()
            set = PollSet.Driver.plus(tiles).copy(broadcastCar = ft86, pids = pids)
        }
        set
    }
}

/** The session's car's OBD profile: its own parameters (FBBD-344), from the setup saved at START. */
internal fun sessionCarProfile(context: Context): CarProfile = ownProfile(sessionSetup(context)?.active()?.car)

/**
 * Whether a car named [name] is an FR-S / 86 / BRZ of the first generation, whose CAN broadcasts the
 * recorder listens for (FBBD-263, FBBD-284). A GR86 or a model year from 2022 on (the second
 * generation, other CAN frames) is not. The car's VIN decides too, when the adapter reads it.
 */
internal fun isBroadcastCar(name: String): Boolean =
    BroadcastCarName.containsMatchIn(name) && !Gen2Name.containsMatchIn(name) &&
        Year.findAll(name).none { it.value.toInt() >= FirstGen2Year }

private const val FirstGen2Year = 2022
private val Gen2Name = Regex("""\bGR ?86\b""", RegexOption.IGNORE_CASE)
private val Year = Regex("""\b(19|20)\d\d\b""")

private val BroadcastCarName =
    Regex("""\bFR-?S\b|\bGT ?86\b|\bFT ?86\b|\bToyota 86\b|\bBRZ\b""", RegexOption.IGNORE_CASE)

/** The OBD-II channel ids ("coolant_temp") of the tiles on [screens] ("obd_coolant_temp"). */
internal fun obdTileChannels(screens: TrackScreens): Set<String> =
    (screens.main + screens.second).filterNotNull()
        .filter { it.startsWith(TileIds.OBD_PREFIX) }
        .mapTo(LinkedHashSet()) { it.removePrefix(TileIds.OBD_PREFIX) }

/** The setup saved at START, or null when there is none or it cannot be read. */
private fun sessionSetup(context: Context): Setup? {
    val file = File(context.filesDir, SetupRepo.SessionSetupFile)
    return try {
        if (file.isFile) SetupJson.decode(file.readText()) else null
    } catch (e: IOException) {
        DebugLog.w("Obd", "session setup unreadable: ${e.message}")
        null
    } catch (e: SetupFormatException) {
        DebugLog.w("Obd", "session setup unreadable: ${e.message}")
        null
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
