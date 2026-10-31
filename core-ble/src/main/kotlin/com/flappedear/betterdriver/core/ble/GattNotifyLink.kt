package com.flappedear.betterdriver.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val RequestedMtu = 517
private const val WatchdogMs = 1_000L

/** How long the MTU answer may take before the services are looked up without it, as in [GattLink]. */
private const val MtuAnswerMs = 2_000L

/**
 * What a [GattNotifyLink] subscribes to: the [characteristic] of [service], notified by the device.
 * [threadName] names the link's thread in traces. A link silent for [silenceTimeoutMs] is restarted.
 */
data class GattNotifySpec(
    val service: UUID,
    val characteristic: UUID,
    val threadName: String,
    val silenceTimeoutMs: Long = 5_000L,
)

/** A link that streams one device's notifications until stopped: [GattNotifyLink], or a simulated device in tests. */
interface NotifyLink {
    val state: StateFlow<LinkState>

    fun start()

    fun stop()
}

/**
 * Keeps a BLE link to one device that notifies a standard characteristic (Location and Speed,
 * Heart Rate Measurement, ...): connect, larger MTU, subscribe, and reconnect on its own after a
 * drop, a GATT error, no data within 15 s of connecting (30 s on a background connection), or
 * [GattNotifySpec.silenceTimeoutMs] without data, until [stop]. The same life cycle as
 * [RaceBoxLink], without the RaceBox's diagnostics probe. Each setup step is logged (FBBD-279);
 * setups that bring no data change how the next one connects ([SetupLadder]: without the MTU
 * request, then Android's background connection every other attempt), and an MTU request without
 * an answer in 2 s is skipped. After [QuietAfterSetups] setups without data the link waits on one
 * background connection until the device advertises, without further rounds (FBBD-281). No
 * bonding is asked for; a device that needs it for the notification descriptor makes Android pair
 * on the descriptor write. Every GATT call runs on one
 * private thread; [onData] is called on Android's binder thread with each notification's bytes and
 * must return quickly. [onState] gets each new [LinkState]; [LinkState.Streaming] carries the
 * notifications in the last second.
 *
 * Bluetooth permissions are checked by the app before it starts a link. If one is missing, the
 * resulting SecurityException is treated like any other failure and retried.
 */
