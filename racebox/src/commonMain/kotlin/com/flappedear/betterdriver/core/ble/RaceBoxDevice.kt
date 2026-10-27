package com.flappedear.betterdriver.core.ble

/**
 * RaceBox Mini, Mini S and Micro identifiers without a platform UUID type (RaceBox BLE Protocol
 * Description rev 8), for the Android GATT link ([RaceBoxGatt] in core-ble) and iOS CoreBluetooth.
 */
object RaceBoxIds {
    /** Nordic UART Service: UBX packets. */
    const val UART_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    /** Commands to the device. */
    const val UART_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

    /** Data from the device (notifications). */
    const val UART_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    /** Advertised names are one of these, a space and a 10-digit serial number. */
    val NAME_PREFIXES = listOf("RaceBox Mini ", "RaceBox Mini S ", "RaceBox Micro ")
    const val MICRO_PREFIX = "RaceBox Micro"
}

/** A RaceBox seen in a scan. [serial] is the advertised name after the model (10 digits). */
data class FoundRaceBox(val name: String, val address: String, val rssi: Int) {
    val isMicro: Boolean get() = name.startsWith(RaceBoxIds.MICRO_PREFIX)

    val model: String get() = if (isMicro) RaceBoxIds.MICRO_PREFIX else name.substringBeforeLast(' ', name)

    val serial: String get() = name.removePrefix(model).trim()

    /** Signal strength as 0 to 4 bars. Thresholds are a design choice, not from RaceBox. */
    val bars: Int
        get() = when {
            rssi >= -60 -> 4
            rssi >= -70 -> 3
            rssi >= -80 -> 2
            rssi >= -90 -> 1
            else -> 0
        }

    companion object {
        fun isRaceBox(name: String?): Boolean = name != null && RaceBoxIds.NAME_PREFIXES.any(name::startsWith)
    }
}

/**
 * Delays between reconnect attempts: 0.5 s, 1 s, 2 s, then every [maxDelayMs]. Short, because a
 * dropped link loses data and the RaceBox usually comes back within seconds.
 */
class ReconnectPolicy(private val firstDelayMs: Long = 500, private val maxDelayMs: Long = 3_000) {
    private var attempt = 0

    val attempts: Int get() = attempt

    fun nextDelayMs(): Long {
        val delay = (firstDelayMs shl minOf(attempt, MaxShift)).coerceAtMost(maxDelayMs)
        attempt++
        return delay
    }

    /** Call once data flows again. */
    fun reset() {
        attempt = 0
    }

    private companion object {
        const val MaxShift = 10
    }
}
