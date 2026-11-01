@file:Suppress("MagicNumber") // Projection formulas and degree ranges.

package com.flappedear.betterdriver.timing.map

import com.flappedear.betterdriver.math.toDegrees
import com.flappedear.betterdriver.math.toRadians
import com.flappedear.betterdriver.timing.geo.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/** Edge of a map tile in pixels. */
const val TileSize = 256

/** Web Mercator's equatorial radius, in metres (EPSG:3857). */
private const val MercatorRadius = 6_378_137.0

/** Web Mercator stops here, so the world is square. */
private const val MaxMercatorLatitude = 85.051_128_78

/** Half the width of the Web Mercator world, in metres. */
private const val HalfWorldMeters = PI * MercatorRadius

/** A position in the Web Mercator world in pixels at some zoom: (0, 0) is the top-left corner. */
data class WorldPx(val x: Double, val y: Double)

/** A point in EPSG:3857, metres east and north of (0, 0). */
data class MercatorMeters(val x: Double, val y: Double)

/**
 * Web Mercator (EPSG:3857), the projection of slippy map tiles (FBBD-56). At zoom z the world is
 * [TileSize] · 2^z pixels square and split into 2^z × 2^z tiles. Zoom may be fractional.
 */
object WebMercator {
    fun worldSize(zoom: Double): Double = TileSize * 2.0.pow(zoom)

    fun toPx(p: GeoPoint, zoom: Double): WorldPx {
        val size = worldSize(zoom)
        val lat = p.lat.coerceIn(-MaxMercatorLatitude, MaxMercatorLatitude)
        val sinLat = sin(toRadians(lat))
        val y = (0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * PI)) * size
        return WorldPx((p.lon + 180.0) / 360.0 * size, y)
    }

    fun toGeo(px: WorldPx, zoom: Double): GeoPoint {
        val size = worldSize(zoom)
        val lon = px.x / size * 360.0 - 180.0
        val lat = toDegrees(atan(sinh(PI - 2 * PI * px.y / size)))
        return GeoPoint(lat.coerceIn(-MaxMercatorLatitude, MaxMercatorLatitude), wrapLongitude(lon))
    }

    fun toMeters(p: GeoPoint): MercatorMeters {
        val world = toPx(p, 0.0)
        return MercatorMeters(
            world.x / TileSize * 2 * HalfWorldMeters - HalfWorldMeters,
            HalfWorldMeters - world.y / TileSize * 2 * HalfWorldMeters,
        )
    }

    /** Ground metres per pixel at [lat] and [zoom]. */
    fun metersPerPixel(lat: Double, zoom: Double): Double =
        cos(toRadians(lat)) * 2 * HalfWorldMeters / worldSize(zoom)

    /** The EPSG:3857 bounds of tile ([x], [y]) at zoom [z]: min x, min y, max x, max y in metres. */
    fun tileBounds(z: Int, x: Int, y: Int): DoubleArray {
        val span = 2 * HalfWorldMeters / (1 shl z)
        val minX = -HalfWorldMeters + x * span
        val maxY = HalfWorldMeters - y * span
        return doubleArrayOf(minX, maxY - span, minX + span, maxY)
    }

    private fun wrapLongitude(lon: Double): Double {
        val wrapped = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return if (wrapped == -180.0 && lon > 0) 180.0 else wrapped
    }
}

/**
 * Where map tiles come from (UI-26). [url] gives the address of tile ([x], [y]) at zoom [z].
 * [attribution] must be shown on the map. [imagePx] is the edge of the image a tile comes as: a
 * 512 px tile is as sharp as four 256 px ones a zoom deeper, in a quarter of the requests.
 */
class TileSource(
    val id: String,
    val name: String,
    val attribution: String,
    val maxZoom: Int,
    val imagePx: Int = TileSize,
    private val template: (z: Int, x: Int, y: Int) -> String,
) {
    fun url(z: Int, x: Int, y: Int): String = template(z, x, y)
}

