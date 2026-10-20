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

/** A Wi-Fi ELM327 at [host]:[port], connected through [network] (FBBD-15). */
class WifiObdTarget(val host: String, val port: Int, private val network: TcpNetwork) : ObdTarget {
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
class SppObdTarget(context: Context, val name: String, val address: String) : ObdTarget {
    private val appContext = context.applicationContext
    override val where = "$name (Bluetooth)"
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

/**
 * Paired Bluetooth devices whose name is an OBD-II adapter's ([ObdAdapterNames]), as adapters to
 * try (FBBD-121): a Classic or dual-mode one over SPP, a Bluetooth LE only one over GATT. Empty
 * when Bluetooth is off or BLUETOOTH_CONNECT is missing.
 */
@SuppressLint("MissingPermission")
fun pairedObdTargets(context: Context): List<ObdTarget> {
    val adapter = bluetooth(context) ?: return emptyList()
    val bonded: Set<BluetoothDevice> = try {
        adapter.bondedDevices.orEmpty()
    } catch (_: SecurityException) {
        emptySet()
    }
    return bonded.mapNotNull { d ->
        try {
            val name: String? = d.name
            when {
                !ObdAdapterNames.isCandidate(name) -> null
                d.type == BluetoothDevice.DEVICE_TYPE_LE -> BleObdTarget(context, name.orEmpty(), d.address)
                else -> SppObdTarget(context, name.orEmpty(), d.address)
            }
        } catch (_: SecurityException) {
            null
        }
    }.sortedBy { it.where.lowercase() }
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
