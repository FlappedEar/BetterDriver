package com.flappedear.betterdriver.gopro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

private const val ForgetAfterMs = 5_000L

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

/**
 * Scans for GoPros while collected and emits those heard in the last 5 s, strongest first.
 * Filters on the Open GoPro control service 0xFEA6, which the camera advertises while it is on
 * (https://gopro.github.io/OpenGoPro/ble/protocol/ble_setup.html). Emits an empty list if Bluetooth
 * is off or a permission is missing; the app checks permissions before collecting.
 */
@SuppressLint("MissingPermission")
fun scanGoPros(context: Context): Flow<List<FoundGoPro>> = callbackFlow {
    val scanner = context.applicationContext.getSystemService(BluetoothManager::class.java)
        ?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    val seen = LinkedHashMap<String, Pair<FoundGoPro, Long>>()
    fun emit() {
        val now = SystemClock.elapsedRealtime()
        seen.values.removeAll { now - it.second > ForgetAfterMs }
        trySend(seen.values.map { it.first }.sortedByDescending { it.rssi })
    }
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: "GoPro"
            val found = FoundGoPro(name, result.device.address, result.rssi)
            synchronized(seen) {
                seen[found.address] = found to SystemClock.elapsedRealtime()
                emit()
            }
        }
    }
    val started = scanner != null && try {
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(GoProUuids.CONTROL_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        true
    } catch (_: SecurityException) {
        false
    }
    if (!started) trySend(emptyList())
    awaitClose {
        try {
            if (started) scanner?.stopScan(callback)
        } catch (_: SecurityException) {
            // Permission revoked while scanning: the scan is already gone.
        } catch (_: IllegalStateException) {
            // Bluetooth turned off while scanning.
        }
    }
}.distinctUntilChanged()