/**
 * The map backgrounds (UI-26 option A, satellite; FBBD-230). Checked 2026-10-16:
 * - MapTiler Satellite: sharp satellite imagery everywhere, served fast from a CDN as 512 px
 *   (@2x) images. It needs Arek's MapTiler key, entered on the phone: the key is never in the
 *   repository or the APK.
 * - OpenStreetMap's street map, until a key is entered.
 * - Google's satellite map (Map Tiles API, FBBD-411): checked 2026-11-01 under the Silesia Ring lap,
 *   the only source of those tried whose photo shows the track (Apple's, Esri's and GUGiK's predate
 *   it). It needs Arek's Google key, entered on the device, and a session ([GoogleMapTiles]).
 * GUGiK's orthophoto WMS was dropped: Arek found it far too slow (2026-10-16).
 */
object TileSources {
    private const val MapTilerSatellite = "https://api.maptiler.com/maps/satellite/256"

    val OpenStreetMap = TileSource(
        id = "osm",
        name = "OpenStreetMap",
        attribution = "© OpenStreetMap contributors",
        maxZoom = 19,
    ) { z, x, y -> "https://tile.openstreetmap.org/$z/$x/$y.png" }

    /** MapTiler's satellite map with the key [key]. Cached tiles do not depend on the key. */
    fun mapTiler(key: String): TileSource = TileSource(
        id = "maptiler-satellite",
        name = "Satellite (MapTiler)",
        attribution = "© MapTiler © OpenStreetMap contributors",
        maxZoom = 20,
        imagePx = 2 * TileSize,
    ) { z, x, y -> "$MapTilerSatellite/$z/$x/$y@2x.jpg?key=$key" }

    /**
     * Google's satellite map with the key [key] and a [session] from [GoogleMapTiles.createSessionUrl].
     * Google's terms allow no store of its tiles beyond what their HTTP headers say, so they are not
     * kept on disk.
     */
    fun googleSatellite(key: String, session: String): TileSource = TileSource(
        id = "google-satellite",
        name = "Satellite (Google)",
        attribution = "Google Maps",
        maxZoom = 22,
        imagePx = 2 * TileSize,
    ) { z, x, y -> "${GoogleMapTiles.HOST}/v1/2dtiles/$z/$x/$y?session=$session&key=$key" }

    /** The backgrounds, best first: MapTiler's satellite when [mapTilerKey] is set, then the street map. */
    fun available(mapTilerKey: String? = null): List<TileSource> = listOfNotNull(
        mapTilerKey?.trim()?.takeIf { it.isNotEmpty() }?.let(::mapTiler),
        OpenStreetMap,
    )
}

/**
 * Google's Map Tiles API (FBBD-411): a tile request needs a session, made once with a POST of
 * [SESSION_REQUEST] to [createSessionUrl]; the answer's "session" goes into every tile's address
 * ([TileSources.googleSatellite]) until its "expiry" (seconds since 1970). The picture's credit
 * is "Google Maps" with the copyright text from [viewportUrl] for the area shown.
 */
object GoogleMapTiles {
    const val HOST = "https://tile.googleapis.com"

    /** A satellite map without labels, in 512 px tiles (as MapTiler's @2x ones). */
    const val SESSION_REQUEST =
        """{"mapType":"satellite","language":"en-GB","region":"PL","scale":"scaleFactor2x","highDpi":true}"""

    fun createSessionUrl(key: String): String = "$HOST/v1/createSession?key=$key"

    /** [SESSION_REQUEST] for Swift, which reaches functions of an object more surely than its constants. */
    fun sessionRequest(): String = SESSION_REQUEST

    /** The copyright of the photos in the area between [north], [south], [east] and [west] at [zoom]. */
    fun viewportUrl(
        key: String,
        session: String,
        zoom: Int,
        north: Double,
        south: Double,
        east: Double,
        west: Double,
    ): String = "$HOST/tile/v1/viewport?session=$session&key=$key&zoom=$zoom" +
        "&north=$north&south=$south&east=$east&west=$west"
}
