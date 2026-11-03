package com.flappedear.betterdriver.core.ble

import java.util.UUID

/** The largest 16-bit assigned number and how it is written in a UUID. */
private const val MaxAssigned = 0xFFFF
private const val HexRadix = 16
private const val HexDigits = 4

/**
 * The 128-bit UUID of a 16-bit Bluetooth SIG assigned number (services, characteristics,
 * descriptors), on the Bluetooth Base UUID 00000000-0000-1000-8000-00805F9B34FB.
 */
fun sigUuid(assigned: Int): UUID {
    require(assigned in 0..MaxAssigned) { "not a 16-bit assigned number: $assigned" }
    return UUID.fromString("0000${assigned.toString(HexRadix).padStart(HexDigits, '0')}-0000-1000-8000-00805f9b34fb")
}

/** Whether an advertisement lists [service] among its service UUIDs. [advertised] is null when it lists none. */
fun advertisesService(advertised: Collection<UUID>?, service: UUID): Boolean = advertised?.contains(service) == true
