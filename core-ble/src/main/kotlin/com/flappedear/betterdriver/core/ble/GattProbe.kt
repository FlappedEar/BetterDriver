package com.flappedear.betterdriver.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import java.util.UUID

/**
 * Extra reads for the diagnostics screen (FBBD-89). The recorder never passes one to [RaceBoxLink],
 * so its link does exactly what it did before. All calls arrive on the link's own thread.
 */
interface LinkProbe {
    fun onMtu(mtu: Int)

    /** A Device Information Service string; [uuid] is one of [RaceBoxGatt.DEVICE_INFO_CHARACTERISTICS]. */
    fun onDeviceInfo(uuid: UUID, value: String)

    fun onRssi(dbm: Int)

    /**
     * Read-only queries to write to the device once the Device Information strings are in. Called
     * once per connection, so the answer can depend on the firmware version.
     */
    fun queries(): List<ByteArray>
}

/**
 * Runs a [LinkProbe]'s GATT operations for [RaceBoxLink]. GATT allows one outstanding read or
 * write, so they run one at a time: each result callback calls [next]. Used only on the link's thread.
 */
@SuppressLint("MissingPermission")
internal class GattProbe(private val probe: LinkProbe) {
    private val ops = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var busySince = 0L

    /** Forget queued work, e.g. before a reconnect. */
    fun clear() {
        ops.clear()
        busySince = 0L
    }

    /** Once notifications are on: the Device Information reads, then the probe's queries. */
    fun start(g: BluetoothGatt, now: Long) {
        clear()
        val info = g.getService(RaceBoxGatt.DEVICE_INFO_SERVICE)
        RaceBoxGatt.DEVICE_INFO_CHARACTERISTICS.forEach { uuid ->
            info?.getCharacteristic(uuid)?.let { ch -> ops.addLast { it.readCharacteristic(ch) } }
        }
        ops.addLast { gatt ->
            probe.queries().forEach { q -> ops.addLast { write(it, q) } }
            gatt.readRemoteRssi()
        }
        next(g, now)
    }

    /** Runs queued operations until one starts; its result callback calls this again. */
    fun next(g: BluetoothGatt, now: Long) {
        busySince = 0L
        while (ops.isNotEmpty()) {
            val started = try {
                ops.removeFirst()(g)
            } catch (_: SecurityException) {
                false
            }
            if (started) {
                busySince = now
                return
            }
        }
    }

    /** Once a second while streaming: signal strength, and moving on from an operation that never answered. */
    fun tick(g: BluetoothGatt, now: Long) {
        when {
            busySince != 0L && now - busySince > OpTimeoutMs -> next(g, now)
            busySince == 0L && ops.isEmpty() -> {
                ops.addLast { it.readRemoteRssi() }
                next(g, now)
            }
        }
    }

    fun mtu(mtu: Int) = probe.onMtu(mtu)

    fun rssi(dbm: Int) = probe.onRssi(dbm)

    fun deviceInfo(uuid: UUID, value: ByteArray) {
        if (uuid in RaceBoxGatt.DEVICE_INFO_CHARACTERISTICS) {
            probe.onDeviceInfo(uuid, value.toString(Charsets.UTF_8).trim { it <= ' ' })
        }
    }

    @Suppress("DEPRECATION") // The pre-Android 13 characteristic write.
    private fun write(g: BluetoothGatt, bytes: ByteArray): Boolean {
        val ch = g.getService(RaceBoxGatt.UART_SERVICE)?.getCharacteristic(RaceBoxGatt.UART_RX) ?: return false
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = type
            ch.value = bytes
            g.writeCharacteristic(ch)
        }
    }

    private companion object {
        const val OpTimeoutMs = 2_000L
    }
}
