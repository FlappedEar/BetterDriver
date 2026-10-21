package com.flappedear.betterdriver.core.ble.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.flappedear.betterdriver.core.ble.nmea.SppUuid
import com.flappedear.betterdriver.core.ble.nmea.TcpNetwork
import com.flappedear.betterdriver.obd.ObdAdapterNames
import com.flappedear.betterdriver.obd.ObdBytes
import com.flappedear.betterdriver.obd.ObdConnection
import com.flappedear.betterdriver.obd.ObdTarget
import com.flappedear.betterdriver.obd.ObdTransport
import com.flappedear.betterdriver.obd.StreamObdConnection
import java.io.IOException
import java.net.InetSocketAddress

private const val WifiConnectTimeoutMs = 3_000

/** A Wi-Fi ELM327 [name] at [host]:[port], connected through [network] (FBBD-15). */
class WifiObdTarget(
    val host: String,
    val port: Int,
    private val network: TcpNetwork,
    override val name: String = "Wi-Fi adapter",
) : ObdTarget {
    override val where = "$host:$port"
    override val transport = ObdTransport.Wifi

    override fun open(bytes: ObdBytes): ObdConnection {
        val s = network.createSocket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(network.resolve(host), port), WifiConnectTimeoutMs)
        } catch (e: IOException) {
            closeQuietly(s)
            throw e
        }
        return StreamObdConnection(s.getInputStream(), s.getOutputStream(), s, bytes, "obd-wifi")
    }
}

/**
 * A paired Bluetooth Classic ELM327 (FBBD-121), over the Serial Port Profile. The secure RFCOMM
 * socket is tried first and the insecure one after it, as for NMEA receivers ([SppUuid]); discovery
 * is cancelled first because it slows a classic connect down a lot.
 */
@SuppressLint("MissingPermission")
class SppObdTarget(context: Context, name: String, override val address: String) : ObdTarget {
    private val appContext = context.applicationContext
    override val name = name.ifEmpty { address }
    override val where = "${this.name} (Bluetooth)"
    override val transport = ObdTransport.BluetoothClassic

    override fun open(bytes: ObdBytes): ObdConnection {
        val adapter = bluetooth(appContext) ?: throw IOException("Bluetooth off")
        val device = remote(adapter, address)
        try {
            adapter.cancelDiscovery()
        } catch (_: SecurityException) {
            // Connect anyway; it is only slower while another app is discovering.
        }
        val socket = try {
            connect(device.createRfcommSocketToServiceRecord(SppUuid))
        } catch (secure: IOException) {
            try {
                connect(device.createInsecureRfcommSocketToServiceRecord(SppUuid))
            } catch (insecure: IOException) {
                throw IOException("connect failed: ${secure.message}; insecure: ${insecure.message}", secure)
                    .apply { addSuppressed(insecure) }
            }
        }
        return StreamObdConnection(socket.inputStream, socket.outputStream, socket, bytes, "obd-spp")
    }

    private fun connect(s: BluetoothSocket): BluetoothSocket {
        try {
            s.connect()
        } catch (e: IOException) {
            closeQuietly(s)
            throw e
        }
        return s
    }
}

/** A paired Bluetooth device whose name is an OBD-II adapter's, offered in Add equipment (FBBD-121). */
data class PairedObdAdapter(val name: String, val address: String)

/**
 * Paired Bluetooth devices whose name is an OBD-II adapter's ([ObdAdapterNames]), for Add equipment
 * (UI-105 C). Empty when Bluetooth is off or BLUETOOTH_CONNECT is missing.
 */
@SuppressLint("MissingPermission")
fun pairedObdAdapters(context: Context): List<PairedObdAdapter> {
    val adapter = bluetooth(context) ?: return emptyList()
    val bonded: Set<BluetoothDevice> = try {
        adapter.bondedDevices.orEmpty()
    } catch (_: SecurityException) {
        emptySet()
    }
    return bonded.mapNotNull { d ->
        try {
            val name: String? = d.name
            if (ObdAdapterNames.isCandidate(name)) PairedObdAdapter(name.orEmpty(), d.address) else null
        } catch (_: SecurityException) {
            null
        }
    }.sortedBy { it.name.lowercase() }
}

/**
 * How the listed Bluetooth adapter at [address] is reached (FBBD-121): a paired Classic or
 * dual-mode one over the serial port profile, a Bluetooth LE only one, or one that is not paired
 * (only a Bluetooth LE adapter works without pairing), over GATT. Null with Bluetooth off.
 */
@SuppressLint("MissingPermission")
fun bluetoothObdTransport(context: Context, address: String): ObdTransport? {
    val adapter = bluetooth(context) ?: return null
    val device = try {
        adapter.bondedDevices.orEmpty().firstOrNull { it.address == address }
    } catch (_: SecurityException) {
        null
    }
    return if (device == null || device.type == BluetoothDevice.DEVICE_TYPE_LE) {
        ObdTransport.BluetoothLe
    } else {
        ObdTransport.BluetoothClassic
    }
}

/** The listed Bluetooth adapter [name] at [address] as a target, over its transport ([bluetoothObdTransport]). */
fun bluetoothObdTarget(context: Context, name: String, address: String): ObdTarget =
    when (bluetoothObdTransport(context, address)) {
        ObdTransport.BluetoothLe -> BleObdTarget(context, name, address)
        // With Bluetooth off either one fails to open, saying so.
        else -> SppObdTarget(context, name, address)
    }

internal fun bluetooth(context: Context): BluetoothAdapter? =
    context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf {
        try {
            it.isEnabled
        } catch (_: SecurityException) {
            false
        }
    }

internal fun remote(adapter: BluetoothAdapter, address: String): BluetoothDevice = try {
    adapter.getRemoteDevice(address)
} catch (e: IllegalArgumentException) {
    throw IOException("bad address: ${e.message}", e)
}

private fun closeQuietly(c: java.io.Closeable) {
    try {
        c.close()
    } catch (_: IOException) {
        // Already closed.
    }
}
