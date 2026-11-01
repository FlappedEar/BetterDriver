package com.flappedear.betterdriver.analysis.weather

import com.flappedear.betterdriver.math.roundHalfUp
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The Open-Meteo weather API (FBBD-403): free without a key for non-commercial use, under 10 000
 * calls a day, data under CC BY 4.0, so the app names Open-Meteo where it shows the weather
 * (open-meteo.com/en/terms, checked 2026-11-01). One request asks for the 15-minute values of whole
 * UTC days; the session keeps the quarter hours it needs.
 */
object OpenMeteo {
    /** Shown under the weather: the licence asks for the source to be named. */
    const val Credit = "Weather data by Open-Meteo.com (model, CC BY 4.0)"

    /** Days back the forecast API still serves; an older session asks the historical forecast API. */
    const val ForecastDaysBack = 60

    private const val ForecastUrl = "https://api.open-meteo.com/v1/forecast"
    private const val HistoricalUrl = "https://historical-forecast-api.open-meteo.com/v1/forecast"

    /** The model's variables, in the order of [WeatherReading]'s fields. */
    private val Variables = listOf(
        "temperature_2m",
        "relative_humidity_2m",
        "rain",
        "precipitation",
        "wind_speed_10m",
        "wind_direction_10m",
        "wind_gusts_10m",
        "shortwave_radiation",
        "weather_code",
    )

    /** The position as sent: rounded to 0.01° (about 1 km), so no more than the place is told. */
    fun rounded(degrees: Double): Double = roundHalfUp(degrees * PerDegree) / PerDegree

    /**
     * The request for the weather at [latitude], [longitude] from [fromUtcMillis] to [toUtcMillis]
     * (whole UTC days are asked for), made at [nowUtcMillis]. Null for a place that is no place.
     */
    fun url(latitude: Double, longitude: Double, fromUtcMillis: Long, toUtcMillis: Long, nowUtcMillis: Long): String? {
        if (!validPlace(latitude, longitude)) return null
        val base = if (nowUtcMillis - fromUtcMillis > ForecastDaysBack * MillisPerDay) HistoricalUrl else ForecastUrl
        val lat = rounded(latitude)
        val lon = rounded(longitude)
        return base +
            "?latitude=${degreesText(lat)}&longitude=${degreesText(lon)}" +
            "&minutely_15=${Variables.joinToString(",")}" +
            "&start_date=${utcDate(fromUtcMillis)}&end_date=${utcDate(toUtcMillis)}" +
            "&timezone=GMT&wind_speed_unit=kmh&temperature_unit=celsius&precipitation_unit=mm"
    }

    /**
     * The readings in Open-Meteo's answer [body], in time order; empty when it holds none. Throws
     * [IllegalArgumentException] for an answer that is not Open-Meteo's (an error page, a captive
     * portal), so it is never kept as weather.
     */
    @Suppress("MagicNumber") // The column of each of WeatherReading's fields, in Variables' order.
    fun readings(body: String): List<WeatherReading> {
        val root = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not a weather answer", e)
        }
        val reason = (root["reason"] as? JsonPrimitive)?.contentOrNull
        require((root["error"] as? JsonPrimitive)?.contentOrNull != "true") { "weather service error: $reason" }
        val block = requireNotNull(root["minutely_15"] as? JsonObject) { "no minutely_15 in the answer" }
        val times = requireNotNull(block["time"] as? JsonArray) { "no times in the answer" }
            .map { (it as? JsonPrimitive)?.contentOrNull?.let(::parseUtc) }
        val columns = Variables.map { name -> (block[name] as? JsonArray)?.jsonArray }
        fun value(column: Int, row: Int): Double? =
            (columns[column]?.getOrNull(row) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        return times.indices.mapNotNull { i ->
            val t = times[i] ?: return@mapNotNull null
            WeatherReading(
                utcMillis = t,
                airC = value(0, i),
                humidityPct = value(1, i),
                rainMm = value(2, i),
                precipitationMm = value(3, i),
                windKmh = value(4, i),
                windFromDeg = value(5, i),
                gustKmh = value(6, i),
                sunWm2 = value(7, i),
                code = value(8, i)?.let { roundHalfUp(it).toInt() },
            ).takeIf { it.hasValues() }
        }.sortedBy { it.utcMillis }
    }

    private fun WeatherReading.hasValues() =
        listOf(airC, humidityPct, rainMm, precipitationMm, windKmh, windFromDeg, gustKmh, sunWm2).any { it != null } ||
            code != null

    private const val PerDegree = 100.0
    private const val MaxLatitude = 90.0
    private const val MaxLongitude = 180.0
    private const val NullIsland = 0.01

    /** A real fix: in range, and not the 0,0 a receiver without a fix reports. */
    fun validPlace(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -MaxLatitude..MaxLatitude && longitude in -MaxLongitude..MaxLongitude &&
            !(kotlin.math.abs(latitude) < NullIsland && kotlin.math.abs(longitude) < NullIsland)

    /** "50.06", "-0.5", "19.94": the shortest text of a value rounded to 0.01. */
    private fun degreesText(value: Double): String {
        val hundredths = roundHalfUp(value * PerDegree)
        val sign = if (hundredths < 0) "-" else ""
        val abs = if (hundredths < 0) -hundredths else hundredths
        val whole = abs / Hundred
        val frac = abs % Hundred
        return when {
            frac == 0L -> "$sign$whole"
            frac % Ten == 0L -> "$sign$whole.${frac / Ten}"
            else -> "$sign$whole.${frac.toString().padStart(2, '0')}"
        }
    }

    private const val Hundred = 100L
    private const val Ten = 10L
}

internal const val MillisPerMinute = 60_000L
internal const val MillisPerHour = 60 * MillisPerMinute
internal const val MillisPerDay = 24 * MillisPerHour

/** "2026-11-01": the UTC day of [utcMillis]. */
internal fun utcDate(utcMillis: Long): String {
    val (y, m, d) = civil(utcMillis.floorDiv(MillisPerDay))
    return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

/** "2026-11-01T08:15" in UTC as epoch milliseconds; null when it is not such a time. */
@Suppress("DestructuringDeclarationWithTooManyEntries") // Year, month, day, hour, minute.
internal fun parseUtc(text: String): Long? {
    val m = UtcTime.matchEntire(text) ?: return null
    val (y, mo, d, h, mi) = m.destructured
    val day = daysFromCivil(y.toLong(), mo.toInt(), d.toInt())
    return day * MillisPerDay + h.toLong() * MillisPerHour + mi.toLong() * MillisPerMinute
}

private val UtcTime = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::\d{2})?Z?""")

// Howard Hinnant's civil calendar algorithms, as in VboText.
@Suppress("MagicNumber")
private fun civil(days: Long): Triple<Long, Int, Int> {
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return Triple(yoe + era * 400 + if (m <= 2) 1 else 0, m, d)
}

@Suppress("MagicNumber")
private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = y.floorDiv(400L)
    val yoe = y - era * 400
    val mp = if (month > 2) month - 3 else month + 9
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}
