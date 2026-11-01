package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.weather.SessionWeatherFiles
import com.flappedear.betterdriver.analysis.weather.SessionWeatherListener
import com.flappedear.betterdriver.analysis.weather.WeatherHttp
import com.flappedear.betterdriver.analysis.weather.WeatherUi
import com.flappedear.betterdriver.recorder.log.CoreLog
import com.flappedear.betterdriver.recorder.log.logLine
import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLock
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.timeIntervalSince1970
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * The weather service over the iPhone's or iPad's network (FBBD-403), as Android's AndroidWeatherHttp:
 * one HTTPS GET, the body of a 200 answer, null on anything else. Blocking: off the main thread.
 */
internal object IosWeatherHttp : WeatherHttp {
    @OptIn(BetaInteropApi::class)
    override fun get(url: String): String? {
        val address = NSURL.URLWithString(url) ?: return null
        val request = NSURLRequest.requestWithURL(address, NSURLRequestUseProtocolCachePolicy, TimeoutSeconds)
        val done = dispatch_semaphore_create(0)
        var body: String? = null
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            val status = (response as? NSHTTPURLResponse)?.statusCode
            if (error != null) {
                logLine(CoreLog.LevelInfo, Tag, "weather service not reached: ${error.localizedDescription}")
            } else if (status != OkStatus) {
                logLine(CoreLog.LevelWarn, Tag, "weather service answered $status")
            } else if (data != null) {
                body = NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
            }
            dispatch_semaphore_signal(done)
        }
        task.resume()
        dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, WaitNanos))
        return body
    }

    private const val Tag = "Weather"
    private const val OkStatus = 200L
    private const val TimeoutSeconds = 15.0
    private const val WaitNanos = 20_000_000_000L
}

/**
 * The Weather card of the sessions in [sessionsDir] (FBBD-403), the shared core's as on Android: as
 * the recording kept it, or fetched when the session is opened. One at a time across the app.
 * Blocking: call it off the main thread.
 */
class SessionWeatherCards(sessionsDir: String) {
    private val files = SessionWeatherFiles(sessionsDir, IosWeatherHttp, ::weatherNowMillis)

    fun card(id: String): WeatherUi? {
        lock.lock()
        try {
            return files.load(id)
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        val lock = NSLock()
    }
}

/**
 * The weather of a recording while it runs (FBBD-403), as on Android's recorder: fetched at the first
 * fix and every 15 minutes on a queue of its own into the session's folder [dir].
 */
internal fun recordingWeather(id: String, dir: String, startUtcMillis: Long) = SessionWeatherListener(
    id, dir, startUtcMillis, IosWeatherHttp, { work -> dispatch_async(weatherQueue) { work() } }, ::weatherNowMillis,
)

private val weatherQueue = dispatch_queue_create("weather", null)

private fun weatherNowMillis(): Long = (NSDate().timeIntervalSince1970 * MillisPerSecondD).toLong()

private const val MillisPerSecondD = 1000.0
