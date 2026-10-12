package com.flappedear.betterdriver.core.ble

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * RaceBoxes heard in the shared scan ([BleScan]) in the last 5 s, strongest first, while collected.
 * Matches on the advertised name, so a RaceBox is found even if it does not put its service UUID in
 * the advertisement. Emits an empty list first, and nothing more if Bluetooth is off or a
 * permission is missing; the app checks permissions before collecting.
 */
fun scanRaceBoxes(context: Context): Flow<List<FoundRaceBox>> =
    nearbyDevices(context, FoundRaceBox::address, FoundRaceBox::rssi) { result ->
        result.scanRecord?.deviceName?.takeIf { FoundRaceBox.isRaceBox(it) }?.let {
            FoundRaceBox(it, result.device.address, result.rssi)
        }
    }
