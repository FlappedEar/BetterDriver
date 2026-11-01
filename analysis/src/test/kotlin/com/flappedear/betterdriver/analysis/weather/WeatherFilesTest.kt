package com.flappedear.betterdriver.analysis.weather

import com.flappedear.betterdriver.analysis.SimTrack
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.geo.GeoPoint
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The weather kept with each session (FBBD-403): fetched on opening, compared with the last time here. */
class WeatherFilesTest {
    @TempDir
    lateinit var tmp: File

    // A made-up place in open sea (the repository is public).
    private val track = SimTrack.rectangle(origin = GeoPoint(40.12, -30.99))

    /** 2030-06-15T10:00Z and the same time 10 days before. */
    private val today = 1_907_748_000_000L
    private val before = today - 10 * MillisPerDay

    private val asked = mutableListOf<String>()

    /** Open-Meteo as a fake: each requested day's quarter hours, 6 °C and wet on [today], 14 °C and dry before. */
    private val http = WeatherHttp { url ->
        asked += url
        val start = Regex("start_date=([0-9-]+)").find(url)!!.groupValues[1]
        val day = parseUtc("${start}T00:00")!!
        val wet = utcDate(day) == utcDate(today)
        val n = 96
        val times = (0 until n).map { "\"" + utcIso(day + it * QuarterMillis) + "\"" }
        fun col(v: Any) = List(n) { v }.joinToString(",", "[", "]")
        """{"minutely_15":{"time":${times.joinToString(",", "[", "]")},
            "temperature_2m":${col(if (wet) 6.0 else 14.0)},"relative_humidity_2m":${col(80)},
            "rain":${col(if (wet) 0.2 else 0.0)},"precipitation":${col(if (wet) 0.2 else 0.0)},
            "wind_speed_10m":${col(12.0)},"wind_direction_10m":${col(90)},"wind_gusts_10m":${col(20.0)},
            "shortwave_radiation":${col(100.0)},"weather_code":${col(if (wet) 61 else 1)}}}"""
    }

    @Test
    fun aSessionGetsItsWeatherWhenOpenedAndIsComparedWithTheLastTimeHere() {
        val store = SessionStore(tmp)
        val earlier = track.record(store, null, 2, before)
        val now = track.record(store, null, 2, today)
        val files = SessionWeatherFiles(tmp.path, http) { today + MillisPerHour }

        val earlierUi = assertNotNull(files.load(earlier))
        assertEquals("14 °C", earlierUi.air)
        assertEquals("Dry", earlierUi.surface)
        assertNull(earlierUi.versus, "nothing here before it")

        val ui = assertNotNull(files.load(now))
        assertEquals("6 °C", ui.air)
        assertTrue(ui.wet)
        assertEquals("Last time here (5 Jun): 14 °C, dry. Today 8 °C colder, wet.", ui.versus)
        assertEquals("12 km/h from E, gusts 20", ui.wind)
        assertTrue(asked.all { "latitude=40.1" in it && "longitude=-3" in it }, asked.joinToString())
        assertTrue(File(tmp, "$now/${SessionWeather.FileName}").isFile)

        // Kept: opened again, nothing is fetched.
        val calls = asked.size
        files.load(now)
        assertEquals(calls, asked.size)
    }

    @Test
    fun offlineItIsNotAskedAgainAtEachOpening() {
        val store = SessionStore(tmp)
        val id = track.record(store, null, 1, today)
        var calls = 0
        var clock = today + MillisPerHour
        val files = SessionWeatherFiles(tmp.path, { calls++; null }) { clock }
        assertNull(files.load(id))
        assertNull(files.load(id))
        assertEquals(1, calls)
        clock += SessionWeatherFiles.RetryAfterMillis
        assertNull(files.load(id))
        assertEquals(2, calls)
    }

    @Test
    fun anAnswerWithoutValuesIsNotTakenAsTheWeather() {
        val store = SessionStore(tmp)
        val id = track.record(store, null, 1, today)
        var calls = 0
        var clock = today + MillisPerHour
        val empty = WeatherHttp { calls++; """{"minutely_15":{"time":[],"temperature_2m":[]}}""" }
        val files = SessionWeatherFiles(tmp.path, empty) { clock }
        assertNull(files.load(id))
        assertTrue(!File(tmp, "$id/${SessionWeather.FileName}").exists(), "nothing kept")
        clock += SessionWeatherFiles.RetryAfterMillis
        files.load(id)
        assertEquals(2, calls, "asked again later")
    }

    @Test
    fun whileRecordingAtTheFirstFixThenEveryQuarterHour() {
        val dir = File(tmp, "s1").apply { mkdirs() }
        var clock = today
        val queued = ArrayDeque<() -> Unit>()
        val listener = SessionWeatherListener("s1", dir.path, today, http, { queued.addLast(it) }) { clock }
        val noFix = SampleSet(0, listOf(ChannelValue(GnssChannels.LAT.id, 0.0), ChannelValue(GnssChannels.LON.id, 0.0)))
        val fix = SampleSet(
            0,
            listOf(ChannelValue(GnssChannels.LAT.id, 40.1234), ChannelValue(GnssChannels.LON.id, -30.99)),
        )
        listener.onSamples(noFix)
        assertTrue(queued.isEmpty(), "no fix, no request")
        listener.onSamples(fix)
        listener.onSamples(fix)
        assertEquals(1, queued.size, "one fetch at a time")
        queued.removeFirst()()
        val kept = assertNotNull(readWeather(dir.path))
        assertEquals(Surface.Wet, kept.surfaceAt(today))
        assertTrue(kept.readings.all { it.utcMillis <= today + QuarterMillis }, "no forecast kept")
        clock += SessionWeatherListener.RefreshMillis - 1
        listener.onSamples(fix)
        assertTrue(queued.isEmpty(), "not yet")
        clock += 1
        listener.onSamples(fix)
        assertEquals(1, queued.size)
        queued.removeFirst()()
        listener.onStatus(RecorderStatus.Stopped("user"))
        clock += SessionWeatherListener.RefreshMillis
        listener.onSamples(fix)
        assertTrue(queued.isEmpty(), "nothing after the stop")
    }
}

private fun utcIso(utcMillis: Long): String {
    val day = utcDate(utcMillis)
    val minutes = (utcMillis.mod(MillisPerDay)) / MillisPerMinute
    return "${day}T${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"
}
