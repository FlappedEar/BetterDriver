@file:Suppress("MagicNumber") // glibc hypot constants and degree ranges.

package com.flappedear.betterdriver.analysis.geo

import com.flappedear.betterdriver.math.roundHalfUp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

// Port of FlappedEar Telemetry packages/telemetry_core/lib/src/geometry.dart (Apache 2.0).

/** A WGS 84 position in degrees. */
data class GeoCoordinate(val latitudeDegrees: Double, val longitudeDegrees: Double)

/** A position in metres east and north of a local origin. */
data class MetricPoint(val eastMeters: Double, val northMeters: Double)

/** The radius Telemetry and Overlays project with; analysis results depend on it. */
const val AnalysisEarthRadiusMeters = 6_371_000.0
const val RadiansPerDegree = PI / 180.0

/** True for a finite coordinate within the latitude and longitude ranges. */
fun isValidCoordinate(c: GeoCoordinate): Boolean =
    c.latitudeDegrees.isFinite() && c.longitudeDegrees.isFinite() &&
        abs(c.latitudeDegrees) <= 90.0 && abs(c.longitudeDegrees) <= 180.0

/** [degrees] of longitude in -180 to 180, moved by whole turns when outside. */
fun wrapLongitudeDegrees(degrees: Double): Double {
    if (degrees >= -180.0 && degrees <= 180.0) return degrees
    return (degrees + 180.0).mod(360.0) - 180.0
}

/** The point halfway between [a] and [b], the short way round in longitude. */
fun geoMidpoint(a: GeoCoordinate, b: GeoCoordinate): GeoCoordinate {
    val latitude = (a.latitudeDegrees + b.latitudeDegrees) / 2.0
    val difference = b.longitudeDegrees - a.longitudeDegrees
    if (abs(difference) <= 180.0) {
        return GeoCoordinate(latitude, (a.longitudeDegrees + b.longitudeDegrees) / 2.0)
    }
    return GeoCoordinate(
        latitude,
        wrapLongitudeDegrees(a.longitudeDegrees + wrapLongitudeDegrees(difference) / 2.0),
    )
}

/** Equirectangular projection of [c] around [origin], in metres. */
fun projectCoordinate(c: GeoCoordinate, origin: GeoCoordinate): MetricPoint = MetricPoint(
    wrapLongitudeDegrees(c.longitudeDegrees - origin.longitudeDegrees) *
        RadiansPerDegree * AnalysisEarthRadiusMeters * cos(origin.latitudeDegrees * RadiansPerDegree),
    (c.latitudeDegrees - origin.latitudeDegrees) * RadiansPerDegree * AnalysisEarthRadiusMeters,
)

/** The inverse of [projectCoordinate]. */
fun unprojectCoordinate(east: Double, north: Double, origin: GeoCoordinate): GeoCoordinate = GeoCoordinate(
    origin.latitudeDegrees + north / AnalysisEarthRadiusMeters / RadiansPerDegree,
    wrapLongitudeDegrees(
        origin.longitudeDegrees +
            east / (AnalysisEarthRadiusMeters * cos(origin.latitudeDegrees * RadiansPerDegree)) / RadiansPerDegree,
    ),
)

/**
 * Euclidean length of (x, y), reproducing glibc's correctly rounded `hypot`, which Overlays and
 * Telemetry use: a cheaper formula is often one ULP off, enough to flip a nearest-point choice.
 * (Java's Math.hypot is also correctly rounded but much slower.)
 */
fun hypot(x0: Double, y0: Double): Double {
    if (!x0.isFinite() || !y0.isFinite()) {
        return if (x0.isInfinite() || y0.isInfinite()) Double.POSITIVE_INFINITY else Double.NaN
    }
    val x = abs(x0)
    val y = abs(y0)
    val ax = if (x < y) y else x
    val ay = if (x < y) x else y
    return when {
        ax > HypotLarge ->
            if (ay <= ax * HypotEpsilon) ax + ay else hypotKernel(ax * HypotScale, ay * HypotScale) / HypotScale
        ay < HypotTiny ->
            if (ax >= ay / HypotEpsilon) ax + ay else hypotKernel(ax / HypotScale, ay / HypotScale) * HypotScale
        ay <= ax * HypotEpsilon -> ax + ay
        else -> hypotKernel(ax, ay)
    }
}

private const val HypotScale = 2.409919865102884e-181 // 2^-600
private const val HypotLarge = 6.703903964971299e+153 // 2^511
private const val HypotTiny = 1.4916681462400413e-154 // 2^-511
private const val HypotEpsilon = 5.551115123125783e-17 // 2^-54

private fun hypotKernel(ax: Double, ay: Double): Double {
    var h = sqrt(ax * ax + ay * ay)
    val t1: Double
    val t2: Double
    if (h <= 2.0 * ay) {
        val delta = h - ay
        t1 = ax * (2.0 * delta - ax)
        t2 = (delta - 2.0 * (ax - ay)) * delta
    } else {
        val delta = h - ax
        t1 = 2.0 * delta * (ax - 2.0 * ay)
        t2 = (4.0 * delta - ay) * ay + delta * delta
    }
    h -= (t1 + t2) / (2.0 * h)
    return h
}

/** `std::lround` for finite values: half away from zero, like Dart's round(). */
internal fun lround(value: Double): Int =
    if (value < 0) -roundHalfUp(-value).toInt() else roundHalfUp(value).toInt()

/** A 2-D vector in metres. */
data class Vector2(val x: Double, val y: Double) {
    operator fun minus(o: Vector2) = Vector2(x - o.x, y - o.y)
    operator fun plus(o: Vector2) = Vector2(x + o.x, y + o.y)
    operator fun times(scale: Double) = Vector2(x * scale, y * scale)
    fun dot(o: Vector2) = x * o.x + y * o.y
    fun cross(o: Vector2) = x * o.y - y * o.x
    val length: Double get() = hypot(x, y)
}
