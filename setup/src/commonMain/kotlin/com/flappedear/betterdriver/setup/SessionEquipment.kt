package com.flappedear.betterdriver.setup

/** A Wi-Fi OBD-II adapter as listed, and the address it is reached at. */
data class WifiObd(val adapter: Equipment, val at: TcpAddress)

/**
 * Which OBD-II adapters and which heart-rate strap a session uses (FBBD-121 UI-105 C, FBBD-119,
 * FBBD-349): only the ones listed in Settings › Equipment, on Android and on iOS. Nothing listed,
 * nothing used: no adapter used before, nearby or remembered is tried, so an item removed from
 * Equipment stops recording from the next session on.
 */
object SessionEquipment {
    /** The Bluetooth adapters, in the order they are listed: one without an address cannot be reached. */
    fun obdBluetooth(carData: List<Equipment>): List<Equipment> =
        carData.filter { it.model == EquipmentModel.OBD_BLUETOOTH && !it.address.isNullOrBlank() }

    /** The Wi-Fi adapters, after the Bluetooth ones; one whose address is not "host:port" is left out. */
    fun obdWifi(carData: List<Equipment>): List<WifiObd> =
        carData.filter { it.model == EquipmentModel.OBD_WIFI }.mapNotNull { e ->
            e.address?.let(TcpAddress::parse)?.let { WifiObd(e, it) }
        }

    /**
     * The strap recorded: the first listed heart-rate monitor with an address. Heart rate has fixed
     * channel ids, so a second strap would merge into the same stream; null when none is listed.
     */
    fun heartRateStrap(sensors: List<Equipment>): Equipment? =
        sensors.firstOrNull { it.model == EquipmentModel.HEART_RATE && !it.address.isNullOrBlank() }
}
