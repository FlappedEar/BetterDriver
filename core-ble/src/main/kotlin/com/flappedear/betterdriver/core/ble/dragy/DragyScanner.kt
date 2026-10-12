package com.flappedear.betterdriver.core.ble.dragy

import android.bluetooth.le.ScanRecord
import android.content.Context
import com.flappedear.betterdriver.core.ble.nearbyDevices
import kotlinx.coroutines.flow.Flow

/**
 * Dragys ([isDragy]: name, `FD00` service or manufacturer data) heard in the shared scan
 * (`BleScan`) in the last 5 s, strongest first, while collected. Emits an empty list first, and
 * nothing more if Bluetooth is off or a permission is missing; the app checks permissions before
 * collecting. Like `scanRaceBoxes`.
 */
fun scanDragys(context: Context): Flow<List<FoundDragy>> =
    nearbyDevices(context, FoundDragy::address, FoundDragy::rssi) { result ->
        val record = result.scanRecord
        val name = record?.deviceName
        if (isDragy(name, record?.serviceUuids.orEmpty().map { it.uuid }, manufacturerBytes(record))) {
            FoundDragy(name ?: result.device.address, result.device.address, result.rssi)
        } else {
            null
        }
    }

/** All manufacturer-specific data of an advertisement, concatenated, or null if there is none. */
private fun manufacturerBytes(record: ScanRecord?): ByteArray? {
    val data = record?.manufacturerSpecificData ?: return null
    return (0 until data.size()).fold(ByteArray(0)) { acc, i -> acc + (data.valueAt(i) ?: ByteArray(0)) }
        .takeIf { it.isNotEmpty() }
}
