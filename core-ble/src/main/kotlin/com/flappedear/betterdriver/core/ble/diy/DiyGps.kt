package com.flappedear.betterdriver.core.ble.diy

import com.flappedear.betterdriver.core.ble.GattProfile
import com.flappedear.betterdriver.core.ble.bluetoothUuid
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The RaceChrono BLE DIY API (FBBD-118), as specified in the README of
 * https://github.com/aollin/racechrono-ble-diy-device ("RaceChrono BLE DIY APIs"). One service,
 * 0x1FF8; all values big-endian and unsigned unless noted.
 *
 * Only the GPS feature is used: GPS main (0x0003) and GPS time (0x0004), both READ and NOTIFY.
 * Not used yet: CAN-Bus main (0x0001, a little-endian 32-bit packet id and 1 to 16 payload bytes)
 * with its filter (0x0002, write deny all / allow all / allow one PID), which come with CAN logging
 * in Phase 5; and the Monitor API (0x0005 configuration, 0x0006 values), with which a device
 * displays values the app computes.
 */
object DiyGatt {
    val SERVICE: UUID = bluetoothUuid(0x1FF8)
    val CAN_MAIN: UUID = bluetoothUuid(0x0001)
    val CAN_FILTER: UUID = bluetoothUuid(0x0002)
    val GPS_MAIN: UUID = bluetoothUuid(0x0003)
    val GPS_TIME: UUID = bluetoothUuid(0x0004)

    /**
     * GPS main is required. GPS time is subscribed to when it can notify; RaceChrono itself reads
     * (polls) it when needed, so the source reads it too ([DiyGpsDecoder] asks).
     */
    val PROFILE = GattProfile("diy-ble-link", SERVICE, listOf(GPS_MAIN, GPS_TIME), optional = setOf(GPS_TIME))
}

/**
 * The GPS main characteristic (0x0003), 20 bytes. Each field is null when the device sends its
 * "invalid" value. [sync] (3 bits) pairs it with a [DiyGpsTime] of the same sync value.
 * [timeInHourMs] is the time since the start of the UTC hour, sent as
 * minute × 30000 + second × 500 + ms / 2, i.e. in units of 2 ms. [fixQuality] is the 2-bit fix
 * quality (the upper 2 bits of byte 3; the satellites are the lower 6). The README does not define
 * its values; it reads like the NMEA GGA quality (0 none, 1 GPS, 2 differential, ...) capped at 3,
 * so 0 is taken as no fix.
 */
data class DiyGpsMain(
    val sync: Int,
    val timeInHourMs: Long?,
    val fixQuality: Int,
    val satellites: Int?,
    val latDeg: Double?,
    val lonDeg: Double?,
    val altitudeM: Double?,
    val speedKmh: Double?,
    val bearingDeg: Double?,
    val hdop: Double?,
    val vdop: Double?,
) {
    val hasFix: Boolean get() = fixQuality > 0 && latDeg != null && lonDeg != null
}

/**
 * The GPS time characteristic (0x0004): [sync] and the UTC start of the hour, sent as
 * (year − 2000) × 8928 + (month − 1) × 744 + (day − 1) × 24 + hour. [hourStartUtcMs] is null when
 * that is not a real date and hour.
 */
data class DiyGpsTime(val sync: Int, val hourStartUtcMs: Long?)

const val DiyGpsMainBytes = 20
private const val DiyGpsTimeBytes = 3
private const val ByteMask = 0xFF
private const val Low21Bits = 0x1F_FFFF
private const val SyncShift = 21
private const val MsPerTimeUnit = 2L
private const val MsPerHour = 3_600_000L
private const val InvalidCoordinate = 0x7FFF_FFFF
private const val Invalid16 = 0xFFFF
private const val Invalid8 = 0xFF
private const val InvalidSatellites = 0x3F
private const val CoarseBit = 0x8000
private const val Low15Bits = 0x7FFF
private const val AltitudeOffsetM = 500.0
private const val CoordinateScale = 1e7
private const val HoursPerDay = 24
private const val HoursPerMonth = 744
private const val HoursPerYear = 8928
private const val BaseYear = 2000

/** The GPS main value, or null when it is shorter than 20 bytes. */
@Suppress("MagicNumber") // Byte offsets and scales of the GPS main layout.
fun parseDiyGpsMain(b: ByteArray): DiyGpsMain? {
    if (b.size < DiyGpsMainBytes) return null
    val head = u24(b, 0)
    val time = (head and Low21Bits) * MsPerTimeUnit
    val lat = s32(b, 4)
    val lon = s32(b, 8)
    val alt = u16(b, 12)
    val speed = u16(b, 14)
    val bearing = u16(b, 16)
    return DiyGpsMain(
        sync = head ushr SyncShift,
        timeInHourMs = time.takeIf { it < MsPerHour },
        fixQuality = u8(b, 3) ushr 6,
        satellites = (u8(b, 3) and InvalidSatellites).takeIf { it != InvalidSatellites },
        latDeg = if (lat == InvalidCoordinate) null else lat / CoordinateScale,
        lonDeg = if (lon == InvalidCoordinate) null else lon / CoordinateScale,
        altitudeM = when {
            alt == Invalid16 -> null
            alt and CoarseBit == 0 -> alt / 10.0 - AltitudeOffsetM
            else -> (alt and Low15Bits) - AltitudeOffsetM
        },
        speedKmh = when {
            speed == Invalid16 -> null
            speed and CoarseBit == 0 -> speed / 100.0
            else -> (speed and Low15Bits) / 10.0
        },
        bearingDeg = if (bearing == Invalid16) null else bearing / 100.0,
        hdop = u8(b, 18).takeIf { it != Invalid8 }?.let { it / 10.0 },
        vdop = u8(b, 19).takeIf { it != Invalid8 }?.let { it / 10.0 },
    )
}

/** The GPS time value, or null when it is shorter than 3 bytes. */
fun parseDiyGpsTime(b: ByteArray): DiyGpsTime? {
    if (b.size < DiyGpsTimeBytes) return null
    val head = u24(b, 0)
    val hours = head and Low21Bits
    val year = BaseYear + hours / HoursPerYear
    val month = hours % HoursPerYear / HoursPerMonth + 1
    val day = hours % HoursPerMonth / HoursPerDay + 1
    val hour = hours % HoursPerDay
    val start = try {
        LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + hour * MsPerHour
    } catch (_: DateTimeException) {
        null
    }
    return DiyGpsTime(head ushr SyncShift, start)
}

private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and ByteMask

@Suppress("MagicNumber")
private fun u16(b: ByteArray, i: Int): Int = u8(b, i) shl 8 or u8(b, i + 1)

@Suppress("MagicNumber")
private fun u24(b: ByteArray, i: Int): Int = u8(b, i) shl 16 or u16(b, i + 1)

@Suppress("MagicNumber")
private fun s32(b: ByteArray, i: Int): Int = u16(b, i) shl 16 or u16(b, i + 2)
