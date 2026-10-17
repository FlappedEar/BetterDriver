package com.flappedear.betterdriver.camera

import android.content.Context
import com.flappedear.betterdriver.debug.DebugGuards
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.io.File
import java.util.concurrent.Executors

/**
 * Settings › Diagnostics › Ignore phone temperature (FBBD-231): Arek's heat stress test. While it is
 * on, phone video never steps down or stops for heat ([ThermalPolicy]); the readings are still
 * logged. Kept as a marker file so the camera process reads the current value at each session start.
 */
object PhoneHeat {
    /** Writes run here, not in a screen's scope, so leaving Settings at once never drops one. */
    private val io = Executors.newSingleThreadExecutor { Thread(it, "phone-heat") }

    private fun marker(context: Context) = File(context.filesDir, "ignore_phone_temperature")

    /** One small file check, made where a session starts or Settings opens. */
    fun ignored(context: Context): Boolean = DebugGuards.allowDisk { marker(context).exists() }

    /** Any thread: the file is written on [io], in the order of the calls. */
    fun setIgnored(context: Context, on: Boolean) {
        val app = context.applicationContext
        io.execute {
            val file = marker(app)
            if (on) file.createNewFile() else file.delete()
            DebugLog.w("PhoneHeat", "ignore phone temperature ${if (on) "on" else "off"}")
        }
    }
}
