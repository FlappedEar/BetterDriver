package com.flappedear.betterdriver.setup

/** A Wi-Fi OBD-II adapter as listed, and the address it is reached at. */
data class WifiObd(val adapter: Equipment, val at: TcpAddress)

/**
 * Which OBD-II adapters, heart-rate strap and tyre pressure bridge a session uses (FBBD-121 UI-105 C,
 * FBBD-119, FBBD-349, FBBD-224): only the ones listed in Settings › Equipment, on Android and on iOS. Nothing listed,
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
     * The adapter the OBD page and the Settings row name (FBBD-423): the listed one the connection
     * is [using] (the connecting or connected adapter's name, by its name or the name it advertises),
     * else the one tried first: the first Bluetooth one, then the first Wi-Fi one, then the first
     * listed car data item. Not simply the first listed: the Wi-Fi adapter listed for setups saved
     * before adapters were equipment would name a Bluetooth adapter's connection as Wi-Fi.
     */
    fun obdShown(carData: List<Equipment>, using: String? = null): Equipment? =
        using?.let { name -> carData.firstOrNull { it.name == name || it.advertisedName == name } }
            ?: obdBluetooth(carData).firstOrNull() ?: obdWifi(carData).firstOrNull()?.adapter ?: carData.firstOrNull()

    /**
     * The strap recorded: the [chosen] one (FBBD-446; the strap added last), else the last listed
     * heart-rate monitor with an address, so a setup saved before the choice records the strap added
     * last too (FBBD-445). Heart rate has fixed channel ids, so a second strap would merge into the
     * same stream; null when none is listed.
     */
    fun heartRateStrap(sensors: List<Equipment>, chosen: String? = null): Equipment? {
        val straps = sensors.filter { it.model == EquipmentModel.HEART_RATE && !it.address.isNullOrBlank() }
        return straps.firstOrNull { it.id == chosen } ?: straps.lastOrNull()
    }

    /**
     * The tyre pressure bridge recorded (FBBD-224): the first listed one with an address. Its eight
     * channels have fixed ids, as heart rate's; null when none is listed.
     */
    fun tpmsBridge(sensors: List<Equipment>): Equipment? =
        sensors.firstOrNull { it.model == EquipmentModel.TPMS_BRIDGE && !it.address.isNullOrBlank() }
}
