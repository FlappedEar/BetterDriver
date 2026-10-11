package com.flappedear.betterdriver.gopro

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val JoinTimeoutMs = 60_000

/**
 * Joins the camera's Wi-Fi access point with a WifiNetworkSpecifier request (Android 10 and later).
 * Android asks the user once to allow the connection. The camera's network has no internet, so
 * the phone keeps its mobile data as the default network, and [GoProHttp] opens its connections
 * on the camera's [Network] explicitly. [leave] releases the request, which drops the network.
 */
class AndroidWifiJoiner(context: Context) : WifiJoiner {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    override suspend fun join(ssid: String, password: String): GoProHttp {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw GoProException("GoPro download needs Android 10 or newer")
        }
        leave()
        val network = request(ssid, password)
        return GoProHttp(open = { url -> network.openConnection(url) })
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun request(ssid: String, password: String): Network = suspendCancellableCoroutine { cont ->
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cont.isActive) cont.resume(network)
            }

            override fun onUnavailable() {
                callback = null
                if (cont.isActive) cont.resumeWithException(GoProException("Could not join the GoPro's Wi-Fi"))
            }
        }
        callback = cb
        try {
            connectivity.requestNetwork(request, cb, JoinTimeoutMs)
        } catch (e: SecurityException) {
            callback = null
            cont.resumeWithException(GoProException("No permission to join Wi-Fi: ${e.message}"))
        }
        cont.invokeOnCancellation { release(cb) }
    }

    override suspend fun leave() {
        callback?.let(::release)
    }

    private fun release(cb: ConnectivityManager.NetworkCallback) {
        if (callback == cb) callback = null
        try {
            connectivity.unregisterNetworkCallback(cb)
        } catch (_: IllegalArgumentException) {
            // Already released.
        }
    }
}
