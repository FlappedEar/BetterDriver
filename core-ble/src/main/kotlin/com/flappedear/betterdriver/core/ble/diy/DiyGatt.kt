package com.flappedear.betterdriver.core.ble.diy

import com.flappedear.betterdriver.core.ble.GattProfile
import com.flappedear.betterdriver.core.ble.bluetoothUuid
import java.util.UUID

private const val Hex = 16

/**
 * The RaceChrono BLE DIY service's UUIDs ([DiyIds], FBBD-118) and the GATT profile the Android link
 * subscribes with.
 */
object DiyGatt {
    val SERVICE: UUID = bluetoothUuid(DiyIds.SERVICE.toInt(Hex))
    val CAN_MAIN: UUID = bluetoothUuid(DiyIds.CAN_MAIN.toInt(Hex))
    val CAN_FILTER: UUID = bluetoothUuid(DiyIds.CAN_FILTER.toInt(Hex))
    val GPS_MAIN: UUID = bluetoothUuid(DiyIds.GPS_MAIN.toInt(Hex))
    val GPS_TIME: UUID = bluetoothUuid(DiyIds.GPS_TIME.toInt(Hex))

    /**
     * GPS main is required. GPS time is subscribed to when it can notify; RaceChrono itself reads
     * (polls) it when needed, so the source reads it too ([DiyGpsDecoder] asks).
     */
    val PROFILE = GattProfile("diy-ble-link", SERVICE, listOf(GPS_MAIN, GPS_TIME), optional = setOf(GPS_TIME))
}

/** A notification or read result of [uuid] from the Android GATT link. Other characteristics are ignored. */
fun DiyGpsDecoder.feed(uuid: UUID, bytes: ByteArray) {
    when (uuid) {
        DiyGatt.GPS_MAIN -> feedMain(bytes)
        DiyGatt.GPS_TIME -> feedTime(bytes)
    }
}
