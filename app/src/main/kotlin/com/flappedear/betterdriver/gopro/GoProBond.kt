@file:SuppressLint("MissingPermission") // The app checks Bluetooth permissions before it pairs or connects.

package com.flappedear.betterdriver.gopro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** The Bluetooth device at [address]; throws [IOException] if Bluetooth is off or the address is bad. */
internal fun remoteDevice(context: Context, address: String): BluetoothDevice {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    if (adapter == null || !adapter.isEnabled) throw IOException("Bluetooth is off")
    return try {
        adapter.getRemoteDevice(address)
    } catch (e: IllegalArgumentException) {
        throw IOException("bad address $address", e)
    }
}

/**
 * Pairs with [device] and waits until Android reports it bonded. Open GoPro needs this once per
 * phone, with the camera in pairing mode (https://gopro.github.io/OpenGoPro/ble/protocol/ble_setup.html).
 */
internal suspend fun bond(context: Context, device: BluetoothDevice, timeoutMs: Long) {
    if (device.bondState == BluetoothDevice.BOND_BONDED) return
    val bonded = CompletableDeferred<Unit>()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (bondDevice(intent)?.address != device.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> bonded.complete(Unit)
                BluetoothDevice.BOND_NONE -> bonded.completeExceptionally(IOException("pairing refused"))
            }
        }
    }
    ContextCompat.registerReceiver(
        context,
        receiver,
        IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
        ContextCompat.RECEIVER_EXPORTED,
    )
    try {
        val started = device.bondState == BluetoothDevice.BOND_BONDING || device.createBond()
        if (!started) throw IOException("pairing did not start")
        if (device.bondState != BluetoothDevice.BOND_BONDED) withTimeout(timeoutMs) { bonded.await() }
    } finally {
        context.unregisterReceiver(receiver)
    }
}

@Suppress("DEPRECATION") // getParcelableExtra(String) before Android 13.
private fun bondDevice(intent: Intent): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }
