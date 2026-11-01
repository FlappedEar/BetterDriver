@file:Suppress("TooManyFunctions") // The conditions and their words, one function each.

package com.flappedear.betterdriver.analysis.weather

import com.flappedear.betterdriver.math.roundHalfUp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The track surface as the rain says (FBBD-403): no weather station, so this is the model's
 * precipitation over the two hours before, which is what leaves a track wet. Damp from 0.1 mm,
 * wet from 0.5 mm.
 */
enum class Surface(val text: String) {
    Dry("dry"),
    Damp("damp"),
    Wet("wet"),
}

/** Precipitation in the two hours before a moment that makes the track [Surface.Damp] or [Surface.Wet]. */
const val DampMm = 0.1
const val WetMm = 0.5

/** How far back rain still counts on the surface. */
const val WetWindowMillis = 2 * MillisPerHour

/** Before the start, for rain that left the track wet. */
internal const val BeforeStartMillis = WetWindowMillis

/** The surface at [utcMillis]; null when the weather has no readings for the two hours before it. */
fun SessionWeather.surfaceAt(utcMillis: Long): Surface? {
    val window = between(utcMillis - WetWindowMillis + 1, utcMillis + QuarterMillis - 1)
    val sums = window.mapNotNull { it.precipitationMm ?: it.rainMm }
    if (sums.isEmpty()) return null
    val mm = sums.sum()
    return when {
        mm >= WetMm -> Surface.Wet
        mm >= DampMm -> Surface.Damp
        else -> Surface.Dry
    }
}

internal const val QuarterMillis = 15 * MillisPerMinute

/**
 * The weather over a session, from [startUtcMillis] to [endUtcMillis]: the air temperature's lowest
 * and highest, the mean wind with where it came from, the strongest gust, the rain during it, the
 * worst surface and the sky as the model's weather code says most often.
 */
data class SessionConditions(
    val airMinC: Double?,
    val airMaxC: Double?,
    val windKmh: Double?,
    val windFromDeg: Double?,
    val gustKmh: Double?,
    val rainMm: Double?,
    val humidityPct: Double?,
    val sunWm2: Double?,
    val surface: Surface?,
    val sky: String?,
) {
    val airMeanC: Double? get() = if (airMinC != null && airMaxC != null) (airMinC + airMaxC) / 2 else null
}

/** The conditions of the session from [startUtcMillis] to [endUtcMillis]; null when the weather has none for it. */
fun SessionWeather.conditions(startUtcMillis: Long, endUtcMillis: Long): SessionConditions? {
    val end = maxOf(endUtcMillis, startUtcMillis)
    // The quarter hours the session touched: from the one it started in to the one it ended in.
    val during = between(startUtcMillis - QuarterMillis + 1, end + QuarterMillis - 1)
        .ifEmpty { listOfNotNull(at(startUtcMillis)) }
    if (during.isEmpty()) return null
    val air = during.mapNotNull { it.airC }
    val winds = during.filter { it.windKmh != null }
    val rain = during.filter { it.utcMillis in startUtcMillis + 1..end + QuarterMillis - 1 }
        .mapNotNull { it.precipitationMm ?: it.rainMm }
    val surfaces = listOf(startUtcMillis, end).mapNotNull(::surfaceAt) +
        during.mapNotNull { surfaceAt(it.utcMillis) }
    val sky = during.mapNotNull { it.code }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let(::skyText)
    return SessionConditions(
        airMinC = air.minOrNull(),
        airMaxC = air.maxOrNull(),
        windKmh = winds.mapNotNull { it.windKmh }.average().takeIf { winds.isNotEmpty() },
        windFromDeg = meanDirection(winds.mapNotNull { r -> r.windFromDeg?.let { it to (r.windKmh ?: 0.0) } }),
        gustKmh = during.mapNotNull { it.gustKmh }.maxOrNull(),
        rainMm = rain.takeIf { it.isNotEmpty() }?.sum(),
        humidityPct = during.mapNotNull { it.humidityPct }.takeIf { it.isNotEmpty() }?.average(),
        sunWm2 = during.mapNotNull { it.sunWm2 }.takeIf { it.isNotEmpty() }?.average(),
        surface = surfaces.maxByOrNull { it.ordinal },
        sky = sky,
    )
}

/** The mean of [directions] (degrees the wind comes from, each with its speed as weight); null for none or calm. */
@Suppress("ReturnCount") // None, calm, or the mean.
private fun meanDirection(directions: List<Pair<Double, Double>>): Double? {
    if (directions.isEmpty()) return null
    var x = 0.0
    var y = 0.0
    for ((deg, weight) in directions) {
        val w = if (weight > 0) weight else 1.0
        val r = deg * kotlin.math.PI / HalfTurn
        x += w * cos(r)
        y += w * sin(r)
    }
    if (sqrt(x * x + y * y) < Calm) return null
    val deg = atan2(y, x) * HalfTurn / kotlin.math.PI
    return (deg + FullTurn) % FullTurn
}

