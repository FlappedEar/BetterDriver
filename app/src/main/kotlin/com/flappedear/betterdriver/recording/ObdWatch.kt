package com.flappedear.betterdriver.recording

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat

/**
 * Tells an OBD-II connection when the phone's way to its adapter may be back (FBBD-271), with a line
 * for the app log: a Wi-Fi network became available or was lost, Bluetooth was switched on or off, or
 * a Bluetooth device connected. The connection then tries again at once instead of after its back-off.
 *
 * On the drive of 20 Oct the Wi-Fi adapter dropped at 140 km/h and the log showed nothing of the
 * phone's Wi-Fi after it; these lines show next time whether the phone left the adapter's network
 * and when it came back.
 *
 * The Wi-Fi callback is a request for any Wi-Fi network without internet, held while the session
 * records, so Android counts the adapter's access point as in use by the app. Close the result to stop.
 */
internal fun obdWatch(context: Context, onChange: (String) -> Unit): AutoCloseable {
    val app = context.applicationContext
    val cm = app.getSystemService(ConnectivityManager::class.java)
    val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChange("Wi-Fi network available ($network)")

        override fun onLost(network: Network) = onChange("Wi-Fi network lost ($network)")
    }
    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    val watchingWifi = try {
        cm?.requestNetwork(request, network)
        cm != null
    } catch (e: SecurityException) {
        onChange("Wi-Fi not watched: ${e.message}")
        false
    }
    val bluetooth = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_ON -> onChange("Bluetooth on")
                        BluetoothAdapter.STATE_OFF -> onChange("Bluetooth off")
                    }
                BluetoothDevice.ACTION_ACL_CONNECTED -> onChange("Bluetooth device connected")
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
    }
    ContextCompat.registerReceiver(app, bluetooth, filter, ContextCompat.RECEIVER_EXPORTED)
    return AutoCloseable {
        app.unregisterReceiver(bluetooth)
        if (watchingWifi) cm?.unregisterNetworkCallback(network)
    }
}
