package com.flappedear.betterdriver.core.ble.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.flappedear.betterdriver.core.ble.BleSetups
import com.flappedear.betterdriver.core.ble.FoundBleDevice
import com.flappedear.betterdriver.core.ble.RaceBoxGatt
import com.flappedear.betterdriver.core.ble.scanBleDevices
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
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow

private const val RequestedMtu = 517
private const val NanosPerMs = 1_000_000L
private const val Tag = "Link"
private const val SetupTimeoutMs = 15_000L
private const val IntervalUnitMs = 1.25
private const val TimeoutUnitMs = 10

/** How long the adapter lets another Bluetooth LE link (the RaceBox) finish connecting first (FBBD-282). */
private const val QuietWaitMs = 5_000L

/**
 * A Bluetooth LE ELM327 (FBBD-121). [open] connects, asks for a larger MTU, finds the serial pair
 * ([BleSerial.choose]) and turns its notifications on, then hands the line to a
 * [BleSerialConnection]; it blocks until that is done or fails, at most 15 s a connection. The steps
 * are [ObdGattSetup]'s, driven by the GATT callbacks on a binder thread. A setup that fails with a
 * GATT status (133, 147, ...) connects again itself after 0.5 s, then 1.5 s ([ObdQuickRetry],
 * FBBD-271). No bonding is asked for; an adapter that needs it makes Android pair on the descriptor
 * write. While another link of the app (the RaceBox) is still connecting, [open] first waits up to 5 s
 * for it ([BleSetups], FBBD-282): on 21 Oct 2026 the two connected at the same moment and the
 * RaceBox's service lookup never answered.
 */
@SuppressLint("MissingPermission")
class BleObdTarget(context: Context, name: String, override val address: String) : ObdTarget {
    private val appContext = context.applicationContext
    override val name = name.ifEmpty { address }
    override val where = "${this.name} (Bluetooth LE)"
    override val transport = ObdTransport.BluetoothLe

