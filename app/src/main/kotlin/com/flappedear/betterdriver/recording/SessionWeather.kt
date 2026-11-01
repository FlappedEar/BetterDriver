package com.flappedear.betterdriver.recording

import android.content.Context
import com.flappedear.betterdriver.analysis.weather.SessionWeatherFiles
import com.flappedear.betterdriver.analysis.weather.WeatherHttp
import com.flappedear.betterdriver.analysis.weather.WeatherUi
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The weather service over the phone's network (FBBD-403): one HTTPS GET, the body of a 200 answer,
 * null on anything else. Blocking: called off the main thread and off the recorder's.
 */
internal object AndroidWeatherHttp : WeatherHttp {
    override fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = TimeoutMs
            c.readTimeout = TimeoutMs
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != HttpURLConnection.HTTP_OK) {
                DebugLog.w(Tag, "weather service answered ${c.responseCode}")
                null
            } else {
                c.inputStream.use { it.readBytes().decodeToString() }
            }
        } catch (e: IOException) {
            DebugLog.i(Tag, "weather service not reached: $e")
            null
        } finally {
            c.disconnect()
        }
    }

    private const val Tag = "Weather"
    private const val TimeoutMs = 15_000
}

/**
 * The weather of the app's saved sessions, for the Summary (FBBD-403): as the recording kept it, or
 * fetched when the session is opened. One at a time, so two screens never fetch the same session.
 * Blocking: call it off the main thread.
 */
internal fun sessionWeather(context: Context): SessionWeatherReader =
    SessionWeatherReader.get(sessionsDir(context).path)

internal class SessionWeatherReader private constructor(root: String) {
    private val files = SessionWeatherFiles(root, AndroidWeatherHttp, System::currentTimeMillis)

    fun load(id: String): WeatherUi? = synchronized(this) { files.load(id) }

    companion object {
        @Volatile
        private var instance: SessionWeatherReader? = null

        /** One per process, so a failed fetch is not tried again at each opening (SessionWeatherFiles). */
        fun get(root: String): SessionWeatherReader =
            instance ?: synchronized(this) { instance ?: SessionWeatherReader(root).also { instance = it } }
    }
}
