package com.flappedear.betterdriver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.flappedear.betterdriver.core.ble.obd.bluetoothObdTransport
import com.flappedear.betterdriver.obd.ObdTransport
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.setup.HeldDevice
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.heldDevices
import com.flappedear.betterdriver.shell.AppPrefs

/**
 * Keeps the Bluetooth LE link to every device chosen for the next session open from the moment it is
 * chosen until the app closes (Arek 2026-11-05, FBBD-464, as iOS's HeldConnections, FBBD-463): a
 * vLinker can need a restart after its link drops, a RaceBox loses its fix and an 808S strap switches
 * itself off without a receiver. The devices are the shared rule's ([heldDevices]).
 *
 * In the UI process, one GATT client per device that only connects (a background connection, which
 * Android keeps asking for until the device is back): no service discovery, no notifications. The
 * recorder process's links keep their own clients and do their own setup on the link this one keeps
 * up; Android keeps one radio link per device while any client holds it, so a recording link that
 * stops or restarts closes only its own client. Main thread only. Not on tablets (no recording there).
 */
// Every call is behind AppPrefs.hasRequiredPermissions and catches SecurityException.
@SuppressLint("MissingPermission")
class HeldLinks private constructor(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val clients = mutableMapOf<String, BluetoothGatt>()
    private var wanted: List<HeldDevice> = emptyList()
    private var receiverOn = false

    /** The addresses (upper case) of the held devices connected now, for the lists' "Connected". */
    var connected by mutableStateOf(emptySet<String>())
        private set

    /** Holds the devices [setup] chooses and lets go of the others. */
    fun follow(setup: Setup) {
        val devices = heldDevices(setup) { bluetoothObdTransport(context, it) != ObdTransport.BluetoothClassic }
        hold(devices)
    }

    private fun hold(devices: List<HeldDevice>) {
        if (!AppPrefs(context).hasRequiredPermissions()) return
        listenToBluetooth()
        val keep = devices.map { it.address.uppercase() }.toSet()
        clients.keys.filter { it !in keep }.forEach { address ->
            DebugLog.i(Tag, "let go ${wanted.firstOrNull { it.address.uppercase() == address }?.name ?: address}")
            close(address)
        }
        wanted = devices
        devices.forEach(::open)
    }

    private fun open(device: HeldDevice) {
        val address = device.address.uppercase()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val usable = address !in clients && adapter != null && adapter.isEnabled &&
            BluetoothAdapter.checkBluetoothAddress(address)
        if (!usable || adapter == null) return
        try {
            val gatt = adapter.getRemoteDevice(address)
                .connectGatt(context, true, callback(device.name), BluetoothDevice.TRANSPORT_LE)
            if (gatt != null) {
                clients[address] = gatt
                DebugLog.i(Tag, "holding ${device.name}")
            }
        } catch (e: SecurityException) {
            DebugLog.w(Tag, "no permission for ${device.name}: ${e.message}")
        }
    }

    private fun close(address: String) {
        val gatt = clients.remove(address) ?: return
        try {
            gatt.disconnect()
            gatt.close()
        } catch (_: SecurityException) {
            // Permission taken away: the client goes with the process.
        }
        connected = connected - address
    }

    private fun callback(name: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address.uppercase()
            main.post {
                if (clients[address] !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    DebugLog.i(Tag, "$name connected")
                    connected = connected + address
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // Out of range or switched off: a background connection waits for it to come back.
                    DebugLog.i(Tag, "$name dropped (status $status); waiting for it")
                    connected = connected - address
                    try {
                        gatt.connect()
                    } catch (_: SecurityException) {
                        close(address)
                    }
                }
            }
        }
    }

    /** Bluetooth off ends every client; once it is on again they are opened afresh. */
    private fun listenToBluetooth() {
        if (receiverOn) return
        receiverOn = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> {
                        DebugLog.i(Tag, "Bluetooth off")
                        clients.keys.toList().forEach(::close)
                    }
                    BluetoothAdapter.STATE_ON -> {
                        DebugLog.i(Tag, "Bluetooth on")
                        wanted.forEach(::open)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    companion object {
        /** The log tag, as on iOS. */
        const val Tag = "HeldLinks"

        @SuppressLint("StaticFieldLeak") // The application context.
        @Volatile private var instance: HeldLinks? = null

        fun get(context: Context): HeldLinks = instance ?: synchronized(this) {
            instance ?: HeldLinks(context.applicationContext).also { instance = it }
        }
    }
}
