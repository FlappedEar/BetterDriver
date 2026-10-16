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
 * - Poland: GUGiK's orthophoto through the Geoportal WMS, free and keyless; its terms forbid only
 *   automated downloading and collecting of images, so tiles are kept only as they are viewed.
 *   Each request is drawn by the server, so tiles come as 512 px images: four times fewer requests.
 * - OpenStreetMap's street map, everywhere, as the last resort.
 */
object TileSources {
    private const val OrthophotoWms =
        "https://mapy.geoportal.gov.pl/wss/service/PZGIK/ORTO/WMS/StandardResolution"
    private const val OrthophotoPx = 512
    private const val MapTilerSatellite = "https://api.maptiler.com/maps/satellite/256"

    /** The WMS's EPSG:3857 extent from its capabilities: imagery exists only inside it. */
    private val OrthophotoExtent = doubleArrayOf(1_572_152.37, 6_275_208.65, 2_687_896.25, 7_330_061.09)

    val Orthophoto = TileSource(
        id = "gugik-orto-512",
        name = "Orthophoto (GUGiK)",
        attribution = "Ortofotomapa © GUGiK · geoportal.gov.pl",
        maxZoom = 19,
        imagePx = OrthophotoPx,
    ) { z, x, y ->
        val b = WebMercator.tileBounds(z, x, y)
        val bbox = b.joinToString(",") { String.format(Locale.ROOT, "%.3f", it) }
        "$OrthophotoWms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=Raster&STYLES=&CRS=EPSG:3857" +
            "&BBOX=$bbox&WIDTH=$OrthophotoPx&HEIGHT=$OrthophotoPx&FORMAT=image/jpeg"
    }

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

    /** Whether GUGiK's orthophoto covers [p]. */
    fun inPoland(p: GeoPoint): Boolean {
        val m = WebMercator.toMeters(p)
        val e = OrthophotoExtent
        return m.x in e[0]..e[2] && m.y in e[1]..e[3]
    }

    /**
     * The backgrounds for a track at [p], best first: MapTiler's satellite when [mapTilerKey] is
     * set, the orthophoto in Poland, then the street map.
     */
    fun forPlace(p: GeoPoint, mapTilerKey: String? = null): List<TileSource> = listOfNotNull(
        mapTilerKey?.trim()?.takeIf { it.isNotEmpty() }?.let(::mapTiler),
        Orthophoto.takeIf { inPoland(p) },
        OpenStreetMap,
    )
}
