package com.flappedear.betterdriver

import android.app.Application
import com.flappedear.betterdriver.debug.AppLog

/** Starts the debug log in every process: UI, recorder, camera and coach (FBBD-146). */
class BetterDriverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.install(this)
    }
}
