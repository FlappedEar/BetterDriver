package com.flappedear.betterdriver.recording

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.flappedear.betterdriver.recorder.log.DebugLog

private const val Tag = "RecorderForeground"

/**
 * Puts the recorder in the foreground as location and connected device, the receiver and sensors
 * over Bluetooth or Wi-Fi (FBBD-34), claiming each type only while Android accepts it
 * ([recorderForegroundTypes]), so a denied permission never makes startForeground throw.
 *
 * Android 14 also refuses location while the app may not use it, such as a restart in the
 * background; then connected device alone keeps the recording, which reads no phone location.
 * Throws when Android 10+ would accept no type, or the start is not allowed at all.
 */
internal fun Service.startRecorderForeground(id: Int, notification: Notification) {
    val sdk = Build.VERSION.SDK_INT
    val types = recorderForegroundTypes(
        sdk,
        locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION),
        bluetoothConnectGranted = sdk >= Build.VERSION_CODES.S && granted(Manifest.permission.BLUETOOTH_CONNECT),
    )
    check(sdk < Build.VERSION_CODES.Q || types.isNotEmpty()) { "allow location or nearby devices in Settings" }
    try {
        ServiceCompat.startForeground(this, id, notification, typeBits(types))
    } catch (e: SecurityException) {
        val device = types - RecorderForegroundType.Location
        if (device.isEmpty() || device == types) throw e
        DebugLog.w(Tag, "location refused, in the foreground as a connected device only", e)
        ServiceCompat.startForeground(this, id, notification, typeBits(device))
    }
}

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun typeBits(types: Set<RecorderForegroundType>): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        types.fold(0) { bits, t ->
            bits or when (t) {
                RecorderForegroundType.Location -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                RecorderForegroundType.ConnectedDevice -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        }
    } else {
        0
    }
