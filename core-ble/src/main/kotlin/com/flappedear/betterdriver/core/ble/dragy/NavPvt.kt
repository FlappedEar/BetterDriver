@file:Suppress("MagicNumber") // Byte offsets of the u-blox UBX-NAV-PVT payload.

package com.flappedear.betterdriver.core.ble.dragy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/** UBX-NAV-PVT: class 0x01, id 0x07, 92-byte payload (u-blox protocol 15 and later). */
const val NavPvtClass = 0x01
const val NavPvtId = 0x07
const val NavPvtLength = 92

/**
 * One u-blox UBX-NAV-PVT ("Navigation position velocity time solution") message, as the Dragy Pro
 * streams it (FBBD-117), in SI-like units. Little-endian, like all UBX payloads.
 *
 * Layout: u-blox 8 / M8 Receiver Description including Protocol Specification, UBX-13003221
 * R15, section 32.18.14 "UBX-NAV-PVT (0x01 0x07)" (and 3.14.11 of the F10 SPG 6.00 Interface
 * description, UBX-23002975). The table itself could not be read from those PDFs in this session,
 * so every offset used here was checked against two independent readings of it:
 * - u-blox's own ubxlib, `gnss/src/u_gnss_pos.c` `posDecode()` (github.com/u-blox/ubxlib): year 4,
 *   month 6 to second 10, valid 11 (bits 0 and 1), fixType 20, flags 21 (bit 0 gnssFixOK), numSV
 *   23, lon 24, lat 28, hMSL 36, hAcc 40, vAcc 44, gSpeed 60;
 * - `ublox_msgs/msg/NavPVT.msg` (github.com/KumarRobotics/ublox): the full 92-byte layout,
 *   including nano 16 (signed), height 32, headMot 64 (1e-5 deg), sAcc 68, headAcc 72, pDOP 76;
 * - the Dragy reading in jremick/dragy-dash `DragyPacketDecoder.swift` agrees on 20, 23, 24, 28,
 *   36, 40, 60 and 64.
 *
 * Not decoded: velN/velE/velD, flags2, flags3 (its invalidLlh bit is newer than protocol 15 and
 * reserved in the ROS layout), headVeh and magnetic declination.
 * [utcMillis] is null unless the receiver flags both date and time as valid.
 */
data class NavPvt(
    val iTowMs: Long,
    val utcMillis: Long?,
    val fixType: Int,
    val fixOk: Boolean,
    val satellites: Int,
    val latDeg: Double,
    val lonDeg: Double,
    val ellipsoidAltM: Double,
    val mslAltM: Double,
    val horizontalAccM: Double,
    val verticalAccM: Double,
    val groundSpeedMps: Double,
    val headingDeg: Double,
    val speedAccMps: Double,
    val headingAccDeg: Double,
    val pdop: Double,
) {
    /** A fix worth logging: gnssFixOK and a 3D or GNSS + dead reckoning fix. */
    val hasFix: Boolean get() = fixOk && (fixType == Fix3D || fixType == FixGnssDeadReckoning)

    val speedKmh: Double get() = groundSpeedMps * MpsToKmh

    companion object {
        private const val Fix3D = 3
        private const val FixGnssDeadReckoning = 4
        private const val MpsToKmh = 3.6
        private const val DegScale = 1e7
        private const val HeadingScale = 1e5
        private const val Milli = 1_000.0
        private const val Centi = 100.0
        private const val NanosPerMilli = 1_000_000L
        private const val ValidDateTime = 0x03
        private const val FlagFixOk = 0x01
        private const val ByteMask = 0xFF
        private const val ShortMask = 0xFFFF
        private const val IntMask = 0xFFFF_FFFFL

        /** Parses a UBX-NAV-PVT payload; null if it is not 92 bytes. */
        fun parse(payload: ByteArray): NavPvt? {
            if (payload.size != NavPvtLength) return null
            val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            fun u8(i: Int) = b.get(i).toInt() and ByteMask
            fun u16(i: Int) = b.getShort(i).toInt() and ShortMask
            fun u32(i: Int) = b.getInt(i).toLong() and IntMask
            val utc = if (u8(11) and ValidDateTime == ValidDateTime) {
                utc(u16(4), intArrayOf(u8(6), u8(7), u8(8), u8(9), u8(10)), b.getInt(16))
            } else {
                null
            }
            return NavPvt(
                iTowMs = u32(0),
                utcMillis = utc,
                fixType = u8(20),
                fixOk = u8(21) and FlagFixOk != 0,
                satellites = u8(23),
                lonDeg = b.getInt(24) / DegScale,
                latDeg = b.getInt(28) / DegScale,
                ellipsoidAltM = b.getInt(32) / Milli,
                mslAltM = b.getInt(36) / Milli,
                horizontalAccM = u32(40) / Milli,
                verticalAccM = u32(44) / Milli,
                groundSpeedMps = b.getInt(60) / Milli,
                headingDeg = b.getInt(64) / HeadingScale,
                speedAccMps = u32(68) / Milli,
                headingAccDeg = u32(72) / HeadingScale,
                pdop = u16(76) / Centi,
            )
        }

        /** [mdhms] is month, day, hour, minute, second. [nano] is signed (the second can be rounded up). */
        private fun utc(year: Int, mdhms: IntArray, nano: Int): Long? = try {
            val t = LocalDateTime.of(year, mdhms[0], mdhms[1], mdhms[2], mdhms[3], mdhms[4])
            t.toInstant(ZoneOffset.UTC).toEpochMilli() + Math.floorDiv(nano.toLong(), NanosPerMilli)
        } catch (_: DateTimeException) {
            null
        }
    }
}
