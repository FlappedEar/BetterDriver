package com.flappedear.betterdriver.core.ble.lns

import com.flappedear.betterdriver.core.ble.FoundRaceBox
import com.flappedear.betterdriver.core.ble.advertisesService
import com.flappedear.betterdriver.core.ble.sigUuid
import java.util.UUID

/** Bluetooth SIG assigned numbers of the Location and Navigation Service (FBBD-114). */
object LnsGatt {
    /** Location and Navigation Service. */
    val SERVICE: UUID = sigUuid(0x1819)

    /** Location and Speed: notified, one value per fix. */
    val LOCATION_AND_SPEED: UUID = sigUuid(0x2A67)
}

/**
 * Whether a BLE advertisement is from a GNSS receiver the [LnsSource] can record: it lists the
 * Location and Navigation Service among its service UUIDs ([serviceUuids], null when it lists none).
 * RaceBoxes are left out even if a future firmware adds the service, because they have their own,
 * richer source.
 */
fun isLnsAdvertisement(name: String?, serviceUuids: Collection<UUID>?): Boolean =
    advertisesService(serviceUuids, LnsGatt.SERVICE) && !FoundRaceBox.isRaceBox(name)
