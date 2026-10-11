package com.flappedear.betterdriver.shell

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Cars the app knows about (test car and target car). */
val Cars = listOf("2023 Toyota Corolla", "2013 Scion FR-S")

/**
 * Small app settings kept on the phone. Values are Compose state, so screens
 * update when they change.
 */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var setupDone by mutableStateOf(prefs.getBoolean(KeySetupDone, false))
        private set
    var phoneCamera by mutableStateOf(prefs.getBoolean(KeyPhoneCamera, true))
        private set
    var car by mutableStateOf(prefs.getString(KeyCar, null) ?: Cars.first())
        private set

    fun updateSetupDone(value: Boolean) {
        setupDone = value
        prefs.edit().putBoolean(KeySetupDone, value).apply()
    }

    fun updatePhoneCamera(value: Boolean) {
        phoneCamera = value
        prefs.edit().putBoolean(KeyPhoneCamera, value).apply()
    }

    fun updateCar(value: String) {
        car = value
        prefs.edit().putString(KeyCar, value).apply()
    }

    /**
     * Device states for the ready check. Bluetooth pairing is not built yet,
     * so the RaceBox and the HERO11 always show as not paired for now.
     */
    fun devices(): List<DeviceStatus> = listOf(
        DeviceStatus("RaceBox Micro", "RaceBox", "Not paired", DeviceState.Off),
        DeviceStatus("GoPro HERO11", "GoPro 11", "Not paired", DeviceState.Off),
        if (phoneCamera) {
            DeviceStatus("Phone camera", "Phone cam", "4K · 60 fps", DeviceState.Ready)
        } else {
            DeviceStatus("Phone camera", "Phone cam", "Off", DeviceState.Off)
        },
        DeviceStatus("GoPro HERO8", "GoPro 8", "Start by hand", DeviceState.Off),
    )

    private companion object {
        const val KeySetupDone = "setup_done"
        const val KeyPhoneCamera = "phone_camera"
        const val KeyCar = "car"
    }
}

@Composable
fun rememberAppPrefs(): AppPrefs {
    val context = LocalContext.current.applicationContext
    return remember(context) { AppPrefs(context) }
}
