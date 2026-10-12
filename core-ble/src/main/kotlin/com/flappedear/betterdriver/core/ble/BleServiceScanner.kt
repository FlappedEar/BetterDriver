package com.flappedear.betterdriver.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val ForgetAfterMs = 5_000L

/** How often the list is re-checked for devices that went quiet. */
private const val RecheckMs = 1_000L

/** A BLE device seen in a scan. [name] is empty when it advertises none. */
data class FoundBleDevice(val name: String, val address: String, val rssi: Int) {
    /** Signal strength as 0 to 4 bars, on the same thresholds as [FoundRaceBox.bars]. */
    val bars: Int get() = FoundRaceBox("", address, rssi).bars
}

/**
 * Scans while collected and emits the devices heard in the last 5 s for which [matches] (given the
 * advertised name and service UUIDs, each null when absent) is true, strongest first. Used for
 * standard-service devices (LNS receivers, heart rate monitors), matched by the pure filters
 * next to their sources. Emits an empty list if Bluetooth is off or a permission is missing; the
 * app checks permissions before collecting.
 */
@SuppressLint("MissingPermission")
fun scanBleDevices(
    context: Context,
    matches: (name: String?, serviceUuids: List<UUID>?) -> Boolean,
): Flow<List<FoundBleDevice>> = callbackFlow {
    val scanner = context.applicationContext.getSystemService(BluetoothManager::class.java)
        ?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    val seen = LinkedHashMap<String, Pair<FoundBleDevice, Long>>()
    fun emit() {
        val now = SystemClock.elapsedRealtime()
        seen.values.removeAll { now - it.second > ForgetAfterMs }
        trySend(seen.values.map { it.first }.sortedByDescending { it.rssi })
    }
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val name = record?.deviceName
            if (!matches(name, record?.serviceUuids?.map { it.uuid })) return
            val found = FoundBleDevice(name.orEmpty(), result.device.address, result.rssi)
            synchronized(seen) {
                seen[found.address] = found to SystemClock.elapsedRealtime()
                emit()
            }
        }
    }
    val started = scanner != null && try {
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        true
    } catch (_: SecurityException) {
        false
    }
    if (!started) trySend(emptyList())
    // A device that stops advertising sends no more results; this drops it from the list anyway.
    if (started) {
        launch {
            while (true) {
                delay(RecheckMs)
                synchronized(seen) { emit() }
            }
        }
    }
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