private const val HalfTurn = 180.0
private const val FullTurn = 360.0
private const val Calm = 1e-6

/** "N", "NE", …: the eight-point compass name of where the wind comes from. */
fun compassName(fromDeg: Double): String {
    val i = roundHalfUp(((fromDeg % FullTurn) + FullTurn) % FullTurn / EighthTurn).toInt() % Compass.size
    return Compass[i]
}

private const val EighthTurn = 45.0
private val Compass = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** The WMO weather code's sky in a word or two (Open-Meteo's code table); null for a code it does not list. */
@Suppress("MagicNumber", "CyclomaticComplexMethod") // The WMO table.
fun skyText(code: Int): String? = when (code) {
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61, 63, 65 -> "Rain"
    66, 67 -> "Freezing rain"
    71, 73, 75, 77 -> "Snow"
    80, 81, 82 -> "Rain showers"
    85, 86 -> "Snow showers"
    95, 96, 99 -> "Thunderstorm"
    else -> null
}

/** "12 °C" or "11–14 °C". */
fun airText(c: SessionConditions): String? {
    val lo = c.airMinC ?: return null
    val hi = c.airMaxC ?: lo
    val a = roundHalfUp(lo)
    val b = roundHalfUp(hi)
    return if (a == b) "$a °C" else "$a–$b °C"
}

/** "18 km/h from W" with ", gusts 35" when the gusts are stronger; null without wind values. */
fun windText(c: SessionConditions): String? {
    val wind = c.windKmh ?: return null
    val from = c.windFromDeg?.let { " from ${compassName(it)}" }.orEmpty()
    val gust = c.gustKmh?.takeIf { roundHalfUp(it) > roundHalfUp(wind) }?.let { ", gusts ${roundHalfUp(it)}" }.orEmpty()
    return "${roundHalfUp(wind)} km/h$from$gust"
}

/** "Dry", "Damp: 0.3 mm in the 2 h before", "Wet: rain 1.2 mm"; null when the rain is not known. */
fun surfaceText(c: SessionConditions): String? {
    val surface = c.surface ?: return null
    val rain = c.rainMm?.takeIf { it >= DampMm }
    return when {
        surface == Surface.Dry -> "Dry"
        rain != null -> "${surface.text.replaceFirstChar(Char::uppercase)}: rain ${mmText(rain)} during the session"
        else -> "${surface.text.replaceFirstChar(Char::uppercase)}: rain in the 2 h before"
    }
}

private fun mmText(mm: Double): String {
    val tenths = roundHalfUp(mm * Tenths)
    return "${tenths / Tenths.toLong()}.${tenths % Tenths.toLong()} mm"
}

private const val Tenths = 10.0

/**
 * This session's conditions against [earlier]'s, the last session at the same place (FBBD-403):
 * "Last time here (12 Oct): 14 °C, dry. Today 6 °C colder, wet." — so a lap time is read with the
 * conditions it was driven in. Null when either has no temperature.
 */
@Suppress("ReturnCount") // Without either temperature there is nothing to compare.
fun versusText(now: SessionConditions, earlier: SessionConditions, earlierDate: String): String? {
    val a = now.airMeanC ?: return null
    val b = earlier.airMeanC ?: return null
    val before = listOfNotNull("${roundHalfUp(b)} °C", earlier.surface?.text).joinToString(", ")
    val diff = roundHalfUp(a - b)
    val warmer = when {
        abs(diff) < 1 -> "same temperature"
        diff > 0 -> "$diff °C warmer"
        else -> "${-diff} °C colder"
    }
    val surface = when {
        now.surface == null || earlier.surface == null -> null
        now.surface == earlier.surface -> null
        else -> now.surface.text
    }
    val wind = windChange(now.windKmh, earlier.windKmh)
    val today = listOfNotNull(warmer, surface, wind).joinToString(", ")
    return "Last time here ($earlierDate): $before. Today $today."
}

/** "windier" or "calmer" when the mean wind differs by 10 km/h or more. */
private fun windChange(now: Double?, before: Double?): String? {
    if (now == null || before == null) return null
    return when {
        now - before >= WindChangeKmh -> "windier"
        before - now >= WindChangeKmh -> "calmer"
        else -> null
    }
}

private const val WindChangeKmh = 10.0

/** "12 Oct": the UTC day of [utcMillis], short. */
fun shortDate(utcMillis: Long): String {
    val iso = utcDate(utcMillis)
    val month = iso.substring(MonthStart, MonthEnd).toInt()
    val day = iso.substring(DayStart).toInt()
    return "$day ${Months[month - 1]}"
}

private const val MonthStart = 5
private const val MonthEnd = 7
private const val DayStart = 8
private val Months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
