package com.flappedear.betterdriver.core.ble.obd

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
import android.os.SystemClock
import com.flappedear.betterdriver.core.ble.BleScan
import com.flappedear.betterdriver.core.ble.RaceBoxGatt
import com.flappedear.betterdriver.obd.BleSerial
import com.flappedear.betterdriver.obd.BleSerialConnection
import com.flappedear.betterdriver.obd.BleSerialPair
import com.flappedear.betterdriver.obd.BleSerialPort
import com.flappedear.betterdriver.obd.GattCharacteristic
import com.flappedear.betterdriver.obd.ObdAdapterNames
import com.flappedear.betterdriver.obd.ObdBytes
import com.flappedear.betterdriver.obd.ObdConnection
import com.flappedear.betterdriver.obd.ObdTarget
import com.flappedear.betterdriver.obd.ObdTransport
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val RequestedMtu = 517
private const val AttHeaderBytes = 3
private const val SetupTimeoutS = 15L

/** How long the fallback scan listens for a Bluetooth LE adapter that is not paired. */
private const val ScanMs = 4_000L

/**
 * A Bluetooth LE ELM327 (FBBD-121). [open] connects, asks for a larger MTU, finds the serial pair
 * ([BleSerial.choose]) and turns its notifications on, then hands the line to a
 * [BleSerialConnection]; it blocks until that is done or fails, at most 15 s. GATT callbacks come on
 * a binder thread and drive the next step straight from there. No bonding is asked for; an adapter
 * that needs it makes Android pair on the descriptor write.
 */
@SuppressLint("MissingPermission")
class BleObdTarget(context: Context, val name: String, val address: String) : ObdTarget {
    private val appContext = context.applicationContext
    override val where = "${name.ifEmpty { address }} (Bluetooth LE)"
    override val transport = ObdTransport.BluetoothLe

    override fun open(bytes: ObdBytes): ObdConnection {
        val adapter = bluetooth(appContext) ?: throw IOException("Bluetooth off")
        val setup = Setup(bytes)
        val gatt = remote(adapter, address).connectGatt(appContext, false, setup, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt returned null")
        val ok = try {
            setup.done.await(SetupTimeoutS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        val connection = setup.connection
        if (!ok || connection == null) {
            release(gatt)
            throw IOException(setup.failure ?: "no serial line within $SetupTimeoutS s")
        }
        return connection
    }

    private class Setup(private val bytes: ObdBytes) : BluetoothGattCallback() {
        val done = CountDownLatch(1)

        @Volatile
        var connection: BleSerialConnection? = null

        @Volatile
        var failure: String? = null

        private var mtu = BleSerialConnection.DEFAULT_PAYLOAD + AttHeaderBytes
        private var pair: BleSerialPair? = null

        private fun fail(reason: String) {
            val c = connection
            if (c != null) {
                c.lost(reason)
            } else {
                failure = reason
                done.countDown()
            }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(RequestedMtu) && !g.discoverServices()) fail("discoverServices failed")
            } else {
                fail("connection state $newState, status $status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            if (!g.discoverServices()) fail("discoverServices failed")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val why = if (status == BluetoothGatt.GATT_SUCCESS) subscribe(g) else "service discovery status $status"
            why?.let(::fail)
        }

        /** Finds the serial pair and turns its notifications on; null when that started, else why not. */
        @Suppress("ReturnCount") // Each early return is one way the adapter is not usable.
        private fun subscribe(g: BluetoothGatt): String? {
            val all = g.services.flatMap { s ->
                s.characteristics.map { GattCharacteristic(s.uuid, it.uuid, it.properties) }
            }
            val p = BleSerial.choose(all) ?: return "no serial characteristic in ${g.services.map { it.uuid }}"
            pair = p
            val ch = g.getService(p.service)?.getCharacteristic(p.notify) ?: return "characteristic ${p.notify} gone"
            val cccd = ch.getDescriptor(RaceBoxGatt.CCCD) ?: return "notification descriptor not found"
            val indicate = ch.properties and BleSerial.PROPERTY_NOTIFY == 0
            val value = if (indicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val started = g.setCharacteristicNotification(ch, true) && writeDescriptor(g, cccd, value)
            return if (started) null else "enabling notifications failed"
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val p = pair
            val ch = p?.let { g.getService(it.service)?.getCharacteristic(it.write) }
            if (status != BluetoothGatt.GATT_SUCCESS || p == null || ch == null) {
                return fail("enable notifications status $status")
            }
            connection = BleSerialConnection(Port(g, ch, p.withResponse), bytes, mtu - AttHeaderBytes)
            done.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            connection?.writeDone(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == pair?.notify) connection?.notified(value)
        }

        @Deprecated("Called instead of the overload above before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ch.uuid == pair?.notify) {
                ch.value?.let { connection?.notified(it.copyOf()) }
            }
        }
    }

    private class Port(
        private val gatt: BluetoothGatt,
        private val ch: BluetoothGattCharacteristic,
        withResponse: Boolean,
    ) : BleSerialPort {
        private val type = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        @Suppress("DEPRECATION") // The pre-Android 13 characteristic write.
        override fun write(chunk: ByteArray): Boolean = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, chunk, type) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = type
                ch.value = chunk
                gatt.writeCharacteristic(ch)
            }
        } catch (_: SecurityException) {
            false
        }

        override fun disconnect() = release(gatt)
    }
}

@Suppress("DEPRECATION") // The pre-Android 13 descriptor write.
@SuppressLint("MissingPermission")
private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
    } else {
        d.value = value
        g.writeDescriptor(d)
    }

@SuppressLint("MissingPermission")
private fun release(g: BluetoothGatt) {
    try {
        g.disconnect()
        g.close()
    } catch (_: SecurityException) {
        // Nothing more to release.
    }
}

/** At most one fallback scan per this long: a car with no adapter must not keep the radio scanning. */
private const val ScanEveryMs = 120_000L

/**
 * Bluetooth LE OBD-II adapters advertising nearby that are not paired (FBBD-121): the fallback when
 * no paired or Wi-Fi adapter answers, so a Bluetooth LE adapter needs no setup. It scans for 4 s at
 * most every 2 minutes and in between returns what the last scan found, so a session without any
 * adapter does not keep scanning next to the RaceBox link. Make one per link.
 */
class ObdLeScan(private val context: Context, private val nowMs: () -> Long = SystemClock::elapsedRealtime) {
    private var last: Long? = null
    private var found: List<ObdTarget> = emptyList()

    @Synchronized
    fun targets(): List<ObdTarget> {
        val now = nowMs()
        val at = last
        if (at == null || now - at >= ScanEveryMs) {
            found = scannedObdTargets(context)
            last = now
        }
        return found
    }
}

/**
 * Bluetooth LE OBD-II adapters advertising nearby, found by a 4 s listen on the shared [BleScan].
 * Empty when Bluetooth is off or a permission is missing.
 */
@SuppressLint("MissingPermission")
private fun scannedObdTargets(context: Context): List<ObdTarget> {
    if (bluetooth(context) == null) return emptyList()
    val found = LinkedHashMap<String, String>()
    runBlocking {
        // The scan never ends on its own; the timeout is how it stops.
        withTimeoutOrNull(ScanMs) {
            BleScan.results(context).collect { r ->
                val name = try {
                    r.scanRecord?.deviceName ?: r.device.name
                } catch (_: SecurityException) {
                    null
                }
                if (ObdAdapterNames.isCandidate(name)) found[r.device.address] = name.orEmpty()
            }
        }
    }
    return found.map { (address, name) -> BleObdTarget(context, name, address) }
}
