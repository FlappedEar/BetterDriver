package com.flappedear.betterdriver.analysis.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A made-up place in open sea, never a real track (the repository is public).
private const val Lat = 40.123456
private const val Lon = -30.987654

/** 2030-06-15T10:00Z. */
private const val Ten = 1_907_748_000_000L

class OpenMeteoTest {
    @Test
    fun asksForTheRoundedPlaceAndWholeUtcDays() {
        val url = OpenMeteo.url(Lat, Lon, Ten - 2 * MillisPerHour, Ten + MillisPerHour, Ten + 2 * MillisPerHour)!!
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=40.12&longitude=-30.99&"), url)
        assertTrue("start_date=2030-06-15&end_date=2030-06-15" in url, url)
        assertTrue("minutely_15=temperature_2m,relative_humidity_2m,rain,precipitation," in url, url)
        assertTrue("timezone=GMT" in url && "wind_speed_unit=kmh" in url, url)
        assertTrue(Lat.toString() !in url && "40.123" !in url, "only the rounded place is sent")
    }

    @Test
    fun anOldSessionAsksTheHistoricalForecast() {
        val now = Ten + (OpenMeteo.ForecastDaysBack + 1) * MillisPerDay
        val url = OpenMeteo.url(Lat, Lon, Ten, Ten + MillisPerHour, now)!!
        assertTrue(url.startsWith("https://historical-forecast-api.open-meteo.com/v1/forecast?"), url)
    }

    @Test
    fun noPlaceNoRequest() {
        assertNull(OpenMeteo.url(0.0, 0.0, Ten, Ten, Ten))
        assertNull(OpenMeteo.url(Double.NaN, 1.0, Ten, Ten, Ten))
        assertNull(OpenMeteo.url(91.0, 1.0, Ten, Ten, Ten))
    }

    @Test
    fun readsTheQuarterHours() {
        val r = OpenMeteo.readings(answer(listOf(10.4, 11.0, null), rain = listOf(0.0, 0.2, null)))
        assertEquals(2, r.size, "a quarter hour without any value is left out")
        assertEquals(Ten, r[0].utcMillis)
        assertEquals(Ten + QuarterMillis, r[1].utcMillis)
        assertEquals(10.4, r[0].airC)
        assertEquals(0.2, r[1].precipitationMm)
        assertEquals(61, r[1].code)
        assertEquals(270.0, r[0].windFromDeg)
    }

    @Test
    fun anErrorOrAnotherPageIsNoWeather() {
        assertFailsWith<IllegalArgumentException> { OpenMeteo.readings("""{"error":true,"reason":"bad"}""") }
        assertFailsWith<IllegalArgumentException> { OpenMeteo.readings("<html>login</html>") }
        assertFailsWith<IllegalArgumentException> { OpenMeteo.readings("""{"latitude":1}""") }
    }

    @Test
    fun utcDaysAndTimes() {
        assertEquals("2030-06-15", utcDate(Ten))
        assertEquals("1970-01-01", utcDate(0))
        assertEquals("1969-12-31", utcDate(-1))
        assertEquals(Ten, parseUtc("2030-06-15T10:00"))
        assertNull(parseUtc("15.06.2030"))
        assertEquals("15 Jun", shortDate(Ten))
    }
}

class ConditionsTest {
    private fun weather(vararg readings: WeatherReading) =
        SessionWeather(
            latitude = 40.12,
            longitude = -30.99,
            fetchedUtcMillis = Ten,
            fromUtcMillis = readings.first().utcMillis,
            toUtcMillis = readings.last().utcMillis,
            readings = readings.toList(),
        )

    private fun at(quarter: Int, air: Double = 12.0, mm: Double = 0.0, wind: Double = 10.0, from: Double = 270.0) =
        WeatherReading(
            Ten + quarter * QuarterMillis, airC = air, precipitationMm = mm, rainMm = mm,
            windKmh = wind, windFromDeg = from, gustKmh = wind * 2, code = 1,
        )

    @Test
    fun dryDampWetFromTheRainOfTheTwoHoursBefore() {
        val w = weather(at(-7, mm = 0.3), at(-4), at(0), at(1, mm = 0.6))
        assertEquals(Surface.Damp, w.surfaceAt(Ten), "0.3 mm 1 h 45 min before")
        assertEquals(Surface.Wet, w.surfaceAt(Ten + QuarterMillis))
        assertEquals(Surface.Dry, weather(at(-1), at(0)).surfaceAt(Ten))
        assertNull(weather(at(10)).surfaceAt(Ten), "no readings before: not known")
    }

