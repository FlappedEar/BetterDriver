package com.flappedear.betterdriver.coach

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The coach settings as the UI sees them (FBBD-98). Changes are written to the settings file off the
 * main thread; the recorder and coach processes read it at the next session start.
 */
internal class CoachPrefs private constructor(context: Context) {
    private val store = coachSettings(context)
    private val io = Executors.newSingleThreadExecutor { Thread(it, "coach-prefs") }

    var settings by mutableStateOf(store.read())
        private set

    /** Reads the settings file again after a restore replaced it (FBBD-156). */
    fun reload() = io.execute {
        val read = store.read()
        Handler(Looper.getMainLooper()).post { settings = read }
    }

    fun update(s: CoachSettings) {
        settings = s
        io.execute {
            try {
                store.write(s)
            } catch (e: IOException) {
                DebugLog.w("CoachPrefs", "coach settings not saved", e)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: CoachPrefs? = null

        fun get(context: Context): CoachPrefs = instance ?: synchronized(this) {
            instance ?: CoachPrefs(context.applicationContext).also { instance = it }
        }
    }
}
