package com.flappedear.betterdriver.analysis.weather

import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.IOException
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.log.CoreLog
import com.flappedear.betterdriver.recorder.log.logLine
import com.flappedear.betterdriver.recorder.store.SessionFolders
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.StoredSession
import kotlin.concurrent.Volatile
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The network as each platform gives it (FBBD-403): an HTTPS GET of [url] that returns the body of
 * a 200 answer, or null on any failure (no network, timeout, another status). Blocking: called off
 * the main thread and off the recorder's.
 */
fun interface WeatherHttp {
    fun get(url: String): String?
}

internal const val Tag = "Weather"

/** The weather file of the session in [dir]; null when it has none or one this version cannot read. */
fun readWeather(dir: String): SessionWeather? {
    val file = CoreFiles.join(dir, SessionWeather.FileName)
    if (!CoreFiles.isFile(file) || CoreFiles.size(file) > MaxWeatherBytes) return null
    return try {
        SessionWeather.fromJson(CoreFiles.readText(file))
    } catch (e: IOException) {
        logLine(CoreLog.LevelWarn, Tag, "unreadable $file", e)
        null
    }
}

/** Far more than a week of quarter hours; a larger file is not the app's. */
private const val MaxWeatherBytes = 1L shl 20

/**
 * [readings] fetched at [nowUtcMillis] for [latitude], [longitude] added to [old] (the stored weather,
 * if any): a newer value of a quarter hour replaces the older one, as the model's later run knows it
 * better. Only the quarter hours from [fromUtcMillis] to [toUtcMillis] are kept.
 */
@Suppress("LongParameterList") // The stored weather, the place and what was fetched for which window.
internal fun merged(
    old: SessionWeather?,
    latitude: Double,
    longitude: Double,
    readings: List<WeatherReading>,
    fromUtcMillis: Long,
    toUtcMillis: Long,
    nowUtcMillis: Long,
): SessionWeather {
    val byTime = LinkedHashMap<Long, WeatherReading>()
    old?.readings?.forEach { byTime[it.utcMillis] = it }
    readings.forEach { byTime[it.utcMillis] = it }
    val from = minOf(fromUtcMillis, old?.fromUtcMillis ?: fromUtcMillis)
    val to = maxOf(toUtcMillis, old?.toUtcMillis ?: toUtcMillis)
    val kept = byTime.values
        .filter { it.utcMillis in from - QuarterMillis..to + QuarterMillis }
        .sortedBy { it.utcMillis }
    return SessionWeather(
        latitude = OpenMeteo.rounded(latitude),
        longitude = OpenMeteo.rounded(longitude),
        fetchedUtcMillis = nowUtcMillis,
        fromUtcMillis = from,
        toUtcMillis = to,
        readings = kept,
    )
}

/**
 * Fetches the weather of one window and writes it to the session in [dir]; the fetched weather, or
 * null when nothing could be fetched (logged). Blocking.
 */
// A failed fetch must never reach the caller; each early return is a fetch that gave nothing.
@Suppress("LongParameterList", "TooGenericExceptionCaught", "ReturnCount")
internal fun fetchInto(
    dir: String,
    id: String,
    http: WeatherHttp,
    latitude: Double,
    longitude: Double,
    fromUtcMillis: Long,
    toUtcMillis: Long,
    nowUtcMillis: Long,
): SessionWeather? {
    val url = OpenMeteo.url(latitude, longitude, fromUtcMillis, toUtcMillis, nowUtcMillis) ?: return null
    return try {
        val body = http.get(url)
        if (body == null) {
            logLine(CoreLog.LevelInfo, Tag, "weather of $id not fetched: no answer", session = id)
            return null
        }
        val readings = OpenMeteo.readings(body)
        // A live fetch also brings the forecast of the hours to come: only what has been is kept.
        val until = minOf(toUtcMillis, nowUtcMillis)
        val weather = merged(readWeather(dir), latitude, longitude, readings, fromUtcMillis, until, nowUtcMillis)
        CoreFiles.writeTextAtomically(CoreFiles.join(dir, SessionWeather.FileName), weather.toJson())
        val now = weather.at(until)
        logLine(
            CoreLog.LevelInfo,
            Tag,
            "weather of $id: ${weather.readings.size} quarter hours, air ${now?.airC} C, " +
                "wind ${now?.windKmh} km/h, surface ${weather.surfaceAt(until)?.text}",
            session = id,
        )
        weather
    } catch (e: Exception) {
        logLine(CoreLog.LevelWarn, Tag, "weather of $id not fetched", e, session = id)
        null
    }
}

