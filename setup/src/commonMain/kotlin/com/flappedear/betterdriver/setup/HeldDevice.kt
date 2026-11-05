package com.flappedear.betterdriver.setup

// The devices whose Bluetooth LE link the app holds open from the moment they are chosen until it
// closes (Arek 2026-11-05, FBBD-463, FBBD-464): a vLinker can need a restart after its link drops, a
// RaceBox loses its fix and an 808S strap switches itself off without a receiver. The same devices a
// session starts with the choice at START.

/** A device to hold, by its [address] (Android's Bluetooth address, the iPhone's identifier). */
data class HeldDevice(val address: String, val name: String)

/** GPS receivers reached over Bluetooth LE; NMEA and VBOX Sport are Bluetooth Classic. */
private val BleGnss = setOf(
    EquipmentModel.RACEBOX,
    EquipmentModel.LNS_GNSS,
    EquipmentModel.DIY_GNSS,
    EquipmentModel.DRAGY,
)

/**
 * The listed devices to hold for [setup]: the chosen GPS receiver when it is a Bluetooth LE one, the
 * Bluetooth OBD-II adapters the session would try, the chosen heart-rate strap and the tyre bridge.
 * [bleObd] tells whether an OBD-II adapter's address is a Bluetooth LE one (Android also lists
 * Classic adapters under the same model). Each address once, in that order.
 */
fun heldDevices(setup: Setup, bleObd: (String) -> Boolean = { true }): List<HeldDevice> {
    val active = setup.active()
    val gnss = listOfNotNull(active.gnss?.takeIf { it.model in BleGnss })
    val obd = active.obdBluetooth.filter { bleObd(it.address.orEmpty()) }
    return (gnss + obd + listOfNotNull(active.heartRateStrap, active.tpmsBridge))
        .mapNotNull { e -> e.address?.takeIf { it.isNotBlank() }?.let { HeldDevice(it, e.name) } }
        .distinctBy { it.address.uppercase() }
}
