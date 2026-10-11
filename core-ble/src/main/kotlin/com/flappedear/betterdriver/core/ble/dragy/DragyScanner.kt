package com.flappedear.betterdriver.core.ble.dragy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

private const val ForgetAfterMs = 5_000L

/**
 * Scans for Dragys ([isDragy]: name, `FD00` service or manufacturer data) while collected and emits
 * those heard in the last 5 s, strongest first. Emits an empty list if Bluetooth is off or a
 * permission is missing; the app checks permissions before collecting. Like `scanRaceBoxes`.
 */
@SuppressLint("MissingPermission")
fun scanDragys(context: Context): Flow<List<FoundDragy>> = callbackFlow {
    val scanner = context.applicationContext.getSystemService(BluetoothManager::class.java)
        ?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    val seen = LinkedHashMap<String, Pair<FoundDragy, Long>>()
    fun emit() {
        val now = SystemClock.elapsedRealtime()
        seen.values.removeAll { now - it.second > ForgetAfterMs }
        trySend(seen.values.map { it.first }.sortedByDescending { it.rssi })
    }
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val name = record?.deviceName
            if (!isDragy(name, record?.serviceUuids.orEmpty().map { it.uuid }, manufacturerBytes(record))) return
            val found = FoundDragy(name ?: result.device.address, result.device.address, result.rssi)
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

/** All manufacturer-specific data of an advertisement, concatenated, or null if there is none. */
private fun manufacturerBytes(record: ScanRecord?): ByteArray? {
    val data = record?.manufacturerSpecificData ?: return null
    return (0 until data.size()).fold(ByteArray(0)) { acc, i -> acc + (data.valueAt(i) ?: ByteArray(0)) }
        .takeIf { it.isNotEmpty() }
}
