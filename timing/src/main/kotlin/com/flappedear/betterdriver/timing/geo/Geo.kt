package com.flappedear.betterdriver.timing.geo

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius (IUGG), in metres. */
const val EarthRadiusMeters = 6_371_008.8

const val MaxLatitude = 90.0
const val MaxLongitude = 180.0

/** A WGS84 position in degrees. */
@Serializable
data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat.isFinite() && lat in -MaxLatitude..MaxLatitude) { "latitude out of range: $lat" }
        require(lon.isFinite() && lon in -MaxLongitude..MaxLongitude) { "longitude out of range: $lon" }
    }
}

/** Great-circle distance in metres (haversine). */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
    return 2 * EarthRadiusMeters * asin(min(1.0, sqrt(h)))
}

/** A point in a [LocalFrame], in metres: x east, y north. */
data class Xy(val x: Double, val y: Double) {
    operator fun minus(o: Xy) = Xy(x - o.x, y - o.y)
    operator fun plus(o: Xy) = Xy(x + o.x, y + o.y)
    operator fun times(k: Double) = Xy(x * k, y * k)
    fun dot(o: Xy) = x * o.x + y * o.y

    /** z of the 3D cross product: positive when [o] is counter-clockwise from this. */
    fun cross(o: Xy) = x * o.y - y * o.x
    fun length() = sqrt(x * x + y * y)
}

/**
 * Flat east/north metres around [origin] (equirectangular). Over a circuit (a few km) the error
 * is far below GNSS noise, and line crossings and distances along a lap only need local geometry.
 */
class LocalFrame(val origin: GeoPoint) {
    private val metersPerDegLat = Math.toRadians(1.0) * EarthRadiusMeters
    private val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(origin.lat))

    fun toXy(p: GeoPoint) = Xy((p.lon - origin.lon) * metersPerDegLon, (p.lat - origin.lat) * metersPerDegLat)

    fun toXy(lat: Double, lon: Double) = Xy((lon - origin.lon) * metersPerDegLon, (lat - origin.lat) * metersPerDegLat)

    fun toGeo(p: Xy) = GeoPoint(origin.lat + p.y / metersPerDegLat, origin.lon + p.x / metersPerDegLon)
}
