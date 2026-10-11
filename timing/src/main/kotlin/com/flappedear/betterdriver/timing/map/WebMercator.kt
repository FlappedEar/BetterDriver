@file:Suppress("MagicNumber") // Projection formulas, degree ranges and the WMS extent.

package com.flappedear.betterdriver.timing.map

import com.flappedear.betterdriver.timing.geo.GeoPoint
import java.util.Locale
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
        val sinLat = sin(Math.toRadians(lat))
        val y = (0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * PI)) * size
        return WorldPx((p.lon + 180.0) / 360.0 * size, y)
    }

    fun toGeo(px: WorldPx, zoom: Double): GeoPoint {
        val size = worldSize(zoom)
        val lon = px.x / size * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI - 2 * PI * px.y / size)))
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
        cos(Math.toRadians(lat)) * 2 * HalfWorldMeters / worldSize(zoom)

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
 * [attribution] must be shown on the map.
 */
class TileSource(
    val id: String,
    val name: String,
    val attribution: String,
    val maxZoom: Int,
    private val template: (z: Int, x: Int, y: Int) -> String,
) {
    fun url(z: Int, x: Int, y: Int): String = template(z, x, y)
}

/**
 * The map backgrounds (UI-26 option A, satellite). Checked 2026-10-11 (FBBD-56):
 * - Poland: GUGiK's orthophoto through the Geoportal WMS, free and keyless; its terms forbid only
 *   automated downloading and collecting of images, so tiles are kept only as they are viewed.
 * - Elsewhere: OpenStreetMap's street map, until a satellite source outside Poland is chosen.
 */
object TileSources {
    private const val OrthophotoWms =
        "https://mapy.geoportal.gov.pl/wss/service/PZGIK/ORTO/WMS/StandardResolution"

    /** The WMS's EPSG:3857 extent from its capabilities: imagery exists only inside it. */
    private val OrthophotoExtent = doubleArrayOf(1_572_152.37, 6_275_208.65, 2_687_896.25, 7_330_061.09)

    val Orthophoto = TileSource(
        id = "gugik-orto",
        name = "Orthophoto",
        attribution = "Ortofotomapa © GUGiK · geoportal.gov.pl",
        maxZoom = 20,
    ) { z, x, y ->
        val b = WebMercator.tileBounds(z, x, y)
        val bbox = b.joinToString(",") { String.format(Locale.ROOT, "%.3f", it) }
        "$OrthophotoWms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=Raster&STYLES=&CRS=EPSG:3857" +
            "&BBOX=$bbox&WIDTH=$TileSize&HEIGHT=$TileSize&FORMAT=image/jpeg"
    }

    val OpenStreetMap = TileSource(
        id = "osm",
        name = "OpenStreetMap",
        attribution = "© OpenStreetMap contributors",
        maxZoom = 19,
    ) { z, x, y -> "https://tile.openstreetmap.org/$z/$x/$y.png" }

    /** The background for a track at [p]: satellite where it is available. */
    fun forPlace(p: GeoPoint): TileSource {
        val m = WebMercator.toMeters(p)
        val e = OrthophotoExtent
        return if (m.x in e[0]..e[2] && m.y in e[1]..e[3]) Orthophoto else OpenStreetMap
    }
}
