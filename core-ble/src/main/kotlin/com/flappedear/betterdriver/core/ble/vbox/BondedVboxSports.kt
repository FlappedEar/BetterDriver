package com.flappedear.betterdriver.core.ble.vbox

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

/**
 * VBOX Sports paired in Android's Bluetooth settings ([isVboxSport]), by name. Like NMEA
 * receivers they are paired there, not found by a scan in the app. Returns an empty list if
 * Bluetooth is off or BLUETOOTH_CONNECT is missing; the app checks the permission first.
 */
@SuppressLint("MissingPermission")
fun bondedVboxSports(context: Context): List<FoundVboxSport> {
    val adapter = context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
    val bonded: Set<BluetoothDevice> = try {
        adapter?.bondedDevices.orEmpty()
    } catch (_: SecurityException) {
        emptySet()
    }
    return bonded.mapNotNull(::vboxSport).sortedBy { it.name.lowercase() }
}

@SuppressLint("MissingPermission")
private fun vboxSport(device: BluetoothDevice): FoundVboxSport? = try {
    val name: String? = device.name
    val leOnly = device.type == BluetoothDevice.DEVICE_TYPE_LE
    if (isVboxSport(name, leOnly)) FoundVboxSport(checkNotNull(name), device.address) else null
} catch (_: SecurityException) {
    // Permission revoked while listing.
    null
}
