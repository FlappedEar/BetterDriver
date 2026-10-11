package com.flappedear.betterdriver.shell

import android.Manifest
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
class AppPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var setupDone by mutableStateOf(prefs.getBoolean(KeySetupDone, false))
        private set
    var phoneCamera by mutableStateOf(prefs.getBoolean(KeyPhoneCamera, true))
        private set
    var car by mutableStateOf(prefs.getString(KeyCar, null) ?: Cars.first())
        private set

    /** The paired RaceBox, or null. The recorder connects to it when a session starts. */
    var raceBox by mutableStateOf(
        prefs.getString(KeyRaceBoxAddress, null)?.let { address ->
            PairedRaceBox(prefs.getString(KeyRaceBoxName, null).orEmpty(), address)
        },
    )
        private set

    /** Phone video records with the session when it is on in settings and camera access is granted (FBBD-21). */
    fun phoneVideoReady(): Boolean = phoneCamera && context.allGranted(listOf(Manifest.permission.CAMERA))

    /** False when a required permission was revoked or expired (one-time grants) since setup. */
    fun hasRequiredPermissions(): Boolean = context.allGranted(requiredPermissions())

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

    fun updateRaceBox(value: PairedRaceBox) {
        raceBox = value
        prefs.edit().putString(KeyRaceBoxAddress, value.address).putString(KeyRaceBoxName, value.name).apply()
    }

    /**
     * Device states for the ready check. The RaceBox shows as paired once one
     * is saved; it connects when a session starts. GoPro pairing is not built
     * yet. The phone camera shows as ready only while camera access is granted.
     */
    fun devices(): List<DeviceStatus> = listOf(
        raceBox?.let { DeviceStatus("RaceBox Micro", "RaceBox", "Paired", DeviceState.Ready) }
            ?: DeviceStatus("RaceBox Micro", "RaceBox", "Not paired", DeviceState.Off),
        DeviceStatus("GoPro HERO11", "GoPro 11", "Not paired", DeviceState.Off),
        when {
            !phoneCamera -> DeviceStatus("Phone camera", "Phone cam", "Off", DeviceState.Off)
            !context.allGranted(listOf(Manifest.permission.CAMERA)) ->
                DeviceStatus("Phone camera", "Phone cam", "No camera access", DeviceState.Attention)
            else -> DeviceStatus("Phone camera", "Phone cam", "4K · 60 fps", DeviceState.Ready)
        },
        DeviceStatus("GoPro HERO8", "GoPro 8", "Start by hand", DeviceState.Off),
    )

    private companion object {
        const val KeySetupDone = "setup_done"
        const val KeyPhoneCamera = "phone_camera"
        const val KeyCar = "car"
        const val KeyRaceBoxAddress = "racebox_address"
        const val KeyRaceBoxName = "racebox_name"
    }
}

@Composable
fun rememberAppPrefs(): AppPrefs {
    val context = LocalContext.current.applicationContext
    return remember(context) { AppPrefs(context) }
}
