@file:Suppress("MagicNumber") // Byte offsets of the documented RaceBox Data Message layout.

package com.flappedear.betterdriver.core.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/** RaceBox Data Message: class 0xFF, id 0x01, 80-byte payload, sent at the GNSS rate (up to 25 Hz). */
const val RaceBoxClass = 0xFF
const val RaceBoxDataId = 0x01
const val RaceBoxDataLength = 80

/**
 * One RaceBox Data Message, in SI-like units. Field layout and scaling are in core-ble/README.md.
 * [utcMillis] is null unless the receiver flags both date and time as valid.
 */
data class RaceBoxData(
    val iTowMs: Long,
    val utcMillis: Long?,
    val fixType: Int,
    val fixOk: Boolean,
    val headingValid: Boolean,
    val coordinatesInvalid: Boolean,
    val satellites: Int,
    val latDeg: Double,
    val lonDeg: Double,
    val wgsAltM: Double,
    val mslAltM: Double,
    val horizontalAccM: Double,
    val verticalAccM: Double,
    val speedMps: Double,
    val headingDeg: Double,
    val speedAccMps: Double,
    val headingAccDeg: Double,
    val pdop: Double,
    /** Raw battery byte. Micro: input voltage × 10. Mini/Mini S: charge % with bit 7 = charging. */
    val batteryStatus: Int,
    val accelXg: Double,
    val accelYg: Double,
    val accelZg: Double,
    val gyroXdps: Double,
    val gyroYdps: Double,
    val gyroZdps: Double,
) {
    /**
     * A position fix worth logging: the receiver says the fix is OK, the fix type is 2D, 3D or
     * GNSS + dead reckoning, and the coordinates are valid.
     */
    val hasFix: Boolean get() = fixOk && fixType in FixTypes2DTo3DDr && !coordinatesInvalid

    val speedKmh: Double get() = speedMps * MpsToKmh

    /** RaceBox Micro reports its supply voltage instead of a battery level. */
    val microInputVolts: Double get() = batteryStatus / VoltScale

    companion object {
        private val FixTypes2DTo3DDr = 2..4
        private const val MpsToKmh = 3.6
        private const val VoltScale = 10.0
        private const val DegScale = 1e7
        private const val HeadingScale = 1e5
        private const val Milli = 1_000.0
        private const val Centi = 100.0
        private const val NanosPerMilli = 1_000_000L
        private const val ValidDate = 0x01
        private const val ValidTime = 0x02
        private const val FlagFixOk = 0x01
        private const val FlagHeadingValid = 0x20
        private const val FlagInvalidLatLon = 0x01
        private const val ByteMask = 0xFF
        private const val ShortMask = 0xFFFF
        private const val IntMask = 0xFFFF_FFFFL

        /** Parses the payload of a RaceBox Data Message; null if it has the wrong length. */
        fun parse(payload: ByteArray): RaceBoxData? {
            if (payload.size != RaceBoxDataLength) return null
            val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            fun u8(i: Int) = b.get(i).toInt() and ByteMask
            fun u16(i: Int) = b.getShort(i).toInt() and ShortMask
            fun u32(i: Int) = b.getInt(i).toLong() and IntMask
            val valid = u8(11)
            val flags = u8(21)
            return RaceBoxData(
                iTowMs = u32(0),
                utcMillis = if (valid and (ValidDate or ValidTime) == (ValidDate or ValidTime)) {
                    utc(u16(4), u8(6), u8(7), u8(8), u8(9), u8(10), b.getInt(16))
                } else {
                    null
                },
                fixType = u8(20),
                fixOk = flags and FlagFixOk != 0,
                headingValid = flags and FlagHeadingValid != 0,
                coordinatesInvalid = u8(66) and FlagInvalidLatLon != 0,
                satellites = u8(23),
                lonDeg = b.getInt(24) / DegScale,
                latDeg = b.getInt(28) / DegScale,
                wgsAltM = b.getInt(32) / Milli,
                mslAltM = b.getInt(36) / Milli,
                horizontalAccM = u32(40) / Milli,
                verticalAccM = u32(44) / Milli,
                speedMps = b.getInt(48) / Milli,
                headingDeg = b.getInt(52) / HeadingScale,
                speedAccMps = u32(56) / Milli,
                headingAccDeg = u32(60) / HeadingScale,
                pdop = u16(64) / Centi,
                batteryStatus = u8(67),
                accelXg = b.getShort(68) / Milli,
                accelYg = b.getShort(70) / Milli,
                accelZg = b.getShort(72) / Milli,
                gyroXdps = b.getShort(74) / Centi,
                gyroYdps = b.getShort(76) / Centi,
                gyroZdps = b.getShort(78) / Centi,
            )
        }

        /** [nano] is signed: the receiver can round the second up and report a negative fraction. */
        @Suppress("LongParameterList")
        private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, nano: Int): Long? = try {
            LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC).toEpochMilli() +
                Math.floorDiv(nano.toLong(), NanosPerMilli)
        } catch (_: DateTimeException) {
            null
        }
    }
}
