package com.flappedear.betterdriver.core.ble.lns

import com.flappedear.betterdriver.core.ble.LeReader
import com.flappedear.betterdriver.core.ble.TruncatedValue
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Bits of the Flags field (uint16). */
private const val SpeedPresent = 1 shl 0
private const val DistancePresent = 1 shl 1
private const val LocationPresent = 1 shl 2
private const val ElevationPresent = 1 shl 3
private const val HeadingPresent = 1 shl 4
private const val RollingTimePresent = 1 shl 5
private const val UtcPresent = 1 shl 6
private const val PositionStatusShift = 7
private const val SpeedDistance3d = 1 shl 9
private const val ElevationSourceShift = 10
private const val HeadingFromCompass = 1 shl 12
private const val TwoBits = 0b11

/** Field scales: speed 1/100 m/s, distance 1/10 m, location 1e-7 deg, elevation and heading 1/100. */
private const val SpeedScale = 0.01
private const val DistanceScale = 0.1
private const val LocationScale = 1e-7
private const val ElevationScale = 0.01
private const val HeadingScale = 0.01

/** The date_time characteristic is 7 bytes: year (uint16), month, day, hours, minutes, seconds. */
private const val DateTimeBytes = 7

/** Position Status values (Flags bits 7 and 8). */
object LnsPositionStatus {
    const val NO_POSITION = 0
    const val OK = 1
    const val ESTIMATED = 2
    const val LAST_KNOWN = 3
}

/** Elevation Source values (Flags bits 10 and 11). */
object LnsElevationSource {
    const val POSITIONING_SYSTEM = 0
    const val BAROMETRIC = 1
    const val DATABASE = 2
    const val OTHER = 3
}

/**
 * One Location and Speed value (characteristic 0x2A67 of the Location and Navigation Service
 * 0x1819), with every field the flags said was present and null for the others.
 *
 * Layout (all little-endian, fields in this order, each only if its flag is set):
 *
 * | Field | Format | Unit | Flag bit |
 * | --- | --- | --- | --- |
 * | Flags | uint16 | | |
 * | Instantaneous Speed | uint16 | 1/100 m/s | 0 |
 * | Total Distance | uint24 | 1/10 m | 1 |
 * | Latitude, Longitude | sint32, sint32 | 1e-7 deg | 2 |
 * | Elevation | sint24 | 1/100 m | 3 |
 * | Heading | uint16 | 1/100 deg | 4 |
 * | Rolling Time | uint8 | s | 5 |
 * | UTC Time | date_time (7 bytes) | | 6 |
 *
 * Flag bits 7–8 are the Position Status, bit 9 the speed and distance format (0 2D, 1 3D),
 * bits 10–11 the Elevation Source and bit 12 the Heading Source (0 movement, 1 magnetic compass).
 *
 * Sources: Bluetooth SIG GATT Specification Supplement, section "Location and Speed"
 * (https://www.bluetooth.com/specifications/gss/), and the Bluetooth SIG GATT XML it replaced,
 * org.bluetooth.characteristic.location_and_speed
 * (https://github.com/oesmith/gatt-xml/blob/master/org.bluetooth.characteristic.location_and_speed.xml);
 * date_time: org.bluetooth.characteristic.date_time (0x2A08) in the same documents.
 */
data class LocationAndSpeed(
    val flags: Int,
    val speedMs: Double? = null,
    val totalDistanceM: Double? = null,
    val latDeg: Double? = null,
    val lonDeg: Double? = null,
    val elevationM: Double? = null,
    val headingDeg: Double? = null,
    val rollingTimeS: Int? = null,
    /** Null when absent or when the date or time is "not known" (0) or out of range. */
    val utcMillis: Long? = null,
) {
    val positionStatus: Int get() = (flags shr PositionStatusShift) and TwoBits
    val is3d: Boolean get() = flags and SpeedDistance3d != 0
    val elevationSource: Int get() = (flags shr ElevationSourceShift) and TwoBits
    val headingFromCompass: Boolean get() = flags and HeadingFromCompass != 0
    val hasLocation: Boolean get() = latDeg != null && lonDeg != null

    companion object {
        /** Decodes one value; null if it is shorter than its flags say. Extra bytes are ignored. */
        fun parse(bytes: ByteArray): LocationAndSpeed? = try {
            read(LeReader(bytes))
        } catch (_: TruncatedValue) {
            null
        }

        private fun read(r: LeReader): LocationAndSpeed {
            val flags = r.uint16()
            fun has(bit: Int) = flags and bit != 0
            val speed = if (has(SpeedPresent)) r.uint16() * SpeedScale else null
            val distance = if (has(DistancePresent)) r.uint24() * DistanceScale else null
            val location = if (has(LocationPresent)) r.sint32() * LocationScale to r.sint32() * LocationScale else null
            return LocationAndSpeed(
                flags = flags,
                speedMs = speed,
                totalDistanceM = distance,
                latDeg = location?.first,
                lonDeg = location?.second,
                elevationM = if (has(ElevationPresent)) r.sint24() * ElevationScale else null,
                headingDeg = if (has(HeadingPresent)) r.uint16() * HeadingScale else null,
                rollingTimeS = if (has(RollingTimePresent)) r.uint8() else null,
                utcMillis = if (has(UtcPresent)) dateTime(r) else null,
            )
        }

        /** Reads a date_time; null if any part is "not known" (0) or the date does not exist. */
        private fun dateTime(r: LeReader): Long? {
            r.need(DateTimeBytes)
            val year = r.uint16()
            val month = r.uint8()
            val day = r.uint8()
            val hour = r.uint8()
            val minute = r.uint8()
            val second = r.uint8()
            if (year == 0 || month == 0 || day == 0) return null
            return try {
                LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: DateTimeException) {
                null
            }
        }
    }
}
