@file:Suppress("MagicNumber") // Field positions and value ranges of the NMEA 0183 sentence layouts.

package com.flappedear.betterdriver.core.ble.nmea

import java.time.DateTimeException
import java.time.LocalDate

/**
 * The sentences the recorder uses, with the fields it needs. Any talker is accepted (GP GPS,
 * GL GLONASS, GA Galileo, GB/BD BeiDou, GN combined, ...): a multi-GNSS receiver reports its fix
 * as GN, a GPS-only one as GP, and both mean the same thing. Times are milliseconds since UTC
 * midnight; positions are degrees, south and west negative.
 */
sealed interface NmeaSentence {
    val talker: String
}

/** Recommended minimum data. [valid] is status A and a mode other than N (not valid, NMEA 2.3+). */
data class Rmc(
    override val talker: String,
    val timeOfDayMs: Long?,
    val valid: Boolean,
    val latDeg: Double?,
    val lonDeg: Double?,
    val speedKnots: Double?,
    val courseDeg: Double?,
    /** Days since 1970-01-01 (UTC), from the ddmmyy field. */
    val epochDay: Long?,
) : NmeaSentence

/** Fix data. [quality] 0 is no fix; [altitudeM] is above mean sea level. */
data class Gga(
    override val talker: String,
    val timeOfDayMs: Long?,
    val latDeg: Double?,
    val lonDeg: Double?,
    val quality: Int,
    val satellites: Int?,
    val hdop: Double?,
    val altitudeM: Double?,
) : NmeaSentence {
    /**
     * 1 GPS, 2 differential, 3 PPS, 4 RTK fixed, 5 RTK float, 6 estimated (dead reckoning). 7 is a
     * position typed in by hand and 8 a simulator: neither is the car.
     */
    val hasFix: Boolean get() = quality in 1..6
}

/** Course and speed over ground. Carries no time: it belongs to the epoch it arrives in. */
data class Vtg(
    override val talker: String,
    val courseDeg: Double?,
    val speedKmh: Double?,
) : NmeaSentence

/** DOP and active satellites. [fixType] is 1 none, 2 2D, 3 3D. Carries no time, like [Vtg]. */
data class Gsa(
    override val talker: String,
    val fixType: Int?,
    val pdop: Double?,
    val hdop: Double?,
) : NmeaSentence

private const val KmhPerKnot = 1.852
private const val MinutesPerDegree = 60.0
private const val MsPerSecond = 1_000.0

/**
 * Parses the body of a checked sentence (between `$` and `*`), or returns null for sentences the
 * recorder does not use, proprietary ones (`$P...`) and ones too short for their layout.
 */
fun parseNmeaSentence(body: String): NmeaSentence? {
    val f = body.split(',')
    val address = f[0]
    if (address.length != 5 || address[0] == 'P') return null
    val talker = address.substring(0, 2)
    return when (address.substring(2)) {
        "RMC" -> if (f.size >= 10) rmc(talker, f) else null
        "GGA" -> if (f.size >= 10) gga(talker, f) else null
        "VTG" -> if (f.size >= 9) vtg(talker, f) else null
        "GSA" -> if (f.size >= 17) gsa(talker, f) else null
        else -> null
    }
}

private fun rmc(talker: String, f: List<String>) = Rmc(
    talker = talker,
    timeOfDayMs = timeOfDay(f[1]),
    valid = f[2] == "A" && f.getOrNull(12) != "N",
    latDeg = coordinate(f[3], f[4], 90.0),
    lonDeg = coordinate(f[5], f[6], 180.0),
    speedKnots = f[7].toDoubleOrNull(),
    courseDeg = f[8].toDoubleOrNull(),
    epochDay = date(f[9]),
)

private fun gga(talker: String, f: List<String>) = Gga(
    talker = talker,
    timeOfDayMs = timeOfDay(f[1]),
    latDeg = coordinate(f[2], f[3], 90.0),
    lonDeg = coordinate(f[4], f[5], 180.0),
    quality = f[6].toIntOrNull() ?: 0,
    satellites = f[7].toIntOrNull(),
    hdop = f[8].toDoubleOrNull(),
    altitudeM = f[9].toDoubleOrNull(),
)

/**
 * `course,T,magnetic,M,knots,N,km/h,K[,mode]`. A pre-NMEA 2.0 VTG without the unit letters is not
 * used, as its fields cannot be told apart. Mode N (not valid) gives no course or speed.
 */
private fun vtg(talker: String, f: List<String>): Vtg? {
    if (f[2] != "T" || f.getOrNull(9) == "N") return null
    val kmh = f[7].toDoubleOrNull() ?: f[5].toDoubleOrNull()?.times(KmhPerKnot)
    return Vtg(talker, f[1].toDoubleOrNull(), kmh)
}

/** `mode,fix type,12 satellite ids,PDOP,HDOP,VDOP[,system id]`. */
private fun gsa(talker: String, f: List<String>) =
    Gsa(talker, f[2].toIntOrNull(), f[15].toDoubleOrNull(), f[16].toDoubleOrNull())

private val TimeOfDayPattern = Regex("""(\d\d)(\d\d)(\d\d(?:\.\d*)?)""")
private val DatePattern = Regex("""(\d\d)(\d\d)(\d\d)""")
private val HemisphereSign = mapOf("N" to 1.0, "E" to 1.0, "S" to -1.0, "W" to -1.0)

/** `hhmmss` with any number of decimals, or null if empty or out of range. */
internal fun timeOfDay(s: String): Long? {
    val (h, m, sec) = TimeOfDayPattern.matchEntire(s)?.destructured ?: return null
    val hours = h.toInt()
    val minutes = m.toInt()
    val seconds = sec.toDouble()
    // Second 60 is a leap second.
    val inRange = hours < 24 && minutes < 60 && seconds < 61.0
    return if (inRange) (hours * 3_600L + minutes * 60L) * 1_000L + Math.round(seconds * MsPerSecond) else null
}

/** `ddmm.mmmm` / `dddmm.mmmm` with its hemisphere letter, as signed degrees, or null. */
internal fun coordinate(value: String, hemisphere: String, limit: Double): Double? {
    val v = value.toDoubleOrNull()
    val sign = HemisphereSign[hemisphere]
    if (v == null || sign == null || v < 0.0) return null
    val degrees = Math.floor(v / 100.0)
    val minutes = v - degrees * 100.0
    val deg = degrees + minutes / MinutesPerDegree
    return if (minutes < MinutesPerDegree && deg <= limit) sign * deg else null
}

/** `ddmmyy` as days since 1970-01-01. Two-digit years are 20yy; receivers here are all younger. */
internal fun date(s: String): Long? {
    val (d, m, y) = DatePattern.matchEntire(s)?.destructured ?: return null
    return try {
        LocalDate.of(2000 + y.toInt(), m.toInt(), d.toInt()).toEpochDay()
    } catch (_: DateTimeException) {
        null
    }
}