@SuppressLint("MissingPermission")
@Suppress("TooManyFunctions") // One function per link step, one callback per GATT event.
class GattNotifyLink(
    context: Context,
    private val address: String,
    private val spec: GattNotifySpec,
    private val onData: (ByteArray) -> Unit,
    private val onState: (LinkState) -> Unit = {},
) : NotifyLink {
    private val appContext = context.applicationContext
    private val thread = HandlerThread(spec.threadName).apply { start() }
    private val handler = Handler(thread.looper)
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    /** The link's life cycle, shared with the iPhone (FBBD-358); touched only on [thread]. */
    private val core = NotifyLinkCore(
        address, Gatt(), SystemClock::elapsedRealtime,
        { s ->
            _state.value = s
            onState(s)
        },
        spec.silenceTimeoutMs,
    )

    // Touched only on [thread].
    private var gatt: BluetoothGatt? = null
    private var awaitingMtu = false

    /** Notifications count only from [gatt]; one from a closed connection is dropped (audit A09). */
    private val connection = CurrentConnection<BluetoothGatt>()

    override fun start() {
        handler.post {
            core.start()
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, WatchdogMs)
        }
    }

    /** Disconnects and ends the link's thread. The link cannot be started again. */
    override fun stop() {
        handler.post {
            handler.removeCallbacksAndMessages(null)
            core.stop()
            thread.quitSafely()
        }
    }

    /** Once a second: the core reports the rate and restarts a link that went silent or never set up. */
    private val watchdog = object : Runnable {
        override fun run() {
            core.tick()
            if (core.state != LinkState.Idle) handler.postDelayed(this, WatchdogMs)
        }
    }

    /** The GATT calls the core asks for, on [thread]. */
    private inner class Gatt : NotifyLinkIo {
        override fun open(plan: ConnectPlan): String? = try {
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                "bluetooth off"
            } else {
                val device = adapter.getRemoteDevice(address)
                gatt = device.connectGatt(appContext, plan.autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
                gatt?.let(connection::open)
                if (gatt == null) "connectGatt returned null" else null
            }
        } catch (e: SecurityException) {
            "no permission: ${e.message}"
        } catch (e: IllegalArgumentException) {
            "bad address: ${e.message}"
        }

        override fun close(setupFailed: Boolean) {
            val failed = gatt
            // Android may hold a stale copy of the device's services; the next connection finds them again.
            if (setupFailed && failed != null && !refreshCache(failed)) core.log("service cache could not be cleared")
            closeGatt()
        }

        override fun later(delayMs: Long, action: () -> Unit) {
            handler.postDelayed(action, delayMs)
        }

        override fun detail(): String? = try {
            appContext.getSystemService(BluetoothManager::class.java)?.adapter
                ?.takeIf { it.isEnabled }?.getRemoteDevice(address)?.let(::bondName)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            // No Bluetooth permission: open() reports it as the failure.
            null
        }
    }

    private fun closeGatt() {
        handler.removeCallbacks(mtuOverdue)
        awaitingMtu = false
        // Before the state change that resets the decoder: no old notification gets through after it.
        connection.close()
        val g = gatt ?: return
        gatt = null
        try {
            g.disconnect()
            g.close()
        } catch (_: SecurityException) {
            // Nothing more to release.
        }
    }

    /** Looks up the device's services, the step after the MTU exchange (or instead of it). */
    private fun discover(g: BluetoothGatt) {
        core.reached(NotifyStep.SERVICES)
        if (!g.discoverServices()) core.failed("discoverServices failed")
    }

    private val mtuOverdue = Runnable {
        val g = gatt
        if (g != null && awaitingMtu) {
            awaitingMtu = false
            core.log("no MTU answer in $MtuAnswerMs ms, finding services without it")
            discover(g)
        }
    }

    private fun subscribe(g: BluetoothGatt) {
        val service = g.getService(spec.service)
        val ch = service?.getCharacteristic(spec.characteristic)
        val cccd = ch?.getDescriptor(RaceBoxGatt.CCCD)
        if (service == null) {
            val found = g.services.joinToString { it.uuid.toString() }
            core.log("services without ${spec.service}: [$found]")
        }
        core.reached(NotifyStep.NOTIFICATIONS)
        val failure = when {
            service == null -> "service ${spec.service} not found"
            ch == null -> "characteristic ${spec.characteristic} not found"
            cccd == null -> "notification descriptor not found"
            !g.setCharacteristicNotification(ch, true) -> "setCharacteristicNotification failed"
            !enableNotifications(g, cccd) -> "enabling notifications failed"
            else -> null
        }
        if (failure != null) core.failed(failure)
    }

    @Suppress("DEPRECATION") // The pre-Android 13 descriptor write.
    private fun enableNotifications(g: BluetoothGatt, cccd: BluetoothGattDescriptor): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }

    /** A notification of [g], on the binder thread: given on at once, counted on the link's thread. */
    private fun data(g: BluetoothGatt, value: ByteArray) {
        onData(value)
        handler.post { if (g == gatt) core.data() }
    }

    /** Callbacks arrive on a binder thread; anything that touches [gatt] is posted to [thread]. */
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    core.connected()
                    // A Location and Speed value with every field is 28 bytes, more than the default
                    // MTU carries; without a larger one the device splits it over notifications.
                    // Skipped when it held setups up: a split value is lost, the link is not.
                    if (core.plan.askMtu && g.requestMtu(RequestedMtu)) {
                        core.reached(NotifyStep.MTU)
                        awaitingMtu = true
                        handler.postDelayed(mtuOverdue, MtuAnswerMs)
                    } else {
                        discover(g)
                    }
                } else {
                    core.failed("connection state $newState, status $status")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (g != gatt) return@post
                core.log("MTU $mtu, status $status, ${core.elapsed()} ms after connecting began")
                // A late answer, or one the device started, must not look the services up twice.
                if (awaitingMtu) {
                    awaitingMtu = false
                    handler.removeCallbacks(mtuOverdue)
                    discover(g)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g != gatt) return@post
                core.log("services found, status $status, ${core.elapsed()} ms after connecting began")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    subscribe(g)
                } else {
                    core.failed("service discovery status $status")
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (g != gatt) return@post
                core.log("notifications on, status $status, ${core.elapsed()} ms after connecting began")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    core.reached(NotifyStep.FIRST_VALUE)
                } else {
                    // 5 or 15: the device wants pairing first; the bond state says whether it happened.
                    core.failed("enable notifications status $status, ${bondName(g.device)}")
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == spec.characteristic) connection.deliver(g) { data(g, value) }
        }

        @Deprecated("Called instead of the overload above before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ch.uuid == spec.characteristic) {
                ch.value?.let { v -> connection.deliver(g) { data(g, v.copyOf()) } }
            }
        }
    }
}

/** A device's bond state for the log: whether it is paired with the phone. */
@SuppressLint("MissingPermission")
private fun bondName(device: BluetoothDevice): String =
    try {
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> "paired"
            BluetoothDevice.BOND_BONDING -> "pairing"
            else -> "not paired"
        }
    } catch (_: SecurityException) {
        "pairing unknown"
    }
