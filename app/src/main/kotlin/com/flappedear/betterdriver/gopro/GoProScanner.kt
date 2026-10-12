package com.flappedear.betterdriver.gopro

import android.content.Context
import com.flappedear.betterdriver.core.ble.nearbyDevices
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * GoPros heard in the shared scan (`BleScan`) in the last 5 s, strongest first, while collected.
 * Matches on the Open GoPro control service 0xFEA6, which the camera advertises while it is on
 * (https://gopro.github.io/OpenGoPro/ble/protocol/ble_setup.html). Emits an empty list first, and
 * nothing more if Bluetooth is off or a permission is missing; the app checks permissions before
 * collecting.
 */
fun scanGoPros(context: Context): Flow<List<FoundGoPro>> {
    val control = UUID.fromString(GoProUuids.CONTROL_SERVICE)
    return nearbyDevices(context, FoundGoPro::address, FoundGoPro::rssi) { result ->
        val record = result.scanRecord
        if (record?.serviceUuids.orEmpty().any { it.uuid == control }) {
            FoundGoPro(record?.deviceName ?: "GoPro", result.device.address, result.rssi)
        } else {
            null
        }
    }
}

/** A GoPro seen in a scan. [name] is what it advertises, or "GoPro" when it sends no name. */
data class FoundGoPro(val name: String, val address: String, val rssi: Int) {
    /** The advertised name after "GoPro", for example the last digits of the serial number. */
    val serial: String get() = name.removePrefix("GoPro").trim()

    /** Signal strength as 0 to 4 bars, with the same thresholds as the RaceBox pairing screen. */
    val bars: Int
        get() = when {
            rssi >= -60 -> 4
            rssi >= -70 -> 3
            rssi >= -80 -> 2
            rssi >= -90 -> 1
            else -> 0
        }
}