/**
 * The weather of a recording while it runs (FBBD-403), the same on Android and iOS: at the first
 * fix, then every 15 minutes, the model's values from two hours before the start until now are
 * fetched with [http] on [background] and written to weather.json in the session's folder [dir],
 * where the summary and the live coach read them. A failed fetch is tried again after 2 minutes;
 * nothing is fetched once the recording has stopped. The recording never waits for it.
 */
@Suppress("LongParameterList") // What one recording's weather needs, one parameter each.
class SessionWeatherListener(
    private val id: String,
    private val dir: String,
    private val startUtcMillis: Long,
    private val http: WeatherHttp,
    private val background: (() -> Unit) -> Unit,
    private val nowUtcMillis: () -> Long,
) : RecorderListener {
    @Volatile
    private var nextAt = 0L

    @Volatile
    private var busy = false

    @Volatile
    private var stopped = false

    @Suppress("ReturnCount") // Each early return is a reason not to fetch on this sample set.
    override fun onSamples(set: SampleSet) {
        if (stopped || busy) return
        val now = nowUtcMillis()
        if (now < nextAt) return
        var lat: Double? = null
        var lon: Double? = null
        for (v in set.values) {
            when (v.channelId) {
                GnssChannels.LAT.id -> lat = v.value
                GnssChannels.LON.id -> lon = v.value
            }
        }
        if (lat == null || lon == null || !OpenMeteo.validPlace(lat, lon)) return
        busy = true
        val la: Double = lat
        val lo: Double = lon
        background {
            try {
                val done = !stopped &&
                    fetchInto(dir, id, http, la, lo, startUtcMillis - BeforeStartMillis, now, now) != null
                nextAt = now + if (done) RefreshMillis else RetryMillis
            } finally {
                busy = false
            }
        }
    }

    override fun onStatus(status: RecorderStatus) {
        if (status is RecorderStatus.Stopped) stopped = true
    }

    companion object {
        const val RefreshMillis = QuarterMillis
        const val RetryMillis = 2 * MillisPerMinute
    }
}

/**
 * What the Summary's Weather card shows (FBBD-403), the same on Android and iOS: the session's sky,
 * air, wind and surface, the line against the last session at the same place, and the credit the
 * data's licence asks for.
 */
data class WeatherUi(
    val sky: String?,
    val air: String?,
    val wind: String?,
    val surface: String?,
    val wet: Boolean,
    val humidity: String?,
    val versus: String?,
    val credit: String = OpenMeteo.Credit,
)

/** The card for [now]'s conditions, against [earlier]'s on [earlierDate] when there is such a session. */
fun weatherUi(now: SessionConditions, earlier: SessionConditions? = null, earlierDate: String? = null) = WeatherUi(
    sky = now.sky,
    air = airText(now),
    wind = windText(now),
    surface = surfaceText(now),
    wet = now.surface != null && now.surface != Surface.Dry,
    humidity = now.humidityPct?.let { "${com.flappedear.betterdriver.math.roundHalfUp(it)} %" },
    versus = if (earlier != null && earlierDate != null) versusText(now, earlier, earlierDate) else null,
)

/**
 * The weather of the saved sessions under [root] (FBBD-403), for the Summary, the same on Android and
 * iOS. A finished session whose weather is missing or stops before its end (recorded offline, made
 * before this version, imported) is fetched with [http] when it is opened, at most once per
 * [RetryAfterMillis] per session while it fails. Blocking, and not thread-safe: the platform calls
 * it off the main thread, one call at a time.
 */
