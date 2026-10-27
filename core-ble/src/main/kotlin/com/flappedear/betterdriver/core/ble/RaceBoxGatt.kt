package com.flappedear.betterdriver.core.ble

import java.util.UUID

/** BLE identifiers of the RaceBox Mini, Mini S and Micro (RaceBox BLE Protocol Description rev 8). */
object RaceBoxGatt {
    /** Nordic UART Service: UBX packets. */
    val UART_SERVICE: UUID = UUID.fromString(RaceBoxIds.UART_SERVICE)

    /** Commands to the device. Only the diagnostics screen writes, and only read-only queries. */
    val UART_RX: UUID = UUID.fromString(RaceBoxIds.UART_RX)

    /** Data from the device (notifications). */
    val UART_TX: UUID = UUID.fromString(RaceBoxIds.UART_TX)

    /** Device Information Service (p. 1): read-only strings. */
    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val MODEL: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val SERIAL: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val FIRMWARE: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val HARDWARE: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
    val MANUFACTURER: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")

    /** Read in this order; the firmware comes before the queries that depend on it. */
    val DEVICE_INFO_CHARACTERISTICS = listOf(MODEL, SERIAL, FIRMWARE, HARDWARE, MANUFACTURER)

    /** Client Characteristic Configuration descriptor, written to turn notifications on. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Advertised names are one of these, a space and a 10-digit serial number. */
    val NAME_PREFIXES = RaceBoxIds.NAME_PREFIXES
    const val MICRO_PREFIX = RaceBoxIds.MICRO_PREFIX
}
