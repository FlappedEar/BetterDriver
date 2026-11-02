package com.flappedear.betterdriver.tracks

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.flappedear.betterdriver.feature.track.editor.AndroidCertHeader
import com.flappedear.betterdriver.feature.track.editor.AndroidPackageHeader
import java.security.MessageDigest

/**
 * The headers a Google key limited to Android apps checks on each Map Tiles API request
 * (FBBD-412): this app's package and the SHA-1 of the certificate it is signed with, in upper-case
 * hex. Without a certificate only the package is sent.
 */
internal fun googleKeyHeaders(context: Context): Map<String, String> {
    val cert = signingSha1(context)
    return buildMap {
        put(AndroidPackageHeader, context.packageName)
        if (cert != null) put(AndroidCertHeader, cert)
    }
}

/** The SHA-1 of the app's signing certificate, upper-case hex without colons; null if it cannot be read. */
private fun signingSha1(context: Context): String? {
    val cert = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION") // The only way before Android 9.
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } ?: return null
    return MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString("") { "%02X".format(it) }
}
