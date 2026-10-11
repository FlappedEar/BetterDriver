package com.flappedear.betterdriver.shell

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Permissions needed to log a session: location (GNSS) and nearby devices (RaceBox). */
internal fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}

/** Asked for with the required ones, but setup can finish without them (phone video, mic, notifications). */
internal fun optionalPermissions(): List<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

internal fun Context.allGranted(permissions: List<String>): Boolean =
    permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

/**
 * True when a permission is still denied and Android will not show its dialog
 * again. Only meaningful right after a request result: before the first request
 * the rationale flag is false too.
 */
internal fun Activity.permanentlyDenied(permissions: List<String>): Boolean = permissions.any {
    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED &&
        !shouldShowRequestPermissionRationale(it)
}

/** Opens this app's page in system settings, where denied permissions can be granted. */
internal fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
