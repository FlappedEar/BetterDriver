package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.map.DriveMap
import com.flappedear.betterdriver.format.fixed
import kotlin.math.roundToInt

private const val MetersPerKm = 1000.0
private const val SecondsPerMinute = 60
private const val MinutesPerHour = 60

/** Below this the distance is given to the metre, above it in km. */
private const val MetersShownBelow = 1000.0

/**
 * The figures of a drive with no laps (FBBD-342), as both apps show them over its map: distance,
 * time from the first fix to the last, average speed while moving and top speed.
 */
data class DriveFigures(val distance: String, val time: String, val averageSpeed: String, val topSpeed: String)

fun driveFigures(drive: DriveMap): DriveFigures = DriveFigures(
    distance = distanceText(drive.distanceMeters),
    time = durationText(drive.durationSeconds),
    averageSpeed = drive.averageMovingKmh?.let { "${it.roundToInt()} km/h" } ?: "–",
    topSpeed = drive.topSpeedKmh?.let { "${it.roundToInt()} km/h" } ?: "–",
)

/** "850 m", "12.4 km". */
fun distanceText(meters: Double): String =
    if (meters < MetersShownBelow) "${meters.roundToInt()} m" else "${fixed(meters / MetersPerKm, 1)} km"

/** "45 s", "23 min", "1 h 05 min". */
fun durationText(seconds: Double): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    val minutes = total / SecondsPerMinute
    return when {
        minutes == 0 -> "$total s"
        minutes < MinutesPerHour -> "$minutes min"
        else -> "${minutes / MinutesPerHour} h ${(minutes % MinutesPerHour).toString().padStart(2, '0')} min"
    }
}
