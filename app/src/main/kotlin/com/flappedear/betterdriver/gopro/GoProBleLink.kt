@file:SuppressLint("MissingPermission") // The app checks Bluetooth permissions before it creates a link.

package com.flappedear.betterdriver.gopro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private val Cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val BondTimeoutMs = 30_000L
private const val OperationTimeoutMs = 10_000L

/**
 * [GoProLink] over Android's BluetoothGatt, for one camera at [address].
 *
 * Follows the Open GoPro BLE setup (https://gopro.github.io/OpenGoPro/ble/protocol/ble_setup.html):
 * connect, pair once per phone (the camera must be in pairing mode the first time), then subscribe
 * to the notifying characteristics again on every connection, because the camera does not cache
 * subscriptions. Android allows one GATT operation at a time, so every operation waits for its
 * callback before the next starts. A drop fails the waiting operation with [IOException]; the
 * caller reconnects with [connect].
 *
 * Bluetooth permissions are checked by the app before it creates a link; a missing one surfaces as
 * an [IOException] like any other failure.
 */
class GoProBleLink(context: Context, private val address: String) : GoProLink {
    private val appContext = context.applicationContext
    private val ops = Mutex()
    private val _notifications = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    override val notifications: Flow<Pair<String, ByteArray>> = _notifications.asSharedFlow()

    @Volatile
    private var gatt: BluetoothGatt? = null

    /** The callback the running operation waits for; failed with an [IOException] on a drop. */
    @Volatile
    private var pending: CompletableDeferred<ByteArray>? = null

    @Volatile
    private var connected: CompletableDeferred<Unit>? = null

    override suspend fun connect() = ops.withLock {
        closeGatt()
        cleanUpOnFailure {
            val device = remoteDevice(appContext, address)
            val ready = CompletableDeferred<Unit>().also { connected = it }
            gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            checkNotNull(gatt) { "connectGatt returned null" }
            ready.await()
            if (device.bondState != BluetoothDevice.BOND_BONDED) bond(appContext, device, BondTimeoutMs)
            val g = checkNotNull(gatt) { "disconnected" }
            op { g.discoverServices() }
            for (uuid in listOf(GoProUuids.COMMAND_RESPONSE, GoProUuids.SETTINGS_RESPONSE, GoProUuids.QUERY_RESPONSE)) {
                subscribe(g, uuid)
            }
        }
    }

    /** Runs [block]; on any failure closes the connection, and reports it as an [IOException]. */
    private suspend fun cleanUpOnFailure(block: suspend () -> Unit) {
        var ok = false
        try {
            block()
            ok = true
        } catch (e: SecurityException) {
            throw IOException("no Bluetooth permission", e)
        } catch (e: IllegalStateException) {
            throw IOException(e.message, e)
        } finally {
            if (!ok) closeGatt()
        }
    }

    override suspend fun write(characteristic: String, packet: ByteArray) {
        ops.withLock {
            val g = gatt ?: throw IOException("not connected")
            val ch = characteristic(g, characteristic)
            op { writeCharacteristic(g, ch, packet) }
        }
    }

    override suspend fun read(characteristic: String): ByteArray = ops.withLock {
        val g = gatt ?: throw IOException("not connected")
        val ch = characteristic(g, characteristic)
        op { g.readCharacteristic(ch) }
    }

    override suspend fun disconnect() = closeGatt()

    private suspend fun subscribe(g: BluetoothGatt, uuid: String) {
        val ch = characteristic(g, uuid)
        val cccd = ch.getDescriptor(Cccd) ?: throw IOException("no notification descriptor on $uuid")
        if (!g.setCharacteristicNotification(ch, true)) throw IOException("could not subscribe to $uuid")
        op { writeDescriptor(g, cccd) }
    }

    /** Starts one GATT operation with [start] and waits for its callback. */
    private suspend fun op(start: () -> Boolean): ByteArray {
        val done = CompletableDeferred<ByteArray>()
        pending = done
        try {
            if (!start()) throw IOException("GATT busy or refused")
            return withTimeout(OperationTimeoutMs) { done.await() }
        } finally {
            pending = null
        }
    }

    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        fail("disconnected")
        try {
            g.disconnect()
            g.close()
        } catch (_: SecurityException) {
            // Nothing more to release.
        }
    }

    private fun fail(reason: String) {
        pending?.completeExceptionally(IOException(reason))
        connected?.completeExceptionally(IOException(reason))
    }

    private fun finish(status: Int, value: ByteArray = ByteArray(0)) {
        val p = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            p.complete(value)
        } else {
            p.completeExceptionally(IOException("GATT status $status"))
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != null && g != gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected?.complete(Unit)
            } else {
                fail("connection state $newState, status $status")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(status)

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            finish(status)

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            finish(status)

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = finish(status, value)

        @Deprecated("Called instead of the overload above before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) finish(status, ch.value?.copyOf() ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(ch.uuid.toString() to value)
        }

        @Deprecated("Called instead of the overload above before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                ch.value?.let { _notifications.tryEmit(ch.uuid.toString() to it.copyOf()) }
            }
        }
    }
}

private fun characteristic(g: BluetoothGatt, uuid: String): BluetoothGattCharacteristic {
    val id = UUID.fromString(uuid)
    return g.services.firstNotNullOfOrNull { it.getCharacteristic(id) }
        ?: throw IOException("characteristic $uuid not found")
}

@Suppress("DEPRECATION") // The pre-Android 13 write calls.
private fun writeCharacteristic(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean {
    val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeCharacteristic(ch, value, type) == BluetoothStatusCodes.SUCCESS
    } else {
        ch.writeType = type
        ch.value = value
        g.writeCharacteristic(ch)
    }
}

@Suppress("DEPRECATION") // The pre-Android 13 descriptor write.
private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
    } else {
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
    }