    @Test
    fun theSessionsConditionsAndTheirWords() {
        val w = weather(at(0, air = 11.2), at(1, air = 13.6, wind = 20.0, from = 260.0), at(2, air = 12.0))
        val c = w.conditions(Ten, Ten + 2 * QuarterMillis)!!
        assertEquals("11–14 °C", airText(c))
        assertEquals("W", compassName(c.windFromDeg!!))
        assertEquals("13 km/h from W, gusts 40", windText(c))
        assertEquals("Dry", surfaceText(c))
        assertEquals("Mostly clear", c.sky)
        val ui = weatherUi(c)
        assertEquals(false, ui.wet)
        assertTrue("Open-Meteo" in ui.credit)
    }

    @Test
    fun rainDuringTheSession() {
        val w = weather(at(0), at(1, mm = 0.4), at(2, mm = 0.8))
        val c = w.conditions(Ten, Ten + 2 * QuarterMillis)!!
        assertEquals(Surface.Wet, c.surface)
        assertEquals("Wet: rain 1.2 mm during the session", surfaceText(c))
        assertTrue(weatherUi(c).wet)
    }

    @Test
    fun againstTheLastTimeHere() {
        val now = weather(at(0, air = 6.0, mm = 0.6, wind = 25.0)).conditions(Ten, Ten)!!
        val before = weather(at(0, air = 14.0, wind = 5.0)).conditions(Ten, Ten)!!
        assertEquals(
            "Last time here (12 Oct): 14 °C, dry. Today 8 °C colder, wet, windier.",
            versusText(now, before, "12 Oct"),
        )
        assertEquals(
            "Last time here (12 Oct): 14 °C, dry. Today same temperature.",
            versusText(before, before, "12 Oct"),
        )
    }

    @Test
    fun compassPoints() {
        assertEquals("N", compassName(0.0))
        assertEquals("N", compassName(359.0))
        assertEquals("NE", compassName(40.0))
        assertEquals("S", compassName(-180.0))
    }

    @Test
    fun storedAndReadBack() {
        val w = weather(at(0), at(1))
        assertEquals(w, SessionWeather.fromJson(w.toJson()))
        assertNull(SessionWeather.fromJson("{ not json"))
        assertNull(SessionWeather.fromJson(w.copy(format = 99).toJson()), "a newer format is not guessed")
    }

    @Test
    fun aNewerValueOfAQuarterHourReplacesTheOlder() {
        val old = weather(at(0, air = 10.0), at(1, air = 10.0))
        val fetched = listOf(at(1, air = 11.0), at(2, air = 12.0), at(40))
        val m = merged(old, Lat, Lon, fetched, Ten, Ten + 2 * QuarterMillis, Ten)
        assertEquals(listOf(10.0, 11.0, 12.0), m.readings.map { it.airC }, "far outside the window is dropped")
        assertEquals(40.12, m.latitude)
        assertEquals(Ten + 2 * QuarterMillis, m.toUtcMillis)
    }
}

/** An answer as Open-Meteo gives it, three quarter hours from [Ten]. */
internal fun answer(air: List<Double?>, rain: List<Double?>): String {
    fun list(values: List<Any?>) = values.joinToString(",", "[", "]") { it?.toString() ?: "null" }
    val n = air.size
    val times = (0 until n).map { "\"" + "2030-06-15T10:" + (it * 15).toString().padStart(2, '0') + "\"" }
    return """
        {"latitude":40.12,"longitude":-30.99,"generationtime_ms":0.1,"utc_offset_seconds":0,"timezone":"GMT",
         "minutely_15_units":{"time":"iso8601","temperature_2m":"°C"},
         "minutely_15":{
           "time":${times.joinToString(",", "[", "]")},
           "temperature_2m":${list(air)},
           "relative_humidity_2m":${list(air.map { it?.let { 80 } })},
           "rain":${list(rain)},
           "precipitation":${list(rain)},
           "wind_speed_10m":${list(air.map { it?.let { 12.5 } })},
           "wind_direction_10m":${list(air.map { it?.let { 270 } })},
           "wind_gusts_10m":${list(air.map { it?.let { 30.0 } })},
           "shortwave_radiation":${list(air.map { it?.let { 300.0 } })},
           "weather_code":${list(rain.map { r -> r?.let { if (it > 0) 61 else 2 } })}
         }}
    """.trimIndent()
}
