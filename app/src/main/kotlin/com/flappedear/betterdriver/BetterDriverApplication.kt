package com.flappedear.betterdriver

import android.app.Application
import com.flappedear.betterdriver.debug.AppLog
import com.flappedear.betterdriver.debug.DebugGuards
import com.flappedear.betterdriver.debug.ProcessLoad
import com.flappedear.betterdriver.debug.StartupLog

/**
 * Starts the debug log in every process: UI, recorder, camera and coach (FBBD-146). Debug builds
 * then turn on StrictMode in each of them (FBBD-41). Each logs its load while a session records
 * (FBBD-281). The UI process also logs its cold start and how
 * the app's processes ended before (FBBD-41), in every build.
 */
class BetterDriverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val process = AppLog.install(this)
        DebugGuards.install(process)
        ProcessLoad.start()
        if (':' !in process) StartupLog.onUiProcessStart(this)
    }
}
