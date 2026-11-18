package com.flappedear.betterdriver.recording

import android.content.Context

/** The release twin of the debug build's weather switch for emulator tests: the weather is always fetched. */
@Suppress("UNUSED_PARAMETER", "UnusedParameter")
object WeatherHook {
    const val FileName = "weather-off"

    fun fetches(context: Context): Boolean = true
}
