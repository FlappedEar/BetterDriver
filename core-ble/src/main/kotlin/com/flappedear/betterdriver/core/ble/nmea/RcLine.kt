@file:Suppress("MagicNumber") // Field positions of the $RC2/$RC3 layouts.

package com.flappedear.betterdriver.core.ble.nmea

/**
 * One RaceChrono DIY data line (FBBD-115), `$RC2` or `$RC3`, as specified in "Tutorial: DIY
 * devices" (https://racechrono.com/article/2572):
 *
 * - `$RC2,[time],[count],[xacc],[yacc],[zacc],[rpm/d1],[d2],[a1],...,[a8]*checksum`
 * - `$RC3,[time],[count],[xacc],[yacc],[zacc],[gyrox],[gyroy],[gyroz],[rpm/d1],[d2],[a1],...,[a15]*checksum`
 *
 * The lines carry no position: a DIY device that has GPS sends NMEA 0183 sentences in the same
 * stream ("mixed output"), and those make the fixes. [timeOfDayMs] is the optional NMEA-format UTC
 * time (`hhmmss.ss`), empty without GPS. [count] is a line counter that wraps from 65535 to 0, so
 * skipped lines can be counted; it is empty in mixed output. Acceleration is in g (1.000 = +1 G),
 * rotation in deg/s, both null for an empty field. [digital] holds rpm/d1 and d2, [analog] a1 to
 * a8 (RC2) or a15 (RC3); their meaning is set by the user in RaceChrono, so the recorder keeps them
 * out of the session for now. Lines use the NMEA checksum and CR LF, and may arrive over Bluetooth
 * SPP or TCP.
 */
data class RcLine(
    val version: Int,
    val timeOfDayMs: Long?,
    val count: Int?,
    val accelXg: Double?,
    val accelYg: Double?,
    val accelZg: Double?,
    val gyroXdps: Double? = null,
    val gyroYdps: Double? = null,
    val gyroZdps: Double? = null,
    val digital: List<Double?> = emptyList(),
    val analog: List<Double?> = emptyList(),
)

/** Fields of each version after the address: time, count, the motion fields, 2 digital, N analog. */
private class RcLayout(val motionFields: Int, val analogFields: Int) {
    val fields: Int get() = 2 + motionFields + DigitalFields + analogFields
}

private const val DigitalFields = 2
private val RcLayouts = mapOf("RC2" to RcLayout(motionFields = 3, analogFields = 8), "RC3" to RcLayout(6, 15))

/** "range -2000000.000 – 2000000.000" for the digital and analog fields, per the tutorial. */
private const val MaxRcValue = 2_000_000.0
private const val MaxCount = 65_535

/** True for the body (between `$` and `*`) of a RaceChrono DIY line. */
fun isRcLine(body: String): Boolean = body.startsWith("RC2,") || body.startsWith("RC3,")

/**
 * Parses the body of a checked `$RC2`/`$RC3` line, or returns null for any other sentence, one
 * that ends before its motion fields or has more fields than its layout, or a malformed count.
 * Missing digital and analog fields at the end are taken as empty, and so is a value that is not a
 * number or out of range.
 */
fun parseRcLine(body: String): RcLine? {
    val f = body.split(',')
    val layout = RcLayouts[f[0]]
    val count = f.getOrElse(2) { "" }
    val countValue = count.toIntOrNull()
    val countOk = count.isEmpty() || countValue != null && countValue in 0..MaxCount
    if (layout == null || f.size !in 3 + layout.motionFields..layout.fields + 1 || !countOk) return null
    val values = (3..layout.fields).map { rcValue(f.getOrElse(it) { "" }) }
    val motion = values.take(layout.motionFields)
    val gyro = motion.size > 3
    return RcLine(
        version = f[0][2].digitToInt(),
        timeOfDayMs = timeOfDay(f.getOrElse(1) { "" }),
        count = countValue,
        accelXg = motion[0],
        accelYg = motion[1],
        accelZg = motion[2],
        gyroXdps = if (gyro) motion[3] else null,
        gyroYdps = if (gyro) motion[4] else null,
        gyroZdps = if (gyro) motion[5] else null,
        digital = values.subList(layout.motionFields, layout.motionFields + DigitalFields),
        analog = values.drop(layout.motionFields + DigitalFields),
    )
}

private fun rcValue(s: String): Double? = s.toDoubleOrNull()?.takeIf { it.isFinite() && it in -MaxRcValue..MaxRcValue }