class SessionWeatherFiles(
    root: String,
    private val http: WeatherHttp?,
    private val nowMillis: () -> Long,
) {
    private val folders = SessionFolders(root)
    private val tried = HashMap<String, Long>()

    /** The Weather card of session [id]; null when it has no weather and none can be fetched. */
    @Suppress("ReturnCount") // Each early return is a session with no weather to show.
    fun load(id: String): WeatherUi? {
        val sessions = folders.list()
        val s = sessions.firstOrNull { it.id == id } ?: return null
        val end = endOf(s)
        val weather = complete(s, end) ?: return null
        val conditions = weather.conditions(s.info.startUtcMillis, end) ?: return null
        val earlier = earlierAt(sessions, s, weather)
        return weatherUi(
            conditions,
            earlier?.second,
            earlier?.first?.info?.startUtcMillis?.let(::shortDate),
        )
    }

    /** The session's weather, fetched first when it is missing or stops before the session's [end]. */
    @Suppress("ReturnCount") // Each early return keeps what is stored without asking the network.
    private fun complete(s: StoredSession, end: Long): SessionWeather? {
        val stored = readWeather(s.dir)
        val done = s.state == SessionState.FINISHED || s.state == SessionState.RECOVERED
        val whole = stored != null && stored.toUtcMillis >= end
        if (!done || http == null || whole) return stored
        val now = nowMillis()
        val last = tried[s.id]
        if (last != null && now - last < RetryAfterMillis) return stored
        tried[s.id] = now
        val place = stored?.let { it.latitude to it.longitude } ?: firstFix(s) ?: return stored
        logLine(CoreLog.LevelInfo, Tag, "fetching the weather of ${s.id} after the session", session = s.id)
        return fetchInto(
            s.dir, s.id, http, place.first, place.second, s.info.startUtcMillis - BeforeStartMillis, end, now,
        ) ?: stored
    }

    /** Where the session first had a fix; null when it never had one. */
    @Suppress("TooGenericExceptionCaught") // A damaged session only has no weather.
    private fun firstFix(s: StoredSession): Pair<Double, Double>? = try {
        folders.read(s.dir).sampleSets.firstNotNullOfOrNull { set ->
            val lat = set.values.firstOrNull { it.channelId == GnssChannels.LAT.id }?.value
            val lon = set.values.firstOrNull { it.channelId == GnssChannels.LON.id }?.value
            if (lat != null && lon != null && OpenMeteo.validPlace(lat, lon)) lat to lon else null
        }
    } catch (e: Exception) {
        logLine(CoreLog.LevelWarn, Tag, "session ${s.id} unreadable for its weather", e)
        null
    }

    /** The last session before [s] at the same place with weather, and its conditions. */
    private fun earlierAt(
        sessions: List<StoredSession>,
        s: StoredSession,
        weather: SessionWeather,
    ): Pair<StoredSession, SessionConditions>? = sessions
        .filter { it.info.startUtcMillis < s.info.startUtcMillis - SameSessionMillis }
        .sortedByDescending { it.info.startUtcMillis }
        .firstNotNullOfOrNull { other ->
            val w = readWeather(other.dir) ?: return@firstNotNullOfOrNull null
            if (kilometres(weather.latitude, weather.longitude, w.latitude, w.longitude) > SamePlaceKm) {
                return@firstNotNullOfOrNull null
            }
            // Another day: on the same day the driver remembers the conditions.
            if (utcDate(other.info.startUtcMillis) == utcDate(s.info.startUtcMillis)) return@firstNotNullOfOrNull null
            w.conditions(other.info.startUtcMillis, endOf(other))?.let { other to it }
        }

    companion object {
        /** A failed fetch is not tried again for this long, so an offline phone is not asked at each opening. */
        const val RetryAfterMillis = 10 * MillisPerMinute

        /** Two places this close are the same track. */
        const val SamePlaceKm = 3.0

        private const val SameSessionMillis = MillisPerMinute
    }
}

/** When the session ended: its start and its length; its start while it is not known. */
private fun endOf(s: StoredSession): Long = s.info.startUtcMillis + (s.durationMicros ?: 0L) / MicrosPerMilli

private const val MicrosPerMilli = 1_000L

/** About the distance between two places, in km; enough to tell one track from another. */
internal fun kilometres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1) * KmPerDegree
    val dLon = (lon2 - lon1) * KmPerDegree * cos((lat1 + lat2) / 2 * kotlin.math.PI / HalfTurnDeg)
    return sqrt(dLat * dLat + dLon * dLon)
}

private const val KmPerDegree = 111.2
private const val HalfTurnDeg = 180.0
