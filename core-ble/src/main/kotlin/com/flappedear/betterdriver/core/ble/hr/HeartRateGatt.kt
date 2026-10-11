package com.flappedear.betterdriver.core.ble.hr

import com.flappedear.betterdriver.core.ble.advertisesService
import com.flappedear.betterdriver.core.ble.sigUuid
import java.util.UUID

/** Bluetooth SIG assigned numbers of the Heart Rate Service (FBBD-119). */
object HeartRateGatt {
    /** Heart Rate Service. */
    val SERVICE: UUID = sigUuid(0x180D)

    /** Heart Rate Measurement: notified, about once a second. */
    val MEASUREMENT: UUID = sigUuid(0x2A37)
}

/**
 * Whether a BLE advertisement is from a heart rate monitor (chest strap, arm band, watch in
 * broadcast mode): it lists the Heart Rate Service among its service UUIDs ([serviceUuids], null
 * when it lists none).
 */
fun isHeartRateAdvertisement(serviceUuids: Collection<UUID>?): Boolean =
    advertisesService(serviceUuids, HeartRateGatt.SERVICE)
