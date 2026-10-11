package com.flappedear.betterdriver.core.ble

import java.util.UUID

/** BLE identifiers of the RaceBox Mini, Mini S and Micro (see core-ble/README.md for sources). */
object RaceBoxGatt {
    /** Nordic UART Service: UBX packets. */
    val UART_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    /** Commands to the device (written by the app). Not used yet. */
    val UART_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    /** Data from the device (notifications). */
    val UART_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** Client Characteristic Configuration descriptor, written to turn notifications on. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val NAME_PREFIX = "RaceBox"
    const val MICRO_PREFIX = "RaceBox Micro"
}

/** A RaceBox seen in a scan. [serial] is the advertised name after the model, e.g. "3241". */
data class FoundRaceBox(val name: String, val address: String, val rssi: Int) {
    val isMicro: Boolean get() = name.startsWith(RaceBoxGatt.MICRO_PREFIX)

    val model: String get() = if (isMicro) RaceBoxGatt.MICRO_PREFIX else name.substringBeforeLast(' ', name)

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
        fun isRaceBox(name: String?): Boolean = name != null && name.startsWith(RaceBoxGatt.NAME_PREFIX)
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
