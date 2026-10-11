@file:Suppress("MagicNumber", "LongParameterList")

package com.flappedear.betterdriver.core.ble.diy

import kotlin.math.roundToInt

/**
 * Builds GPS main and GPS time values the way a DIY device does, with the equations of the API
 * README (https://github.com/aollin/racechrono-ble-diy-device). Null means the invalid value.
 */
object DiyTestData {
    fun main(
        sync: Int,
        minute: Int,
        second: Int,
        ms: Int,
        fixQuality: Int,
        satellites: Int?,
        latDeg: Double?,
        lonDeg: Double?,
        altitudeM: Double?,
        speedKmh: Double?,
        bearingDeg: Double?,
        hdop: Double?,
        vdop: Double?,
    ): ByteArray {
        val time = minute * 30_000 + second * 500 + ms / 2
        val head = (sync and 0x7) shl 21 or (time and 0x1F_FFFF)
        val out = ByteArray(20)
        put(out, 0, head, 3)
        out[3] = ((fixQuality and 0x3) shl 6 or ((satellites ?: 0x3F) and 0x3F)).toByte()
        put(out, 4, latDeg?.let { (it * 10_000_000).roundToInt() } ?: 0x7FFF_FFFF, 4)
        put(out, 8, lonDeg?.let { (it * 10_000_000).roundToInt() } ?: 0x7FFF_FFFF, 4)
        put(out, 12, altitudeM?.let(::altitude) ?: 0xFFFF, 2)
        put(out, 14, speedKmh?.let(::speed) ?: 0xFFFF, 2)
        put(out, 16, bearingDeg?.let { (it * 100).roundToInt() } ?: 0xFFFF, 2)
        out[18] = (hdop?.let { (it * 10).roundToInt() } ?: 0xFF).toByte()
        out[19] = (vdop?.let { (it * 10).roundToInt() } ?: 0xFF).toByte()
        return out
    }

    fun time(sync: Int, year: Int, month: Int, day: Int, hour: Int): ByteArray {
        val hours = (year - 2000) * 8928 + (month - 1) * 744 + (day - 1) * 24 + hour
        return ByteArray(3).also { put(it, 0, (sync and 0x7) shl 21 or (hours and 0x1F_FFFF), 3) }
    }

    /** ((m + 500) × 10) & 0x7FFF while it fits, else ((m + 500) & 0x7FFF) | 0x8000. */
    private fun altitude(m: Double): Int {
        val fine = ((m + 500) * 10).roundToInt()
        return if (fine <= 0x7FFF) fine else ((m + 500).roundToInt() and 0x7FFF) or 0x8000
    }

    /** (km/h × 100) & 0x7FFF while it fits, else ((km/h × 10) & 0x7FFF) | 0x8000. */
    private fun speed(kmh: Double): Int {
        val fine = (kmh * 100).roundToInt()
        return if (fine <= 0x7FFF) fine else ((kmh * 10).roundToInt() and 0x7FFF) or 0x8000
    }

    /** Big-endian. */
    private fun put(out: ByteArray, offset: Int, value: Int, bytes: Int) {
        for (i in 0 until bytes) out[offset + i] = (value ushr (8 * (bytes - 1 - i))).toByte()
    }
}
