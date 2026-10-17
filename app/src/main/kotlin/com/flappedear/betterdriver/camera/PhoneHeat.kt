package com.flappedear.betterdriver.camera

import android.content.Context
import com.flappedear.betterdriver.debug.DebugGuards
import java.io.File

/**
 * Settings › Diagnostics › Ignore phone temperature (FBBD-231): Arek's heat stress test. While it is
 * on, phone video never steps down or stops for heat ([ThermalPolicy]); the readings are still
 * logged. Kept as a marker file so the camera process reads the current value at each session start.
 */
object PhoneHeat {
    private fun marker(context: Context) = File(context.filesDir, "ignore_phone_temperature")

    /** One small file check, made where a session starts or Settings opens. */
    fun ignored(context: Context): Boolean = DebugGuards.allowDisk { marker(context).exists() }

    /** Off the main thread. */
    fun setIgnored(context: Context, on: Boolean) {
        val file = marker(context)
        if (on) file.createNewFile() else file.delete()
    }
}
