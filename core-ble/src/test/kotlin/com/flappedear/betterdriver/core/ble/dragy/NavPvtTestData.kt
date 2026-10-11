@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.dragy

import com.flappedear.betterdriver.core.ble.ubxPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A UBX-NAV-PVT packet built from the u-blox layout (see [NavPvt]), not a capture from a real
 * Dragy. Defaults: 2026-10-11 10:11:12.200 UTC, 3D fix, 14 satellites, 50.2187° N 19.0428° E,
 * 250.5 m MSL, 10.288 m/s (37.04 km/h), heading 90.5°.
 */
@Suppress("LongParameterList")
internal fun navPvtPayload(
    fixType: Int = 3,
    flags: Int = 0x01,
    valid: Int = 0x07,
    nano: Int = 200_000_000,
    sats: Int = 14,
    lon: Int = 190_428_000,
    lat: Int = 502_187_000,
    height: Int = 290_500,
    hMsl: Int = 250_500,
    gSpeed: Int = 10_288,
    headMot: Int = 9_050_000,
): ByteArray {
    val b = ByteBuffer.allocate(NavPvtLength).order(ByteOrder.LITTLE_ENDIAN)
    b.putInt(0, 123_456_000)
    b.putShort(4, 2026.toShort())
    b.put(6, 10).put(7, 11).put(8, 10).put(9, 11).put(10, 12)
    b.put(11, valid.toByte())
    b.putInt(12, 25)
    b.putInt(16, nano)
    b.put(20, fixType.toByte()).put(21, flags.toByte()).put(22, 0xE0.toByte()).put(23, sats.toByte())
    b.putInt(24, lon).putInt(28, lat).putInt(32, height).putInt(36, hMsl)
    b.putInt(40, 1_500).putInt(44, 2_500)
    b.putInt(48, 0).putInt(52, 10_288).putInt(56, 0)
    b.putInt(60, gSpeed).putInt(64, headMot)
    b.putInt(68, 300).putInt(72, 150_000)
    b.putShort(76, 135.toShort())
    return b.array()
}

internal fun navPvtPacket(payload: ByteArray = navPvtPayload()) = ubxPacket(NavPvtClass, NavPvtId, payload)
