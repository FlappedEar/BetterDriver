package com.flappedear.betterdriver.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.Recording
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The phone's side of a recording for the hardware checks (FBBD-183), recorded as its events so they
 * are in the session and in the drive's log: the battery temperature, charge and thermal status every
 * 30 s (FBBD-46), and the screen going off or on (FBBD-63). Runs in the recorder process; the
 * readings are taken on a thread of their own, never the main thread.
 */
internal class DeviceChecks(private val context: Context, private val recording: Recording) {
    private val timer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "device-checks").apply { isDaemon = true } }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> "off"
                Intent.ACTION_SCREEN_ON -> "on"
                else -> return
            }
            recording.event(EventKinds.SCREEN, "state=$state")
        }
    }

    /** Any thread. */
    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(context, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        timer.scheduleWithFixedDelay(::report, 0, PeriodSeconds, TimeUnit.SECONDS)
    }

    /** Main thread. */
    fun stop() {
        timer.shutdownNow()
        try {
            context.unregisterReceiver(screen)
        } catch (_: IllegalArgumentException) {
            // Never registered: start failed.
        }
    }

    @Suppress("TooGenericExceptionCaught") // A reading that fails is left out; it never stops the timer.
    private fun report() {
        try {
            recording.event(EventKinds.DEVICE, reading())
        } catch (e: Exception) {
            DebugLog.w(Tag, "device reading failed", e)
        }
    }

    private fun reading(): String = "${battery()} ${thermal()}"

    /** "battery_c=… battery_pct=… plugged=…", from the sticky battery broadcast (no receiver is registered). */
    private fun battery(): String {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val celsius = if (tenths == Int.MIN_VALUE) NA else String.format(Locale.ROOT, "%.1f", tenths / Tenths)
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * Percent / scale).toString() else NA
        val plugged = when (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }
        return "battery_c=$celsius battery_pct=$percent plugged=$plugged"
    }

    /** "thermal=… headroom=…": Android's thermal status (Android 10+) and its 30 s forecast (Android 11+). */
    private fun thermal(): String {
        val power = context.getSystemService(PowerManager::class.java)
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus.toString() else NA
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            power.getThermalHeadroom(HeadroomForecastSeconds).takeIf { !it.isNaN() }
                ?.let { String.format(Locale.ROOT, "%.3f", it) } ?: NA
        } else {
            NA
        }
        return "thermal=$status headroom=$headroom"
    }

    private companion object {
        const val Tag = "DeviceChecks"
        const val NA = "n/a"
        const val PeriodSeconds = 30L
        const val HeadroomForecastSeconds = 30
        const val Tenths = 10.0
        const val Percent = 100
    }
}