    override fun open(bytes: ObdBytes): ObdConnection {
        val adapter = bluetooth(appContext) ?: throw IOException("Bluetooth off")
        val began = System.nanoTime()
        val busy = BleSetups.Shared.awaitQuiet(address, QuietWaitMs)
        val waited = (System.nanoTime() - began) / NanosPerMs
        if (busy.isNotEmpty()) {
            DebugLog.i(Tag, "$address connecting while ${busy.joinToString()} still connects, after $waited ms")
        } else if (waited > 0) {
            DebugLog.i(Tag, "$address waited $waited ms for another Bluetooth LE link to connect first")
        }
        val start = System.nanoTime()
        var retries = 0
        while (true) {
            val (line, failure) = attempt(adapter, bytes)
            if (line != null) return line
            val elapsed = (System.nanoTime() - start) / NanosPerMs
            val delay = ObdQuickRetry.delayMs(failure, retries, elapsed)
                ?: throw IOException(failure.reason + if (retries > 0) " (after $retries quick retries)" else "")
            // FBBD-271: a status failure (133 above all) usually clears on the next connectGatt, while
            // the connector's own back-off would wait 5 s, then 10 s.
            DebugLog.i(Tag, "$address setup failed after $elapsed ms (${failure.reason}), again in $delay ms")
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                // Only stopping interrupts; the connector then ends its run.
                throw IOException("interrupted")
            }
            retries++
            if (bluetooth(appContext) == null) throw IOException("Bluetooth off")
        }
    }

    /**
     * One connectGatt and setup: the line, or null with why not. Unless the line is returned (whose
     * close releases it), the BluetoothGatt is always disconnected and closed here: a leaked one is a
     * classic cause of status 133 on the next connections.
     */
    private fun attempt(adapter: BluetoothAdapter, bytes: ObdBytes): Pair<ObdConnection?, ObdSetupFailure> {
        val callback = Callback(bytes, address)
        val gatt = remote(adapter, address).connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        callback.gatt = gatt
        val line = try {
            gatt?.let { callback.setup.await(SetupTimeoutMs) }
        } catch (_: InterruptedException) {
            null
        }
        if (line == null) {
            callback.setup.abandon()
            gatt?.let(::release)
        }
        val failure = when {
            gatt == null -> ObdSetupFailure("connectGatt returned null", quick = true)
            line != null -> ObdSetupFailure("connected", quick = false)
            else -> callback.setup.failure ?: ObdSetupFailure("interrupted", quick = false)
        }
        return line to failure
    }

    /**
     * The GATT callbacks of one connection, passed on to its [setup]. They come on binder threads: one
     * that threw would leave the setup waiting its 15 s, or the line deaf, with nothing in the log, so
     * whatever throws (a SecurityException once Nearby devices is taken away) fails the setup or ends
     * the line instead (FBBD-271).
     */
    private class Callback(private val bytes: ObdBytes, private val address: String) : BluetoothGattCallback() {
        /** Set from each callback too: connectGatt can call back before it returns. */
        @Volatile
        var gatt: BluetoothGatt? = null

        @Volatile
        private var pair: BleSerialPair? = null

        val setup = ObdGattSetup(Calls(), { DebugLog.i(Tag, "$address $it") })

        @Suppress("TooGenericExceptionCaught") // Anything thrown on a binder thread is a failed setup or line.
        private inline fun guarded(g: BluetoothGatt, what: String, step: () -> Unit) {
            gatt = g
            try {
                step()
            } catch (e: RuntimeException) {
                DebugLog.w(Tag, "$address $what threw", e)
                setup.failed("$what threw $e")
            }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) =
            guarded(g, "connection state") {
                if (status != BluetoothGatt.GATT_SUCCESS) DebugLog.i(Tag, "$address state $newState, status $status")
                setup.connectionState(status, newState)
            }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = guarded(g, "MTU") {
            DebugLog.i(Tag, "$address MTU $mtu, status $status")
            setup.mtuChanged(mtu, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) =
            guarded(g, "service lookup") { setup.servicesDiscovered(status) }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            guarded(g, "descriptor write") { setup.descriptorWritten(status) }

        /**
         * Hidden callback (see GattLink): the adapter link's interval, never asked for by the app. On
         * 28 Oct 2026 the FR-S CAN stream ran at 85 % of the 18:40 rate with twice the request latency
         * in the 20:04 drive; the log could not show whether the interval differed (FBBD-320).
         */
        @Suppress("unused", "UNUSED_PARAMETER")
        fun onConnectionUpdated(g: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
            DebugLog.i(
                Tag,
                "$address connection interval ${"%.2f".format(Locale.ROOT, interval * IntervalUnitMs)} ms, " +
                    "latency $latency, timeout ${timeout * TimeoutUnitMs} ms, status $status",
            )
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            guarded(g, "write answer") { setup.connection?.writeDone(status == BluetoothGatt.GATT_SUCCESS) }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
            guarded(g, "notification") { if (ch.uuid.toString() == pair?.notify) setup.connection?.notified(value) }

        @Deprecated("Called instead of the overload above before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) =
            guarded(g, "notification") {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ch.uuid.toString() == pair?.notify) {
                    ch.value?.let { v -> setup.connection?.notified(v.copyOf()) }
                }
            }

        /** The setup's GATT calls, on the connection's BluetoothGatt. */
        private inner class Calls : ObdGattCalls {
            override fun requestMtu() = gatt?.requestMtu(RequestedMtu) ?: false

            override fun discoverServices() = gatt?.discoverServices() ?: false

            @Suppress("ReturnCount") // Each early return is one way the adapter is not usable.
            override fun subscribe(): String? {
                val g = gatt ?: return "no connection"
                val all = g.services.flatMap { s ->
                    s.characteristics.map { GattCharacteristic(s.uuid.toString(), it.uuid.toString(), it.properties) }
                }
                val p = BleSerial.choose(all) ?: return "no serial characteristic in ${g.services.map { it.uuid }}"
                pair = p
                val ch = g.getService(UUID.fromString(p.service))?.getCharacteristic(UUID.fromString(p.notify))
                    ?: return "characteristic ${p.notify} gone"
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

            @Suppress("ReturnCount") // No connection, no pair found, or no write characteristic: no line.
            override fun line(payload: Int): BleSerialConnection? {
                val g = gatt ?: return null
                val p = pair ?: return null
                return g.getService(UUID.fromString(p.service))?.getCharacteristic(UUID.fromString(p.write))
                    ?.let { BleSerialConnection(Port(g, it, p.withResponse), bytes, payload) }
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

/**
 * Bluetooth LE OBD-II adapters advertising nearby ([ObdAdapterNames], by name or serial service), for
 * Add equipment (FBBD-121, UI-105 C): a Bluetooth LE adapter needs no pairing. Emits as [scanBleDevices] does.
 */
fun scanObdAdapters(context: Context): Flow<List<FoundBleDevice>> =
    scanBleDevices(context) { name, services ->
        ObdAdapterNames.isCandidate(name, services.orEmpty().map { it.toString() })
    }
