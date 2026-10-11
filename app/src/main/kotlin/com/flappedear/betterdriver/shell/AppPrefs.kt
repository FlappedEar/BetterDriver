package com.flappedear.betterdriver.shell

import android.Manifest
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.flappedear.betterdriver.setup.LegacySettings

/**
 * Small app settings kept on the phone. Values are Compose state, so screens update when they
 * change. Equipment and cars are not here: they are Arek's setup ([SetupRepo], FBBD-83).
 */
class AppPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences(FileName, Context.MODE_PRIVATE)

    var setupDone by mutableStateOf(prefs.getBoolean(KeySetupDone, false))
        private set

    /** False when a required permission was revoked or expired (one-time grants) since setup. */
    fun hasRequiredPermissions(): Boolean = context.allGranted(requiredPermissions())

    /** Phone video needs camera access as well as the phone camera being on for the session (FBBD-21). */
    fun cameraGranted(): Boolean = context.allGranted(listOf(Manifest.permission.CAMERA))

    fun updateSetupDone(value: Boolean) {
        setupDone = value
        prefs.edit().putBoolean(KeySetupDone, value).apply()
    }

    companion object {
        const val FileName = "app"
        private const val KeySetupDone = "setup_done"

        // Builds before FBBD-83 kept the devices and the car here. Read once, to migrate them.
        private const val KeyGoProAddress = "gopro_address"
        private const val KeyGoProName = "gopro_name"
        private const val KeyPhoneCamera = "phone_camera"
        private const val KeyCar = "car"
        private const val KeyRaceBoxAddress = "racebox_address"
        private const val KeyRaceBoxName = "racebox_name"

        /** What an older build saved; values never set are null, so a default is not taken for a choice. */
        fun legacy(context: Context): LegacySettings {
            val p = context.getSharedPreferences(FileName, Context.MODE_PRIVATE)
            return LegacySettings(
                raceBoxAddress = p.getString(KeyRaceBoxAddress, null),
                raceBoxName = p.getString(KeyRaceBoxName, null),
                goProAddress = p.getString(KeyGoProAddress, null),
                goProName = p.getString(KeyGoProName, null),
                phoneCamera = if (p.contains(KeyPhoneCamera)) p.getBoolean(KeyPhoneCamera, true) else null,
                car = p.getString(KeyCar, null),
            )
        }
    }
}

@Composable
fun rememberAppPrefs(): AppPrefs {
    val context = LocalContext.current.applicationContext
    return remember(context) { AppPrefs(context) }
}
