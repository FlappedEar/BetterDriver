package com.flappedear.betterdriver.core.ble.nmea

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import com.flappedear.betterdriver.core.ble.LinkState
import java.io.IOException
import java.io.InputStream

/**
 * Keeps a Bluetooth Classic Serial Port Profile (RFCOMM) link to one paired NMEA receiver: connect,
 * read on a dedicated thread, and reconnect with back-off after a drop, a failed connect, or 5 s
 * without data, until [stop] (see [StreamLink] and [StreamTiming]). [onData] gets every chunk read,
 * on the reader thread, and must return quickly. [onState] is called with each new [LinkState], one
 * call at a time; [LinkState.Streaming] carries the number of NMEA sentences (line ends) in the last
 * second.
 *
 * The receiver must be bonded (paired in Android settings). The secure socket is tried first; some
 * receivers only accept an unauthenticated one, so the insecure socket is the fallback. Discovery
 * is cancelled before connecting because it slows a classic connect down a lot.
 *
 * Bluetooth permissions are checked by the app before it starts a link. If BLUETOOTH_CONNECT is
 * missing the SecurityException is reported as the failure reason and retried, never thrown.
 */
@SuppressLint("MissingPermission")
class SppLink(
    context: Context,
    private val address: String,
    onData: (ByteArray) -> Unit,
    onState: (LinkState) -> Unit = {},
) : StreamLink("nmea-spp-link", onData, onState, StreamTiming(), SystemClock::elapsedRealtime) {
    private val appContext = context.applicationContext

    override fun open(): InputStream {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) throw LinkFailure("bluetooth off")
        val device = remoteDevice(adapter, address)
        cancelDiscovery(adapter)
        val secure = try {
            return connect(device.createRfcommSocketToServiceRecord(SppUuid)).inputStream
        } catch (e: IOException) {
            e
        }
        return try {
            connect(device.createInsecureRfcommSocketToServiceRecord(SppUuid)).inputStream
        } catch (insecure: IOException) {
            insecure.addSuppressed(secure)
            throw LinkFailure("connect failed: ${secure.message}; insecure: ${insecure.message}", insecure)
        }
    }

    /** Blocks until connected. [stop] closes the socket, which makes this throw. */
    private fun connect(s: BluetoothSocket): BluetoothSocket {
        attach(s)
        try {
            s.connect()
        } catch (e: IOException) {
            closeQuietly(s)
            throw e
        }
        return s
    }
}

private fun remoteDevice(adapter: BluetoothAdapter, address: String): BluetoothDevice = try {
    adapter.getRemoteDevice(address)
} catch (e: IllegalArgumentException) {
    throw LinkFailure("bad address: ${e.message}", e)
}

/** Needs BLUETOOTH_SCAN on Android 12+. Only a speed-up, so going without it is fine. */
@SuppressLint("MissingPermission")
private fun cancelDiscovery(adapter: BluetoothAdapter) {
    try {
        adapter.cancelDiscovery()
    } catch (_: SecurityException) {
        // Connect anyway; it is only slower while another app is discovering.
    }
}
