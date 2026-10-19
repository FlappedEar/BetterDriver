package com.flappedear.betterdriver.camera

import android.content.Context
import com.flappedear.betterdriver.debug.DebugGuards
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.io.File

/**
 * Settings › Diagnostics › Ignore phone temperature (FBBD-231): Arek's heat stress test. While it is
 * on, every thermal check is logged. Since 0.4.1 video stops only at Android's thermal shutdown with
 * the switch on or off ([ThermalPolicy]). Kept as a marker file so the camera process reads the
 * current value at each session start.
 */
object PhoneHeat {
    private fun marker(context: Context) = File(context.filesDir, "ignore_phone_temperature")

    /** One small file check, made where a session starts or Settings opens. */
    fun ignored(context: Context): Boolean = DebugGuards.allowDisk { marker(context).exists() }

    /**
     * Creates or deletes the empty marker before returning, so a session started right after the
     * switch, or Settings left at once, always sees the new value. One empty file: fine on the main
     * thread.
     */
    fun setIgnored(context: Context, on: Boolean) {
        DebugGuards.allowDisk {
            val file = marker(context)
            if (on) file.createNewFile() else file.delete()
        }
        DebugLog.w("PhoneHeat", "ignore phone temperature ${if (on) "on" else "off"}")
    }
}
