package com.flappedear.betterdriver.recording

import android.content.Context
import java.io.File

/**
 * The emulator tests' switch for the recording's weather (FBBD-403, FBBD-457): while the file [FileName]
 * is in the app's files directory, a recording fetches no weather, so the coach goes by a dry track. The
 * replay drive replays laps driven on 27 Sep at today's date: the live weather at the Silesia Ring is not
 * theirs, and rain there today put the coach on lap times only and failed the drive (18 Nov 2026).
 * The test writes the file from the UI process; the recorder process reads it at each recording start.
 *
 * Debug builds only: release builds compile the twin in src/release, where the weather is always fetched.
 */
object WeatherHook {
    const val FileName = "weather-off"

    /** Whether a recording starting now fetches its weather. */
    fun fetches(context: Context): Boolean = !File(context.filesDir, FileName).exists()
}
