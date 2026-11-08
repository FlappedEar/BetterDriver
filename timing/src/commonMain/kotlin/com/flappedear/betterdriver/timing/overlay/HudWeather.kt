package com.flappedear.betterdriver.timing.overlay

import kotlin.math.roundToInt

/*
 * The weather plate of the Tech HUD (FBBD-471, UI-320 B, UI-321 B), for the Android painter and the
 * iOS one alike. The values come from the weather the app keeps with each session (FBBD-403,
 * weather.json, Open-Meteo); the analysis turns them into [OverlayWeather], as this module cannot
 * read the weather file itself.
 */

/** The sky as the plate draws it, from the WMO weather code. */
enum class Sky {
    Clear,
    PartlyCloudy,
    Cloudy,
    Fog,
    Rain,
    Snow,
    Thunder,
    ;

    companion object {
        /** The WMO weather code's sky (Open-Meteo's code table); null for a code it does not list. */
        @Suppress("MagicNumber", "CyclomaticComplexMethod") // The WMO table.
        fun of(code: Int): Sky? = when (code) {
            0, 1 -> Clear
            2 -> PartlyCloudy
            3 -> Cloudy
            45, 48 -> Fog
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Rain
            71, 73, 75, 77, 85, 86 -> Snow
            95, 96, 99 -> Thunder
            else -> null
        }
    }
}

/** The track surface from the rain of the two hours before (FBBD-403), as the plate writes it. */
enum class TrackSurface(val text: String) {
    Dry("TRACK DRY"),
    Damp("TRACK DAMP"),
    Wet("TRACK WET"),
}

/**
 * The weather at one moment: air temperature at 2 m in °C, the sky, the track surface, the wind at
 * 10 m in km/h and the direction it comes from in degrees (0 = north), relative humidity in percent.
 * Each is null when the weather model had none.
 */
data class HudWeather(
    val airC: Double? = null,
    val sky: Sky? = null,
    val surface: TrackSurface? = null,
    val windKmh: Double? = null,
    val windFromDeg: Double? = null,
    val humidityPct: Double? = null,
) {
    /** "9°", or a dash; the painter adds a small "C". */
    val airText: String get() = airC?.let { "${it.roundToInt()}°" } ?: HudDash

    /** "12 km/h"; null without wind. */
    val windText: String? get() = windKmh?.let { "${it.roundToInt()} km/h" }

    /** "78%"; null without humidity. */
    val humidityText: String? get() = humidityPct?.let { "${it.roundToInt()}%" }

    /**
     * Where the wind blows to, in degrees clockwise from the top of the picture (north up, as the
     * track map): the arrow's direction. Null without a direction or in calm air.
     */
    val windArrowDeg: Double?
        get() = windFromDeg?.takeIf { (windKmh ?: 0.0) >= CalmKmh }?.let { (it + HalfTurn) % FullTurn }

    private companion object {
        const val HalfTurn = 180.0
        const val FullTurn = 360.0
        const val CalmKmh = 1.0
    }
}

/**
 * A session's weather by session time: each step holds from [steps]' start (µs of session time) to
 * the next one's; before the first, the first holds. Built by the analysis from the session's
 * 15-minute values.
 */
class OverlayWeather(steps: List<Pair<Long, HudWeather>>) {
    private val sorted = steps.sortedBy { it.first }
    private val starts = LongArray(sorted.size) { sorted[it].first }

    val isEmpty: Boolean get() = sorted.isEmpty()

    /** The weather at [sessionMicros]; null when there is none. */
    fun at(sessionMicros: Long): HudWeather? = when {
        sorted.isEmpty() -> null
        sessionMicros < starts[0] -> sorted[0].second
        else -> sorted[floorIndex(starts, sessionMicros)].second
    }
}

/** The weather plate's colours, ARGB: the surface's word and the icons' parts. */
object WeatherColors {
    const val Dry = 0xFFA3A6AD.toInt()
    const val Damp = 0xFFFCB203.toInt()
    const val Wet = 0xFF3D8BFF.toInt()
    const val Sun = 0xFFFCB203.toInt()
    const val Cloud = 0xFFE8EAED.toInt()
    const val DarkCloud = 0xFFA3A6AD.toInt()
    const val Rain = 0xFF3D8BFF.toInt()
    const val Snow = 0xFFFFFFFF.toInt()
    const val Arrow = 0xFFF4F4F4.toInt()

