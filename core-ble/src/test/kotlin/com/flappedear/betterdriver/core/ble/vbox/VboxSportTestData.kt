@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.vbox

import java.io.ByteArrayOutputStream

/**
 * Builds RACELOGIC Serial Stream messages from the field list of the VBOX Sport "Serial Data
 * Format" document (see [VboxSportFormat]), big-endian, with the CRC of its Note 1 over `$` to the
 * last data byte. [commaAfterHeader] selects the support centre's `$VBSPT$,nnnniiii,` layout instead
 * of the PDF's `$VBSPT$nnnniiii,`. Not a capture from a real device.
 */
internal fun vboxMessage(
    standard: Int,
    extended: Int,
    data: ByteArray,
    commaAfterHeader: Boolean = false,
): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(VboxSportFormat.HEADER.toByteArray(Charsets.US_ASCII))
    if (commaAfterHeader) out.write(','.code)
    out.write(be(standard.toLong(), 4))
    out.write(be(extended.toLong(), 4))
    out.write(','.code)
    out.write(data)
    val body = out.toByteArray()
    return body + be(VboxSportFormat.crc16(body, 0, body.size).toLong(), 2)
}

/** [value] as [bytes] big-endian bytes (two's complement for negative values). */
internal fun be(value: Long, bytes: Int): ByteArray =
    ByteArray(bytes) { i -> (value shr (8 * (bytes - 1 - i))).toByte() }

/** Data bytes of a message with the Bluetooth default channels. */
@Suppress("LongParameterList")
internal fun bluetoothDefaultData(
    sats: Int = 0x8A,
    ticks: Long = 3_667_220,
    latRaw: Long = 301_312_340,
    lonRaw: Long = -114_256_780,
    speedRaw: Long = 2_000,
    headingRaw: Long = 9_050,
    heightRaw: Long = -1_234,
    hdopRaw: Long = 80,
): ByteArray =
    be(sats.toLong(), 1) + be(ticks, 3) + be(latRaw, 4) + be(lonRaw, 4) + be(speedRaw, 2) + be(headingRaw, 2) +
        be(heightRaw, 3) + be(0, 2) + be(0, 2) + be(0, 2) +
        // Extended 0x71: battery time to empty (not discharging), media capacity, media free, HDOP.
        be(0xFFFF, 2) + be(31_000_000, 4) + be(30_000_000, 4) + be(hdopRaw, 2)

internal fun bluetoothDefaultMessage(commaAfterHeader: Boolean = false, data: ByteArray = bluetoothDefaultData()) =
    vboxMessage(VboxSportFormat.BLUETOOTH_STANDARD, VboxSportFormat.BLUETOOTH_EXTENDED, data, commaAfterHeader)
