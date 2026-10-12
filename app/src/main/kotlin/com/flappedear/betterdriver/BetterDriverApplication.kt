package com.flappedear.betterdriver

import android.app.Application
import com.flappedear.betterdriver.debug.AppLog
import com.flappedear.betterdriver.debug.DebugGuards

/**
 * Starts the debug log in every process: UI, recorder, camera and coach (FBBD-146). Debug builds
 * then turn on StrictMode in each of them (FBBD-41); release builds do nothing more.
 */
class BetterDriverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val process = AppLog.install(this)
        DebugGuards.install(process)
    }
}