    /** The colour of [s]'s word. */
    fun of(s: TrackSurface): Int = when (s) {
        TrackSurface.Dry -> Dry
        TrackSurface.Damp -> Damp
        TrackSurface.Wet -> Wet
    }
}

/**
 * One part of a weather icon in a 24 by 24 box, y down, as both painters draw it: a filled circle,
 * a round-capped line or a filled polygon, in an ARGB [color].
 */
sealed class IconShape(val color: Int) {
    class Circle(val cx: Float, val cy: Float, val r: Float, color: Int) : IconShape(color)
    class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, color: Int) :
        IconShape(color)
    class Polygon(val points: List<Pair<Float, Float>>, color: Int) : IconShape(color)
}

/** [sky]'s icon in a 24 by 24 box, drawn in order. */
@Suppress("MagicNumber") // The icons' drawing.
fun skyIcon(sky: Sky): List<IconShape> = when (sky) {
    Sky.Clear -> sun(12f, 12f, 5f)
    Sky.PartlyCloudy -> sun(8.5f, 8f, 3.6f) + cloud(0f, WeatherColors.Cloud)
    Sky.Cloudy -> cloud(-4f, WeatherColors.DarkCloud) + cloud(0f, WeatherColors.Cloud)
    Sky.Fog -> listOf(7f, 12f, 17f).mapIndexed { i, y ->
        IconShape.Line(3f + i, y, 21f - i, y, 2f, WeatherColors.Cloud)
    }
    Sky.Rain -> cloud(-4f, WeatherColors.Cloud) + listOf(8f, 12.5f, 17f).map {
        IconShape.Line(it, 17.5f, it - 1.5f, 21.5f, 1.6f, WeatherColors.Rain)
    }
    Sky.Snow -> cloud(-4f, WeatherColors.Cloud) + listOf(8f to 18.5f, 12.5f to 21f, 17f to 18.5f).map { (x, y) ->
        IconShape.Circle(x, y, 1.3f, WeatherColors.Snow)
    }
    Sky.Thunder -> cloud(-4f, WeatherColors.DarkCloud) + IconShape.Polygon(
        listOf(13.5f to 13f, 9f to 19f, 12f to 19f, 10.5f to 23.5f, 16f to 17f, 13f to 17f, 14.5f to 13f),
        WeatherColors.Sun,
    )
}

/** A sun at [cx], [cy] of radius [r] with eight rays. */
@Suppress("MagicNumber")
private fun sun(cx: Float, cy: Float, r: Float): List<IconShape> =
    listOf<IconShape>(IconShape.Circle(cx, cy, r, WeatherColors.Sun)) + (0 until 8).map { i ->
        val a = i * kotlin.math.PI.toFloat() / 4
        val dx = kotlin.math.cos(a)
        val dy = kotlin.math.sin(a)
        val inner = r * 1.45f
        val outer = r * 1.85f
        IconShape.Line(cx + dx * inner, cy + dy * inner, cx + dx * outer, cy + dy * outer, 1.3f, WeatherColors.Sun)
    }

/** A cloud with its flat bottom at y 20, moved [dy] down. */
@Suppress("MagicNumber")
private fun cloud(dy: Float, color: Int): List<IconShape> = listOf(
    IconShape.Circle(8f, 16.5f + dy, 3.5f, color),
    IconShape.Circle(12.5f, 13.5f + dy, 4.8f, color),
    IconShape.Circle(17.2f, 16f + dy, 4f, color),
    IconShape.Polygon(listOf(8f to 16.5f + dy, 17.2f to 16.5f + dy, 17.2f to 20f + dy, 8f to 20f + dy), color),
)

/** The wind arrow in a 24 by 24 box, pointing [deg] clockwise from up (where the wind blows to). */
@Suppress("MagicNumber")
fun windArrow(deg: Double): IconShape.Polygon {
    val a = deg * kotlin.math.PI / 180
    val c = kotlin.math.cos(a).toFloat()
    val s = kotlin.math.sin(a).toFloat()
    // Pointing up, around the box's centre: a head and a shaft.
    val up = listOf(12f to 2f, 19f to 11f, 14f to 11f, 14f to 22f, 10f to 22f, 10f to 11f, 5f to 11f)
    return IconShape.Polygon(
        up.map { (x, y) ->
            val dx = x - 12f
            val dy = y - 12f
            12f + dx * c - dy * s to 12f + dx * s + dy * c
        },
        WeatherColors.Arrow,
    )
}
