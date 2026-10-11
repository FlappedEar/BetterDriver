package com.flappedear.betterdriver.core.ble.nmea

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

/**
 * Devices paired in Android's Bluetooth settings that may be NMEA receivers ([isNmeaCandidate]),
 * by name. Classic receivers are paired there (often with a PIN such as 0000) rather than found
 * by a scan in the app: an SPP connection needs a bond, and they do not advertise over LE.
 *
 * Returns an empty list if Bluetooth is off or BLUETOOTH_CONNECT is missing; the app checks the
 * permission before calling. A device whose name cannot be read is listed by its address.
 */
@SuppressLint("MissingPermission")
fun bondedSppDevices(context: Context): List<FoundNmeaDevice> {
    val adapter = context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
    val bonded: Set<BluetoothDevice> = try {
        adapter?.bondedDevices.orEmpty()
    } catch (_: SecurityException) {
        emptySet()
    }
    return bonded.mapNotNull(::candidate).sortedBy { it.name.lowercase() }
}

@SuppressLint("MissingPermission")
private fun candidate(device: BluetoothDevice): FoundNmeaDevice? = try {
    val name: String? = device.name
    val major = device.bluetoothClass?.majorDeviceClass
    val leOnly = device.type == BluetoothDevice.DEVICE_TYPE_LE
    val label = name?.takeIf { it.isNotBlank() } ?: device.address
    if (isNmeaCandidate(name, major, leOnly)) FoundNmeaDevice(label, device.address) else null
} catch (_: SecurityException) {
    // Permission revoked while listing.
    null
}
