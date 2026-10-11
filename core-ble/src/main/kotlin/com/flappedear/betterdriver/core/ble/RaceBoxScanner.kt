package com.flappedear.betterdriver.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
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
 * Scans for RaceBoxes while collected and emits those heard in the last 5 s, strongest first.
 * Matches on the advertised name, so a RaceBox is found even if it does not put its service
 * UUID in the advertisement. Emits an empty list if Bluetooth is off or a permission is missing;
 * the app checks permissions before collecting.
 */
@SuppressLint("MissingPermission")
fun scanRaceBoxes(context: Context): Flow<List<FoundRaceBox>> = callbackFlow {
    val scanner = context.applicationContext.getSystemService(BluetoothManager::class.java)
        ?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    val seen = LinkedHashMap<String, Pair<FoundRaceBox, Long>>()
    fun emit() {
        val now = SystemClock.elapsedRealtime()
        seen.values.removeAll { now - it.second > ForgetAfterMs }
        trySend(seen.values.map { it.first }.sortedByDescending { it.rssi })
    }
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName
            if (!FoundRaceBox.isRaceBox(name)) return
            val found = FoundRaceBox(checkNotNull(name), result.device.address, result.rssi)
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
