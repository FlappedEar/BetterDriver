package com.flappedear.betterdriver.analysis.weather

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The weather of a session (FBBD-403), the same on Android and iOS: modelled values from
// Open-Meteo (open-meteo.com, CC BY 4.0) for the session's place, one every 15 minutes, kept as
// weather.json in the session's folder. Like FlappedEar Telemetry's B5, only a rounded position and
// the date are sent. Laps are compared across conditions with it, and the live coach reads it.

/**
 * One 15-minute value of the weather model at the session's place, at [utcMillis] (the start of its
 * quarter hour): air temperature at 2 m, relative humidity, rain and all precipitation in the
 * quarter hour before it, wind and gusts at 10 m, the sun's shortwave radiation on the ground, and
 * the WMO weather code. Each value is null when the model had none.
 */
@Serializable
data class WeatherReading(
    val utcMillis: Long,
    val airC: Double? = null,
    val humidityPct: Double? = null,
    val rainMm: Double? = null,
    val precipitationMm: Double? = null,
    val windKmh: Double? = null,
    val windFromDeg: Double? = null,
    val gustKmh: Double? = null,
    val sunWm2: Double? = null,
    val code: Int? = null,
)

/**
 * The weather kept with a session: the readings from [fromUtcMillis] to [toUtcMillis] (two hours
 * before the start, for rain that left the track wet, to the session's end or, while recording, the
 * last fetch), at the rounded place [latitude], [longitude] asked for, fetched at [fetchedUtcMillis].
 */
@Serializable
data class SessionWeather(
    val format: Int = Format,
    val source: String = Source,
    val latitude: Double,
    val longitude: Double,
    val fetchedUtcMillis: Long,
    val fromUtcMillis: Long,
    val toUtcMillis: Long,
    val readings: List<WeatherReading>,
) {
    fun toJson(): String = WeatherJson.encodeToString(serializer(), this)

    /** The readings from [from] to [to], both included. */
    fun between(from: Long, to: Long): List<WeatherReading> = readings.filter { it.utcMillis in from..to }

    /** The reading for [utcMillis]: the quarter hour it falls in, else the nearest one; null when none. */
    fun at(utcMillis: Long): WeatherReading? = readings.minByOrNull {
        val d = utcMillis - it.utcMillis
        if (d >= 0) d else -d + 1
    }

    companion object {
        const val Format = 1
        const val Source = "open-meteo"

        /** The file in the session's folder. */
        const val FileName = "weather.json"

        /** [json] as stored; null when it is not a weather file this version can read. */
        fun fromJson(json: String): SessionWeather? = try {
            WeatherJson.decodeFromString(serializer(), json).takeIf { it.format == Format }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

private val WeatherJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
