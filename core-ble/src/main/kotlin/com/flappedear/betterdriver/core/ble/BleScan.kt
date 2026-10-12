package com.flappedear.betterdriver.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/** Results queued between Android's scan callback and the scanners; more are dropped (they repeat). */
private const val ScanBuffer = 256

/** How often a list is re-checked for devices that went quiet. */
private const val RecheckMs = 1_000L

/**
 * One Bluetooth LE scan shared by every scanner collected at the same time. Android lets an app
 * start at most five scans in 30 s and then quietly delivers nothing ("scanning too frequently"),
 * so the add-equipment screen, with a scanner per kind of device, cannot start a scan for each.
 * The scan runs while anything collects [results] and stops when the last collector leaves.
 */
object BleScan {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var shared: Flow<ScanResult>? = null

    /** Every advertisement heard while collected. Nothing arrives if Bluetooth is off or a permission is missing. */
    fun results(context: Context): Flow<ScanResult> = shared ?: synchronized(this) {
        shared ?: platformScan(context.applicationContext)
            .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)
            .also { shared = it }
    }
}

@SuppressLint("MissingPermission")
private fun platformScan(context: Context): Flow<ScanResult> = callbackFlow {
    val scanner = context.getSystemService(BluetoothManager::class.java)
        ?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            trySend(result)
        }
    }
    val started = scanner != null && try {
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        true
    } catch (_: SecurityException) {
        false
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
}.buffer(ScanBuffer)

/**
 * The devices of one kind in the shared [BleScan]: [found] turns an advertisement into a [T], or
 * null when it is another kind. Emits an empty list first, then the devices heard in the last 5 s,
 * strongest first, whenever that changes.
 */
fun <T> nearbyDevices(
    context: Context,
    address: (T) -> String,
    rssi: (T) -> Int,
    found: (ScanResult) -> T?,
): Flow<List<T>> = channelFlow {
    val nearby = NearbyList(address, rssi)
    fun emit() {
        val list = synchronized(nearby) { nearby.current(SystemClock.elapsedRealtime()) }
        trySend(list)
    }
    send(emptyList())
    // A device that stops advertising sends no more results; this drops it from the list anyway.
    launch {
        while (true) {
            delay(RecheckMs)
            emit()
        }
    }
    BleScan.results(context).collect { result ->
        val device = found(result) ?: return@collect
        synchronized(nearby) { nearby.heard(device, SystemClock.elapsedRealtime()) }
        emit()
    }
}.distinctUntilChanged()

/** A BLE device seen in a scan. [name] is empty when it advertises none. */
data class FoundBleDevice(val name: String, val address: String, val rssi: Int) {
    /** Signal strength as 0 to 4 bars, on the same thresholds as [FoundRaceBox.bars]. */
    val bars: Int get() = FoundRaceBox("", address, rssi).bars
}

/**
 * The devices heard in the shared scan ([BleScan]) in the last 5 s for which [matches] (given the
 * advertised name and service UUIDs, each null when absent) is true, strongest first, while
 * collected. Used for standard-service devices (LNS receivers, heart rate monitors), matched by the
 * pure filters next to their sources. Emits an empty list first, and nothing more if Bluetooth is
 * off or a permission is missing; the app checks permissions before collecting.
 */
fun scanBleDevices(
    context: Context,
    matches: (name: String?, serviceUuids: List<UUID>?) -> Boolean,
): Flow<List<FoundBleDevice>> = nearbyDevices(context, FoundBleDevice::address, FoundBleDevice::rssi) { result ->
    val record = result.scanRecord
    val name = record?.deviceName
    if (matches(name, record?.serviceUuids?.map { it.uuid })) {
        FoundBleDevice(name.orEmpty(), result.device.address, result.rssi)
    } else {
        null
    }
}
