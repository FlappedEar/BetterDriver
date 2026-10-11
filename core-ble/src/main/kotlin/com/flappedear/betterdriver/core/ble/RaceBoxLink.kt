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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the link to the RaceBox is. Shown in the UI and logged by the data source. */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState

    /** Connected and subscribed; [hz] is the measured message rate over the last second. */
    data class Streaming(val hz: Int) : LinkState

    /** Lost; the next attempt starts after [delayMs]. [reason] is for logs. */
    data class Reconnecting(val attempt: Int, val delayMs: Long, val reason: String) : LinkState
}

private const val RequestedMtu = 517
private const val WatchdogMs = 1_000L
private const val SilenceTimeoutMs = 3_000L
private const val SetupTimeoutMs = 10_000L

/**
 * Keeps a BLE link to one RaceBox (FBBD-43): connect, larger MTU, subscribe to the UART TX
 * notifications, and reconnect on its own after a drop, a GATT error, or 3 s without data.
 * No bonding is needed. Every GATT call runs on one private thread; [onData] is called on Android's
 * binder thread with each notification's bytes and must return quickly.
 *
 * Bluetooth permissions are checked by the app before it starts a link. If one is missing, the
 * resulting SecurityException is treated like any other failure and retried.
 */
@SuppressLint("MissingPermission")
class RaceBoxLink(
    context: Context,
    private val address: String,
    private val onData: (ByteArray) -> Unit,
    private val onState: (LinkState) -> Unit = {},
    probe: LinkProbe? = null,
) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("racebox-link").apply { start() }
    private val handler = Handler(thread.looper)
    private val policy = ReconnectPolicy()
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    // Touched only on [thread], except the counters written by the binder thread.
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var setupStartedAt = 0L

    @Volatile
    private var lastDataAt = 0L

    private val received = AtomicInteger()
    private val gattProbe = probe?.let(::GattProbe)

    fun start() {
        handler.post {
            if (running) return@post
            running = true
            connect()
            handler.postDelayed(::watchdog, WatchdogMs)
        }
    }

    /** Disconnects and ends the link's thread. The link cannot be started again. */
    fun stop() {
        handler.post {
            running = false
            handler.removeCallbacksAndMessages(null)
            closeGatt()
            setState(LinkState.Idle)
            thread.quitSafely()
        }
    }

    private fun connect() {
        if (!running) return
        closeGatt()
        setState(LinkState.Connecting)
        setupStartedAt = SystemClock.elapsedRealtime()
        lastDataAt = 0L
        gattProbe?.clear()
        val failure = try {
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                "bluetooth off"
            } else {
                gatt = adapter.getRemoteDevice(address)
                    .connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) "connectGatt returned null" else null
            }
        } catch (e: SecurityException) {
            "no permission: ${e.message}"
        } catch (e: IllegalArgumentException) {
            "bad address: ${e.message}"
        }
        if (failure != null) retry(failure)
    }

    private fun retry(reason: String) {
        if (!running) return
        closeGatt()
        val delay = policy.nextDelayMs()
        setState(LinkState.Reconnecting(policy.attempts, delay, reason))
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, delay)
    }

    private val reconnect = Runnable { connect() }

    /** Once a second: report the rate, and restart a link that went silent or never finished setting up. */
    private fun watchdog() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        val count = received.getAndSet(0)
        val last = lastDataAt
        when {
            gatt == null -> Unit
            last != 0L && now - last > SilenceTimeoutMs -> retry("no data for ${now - last} ms")
            last == 0L && now - setupStartedAt > SetupTimeoutMs -> retry("no data after connecting")
            last != 0L -> {
                policy.reset()
                setState(LinkState.Streaming(count))
                gatt?.let { gattProbe?.tick(it, now) }
            }
        }
        handler.postDelayed(::watchdog, WatchdogMs)
    }

    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        try {
            g.disconnect()
            g.close()
        } catch (_: SecurityException) {
            // Nothing more to release.
        }
    }

    private fun setState(s: LinkState) {
        if (_state.value == s) return
        _state.value = s
        onState(s)
    }

    private fun subscribe(g: BluetoothGatt) {
        val ch = g.getService(RaceBoxGatt.UART_SERVICE)?.getCharacteristic(RaceBoxGatt.UART_TX)
        val cccd = ch?.getDescriptor(RaceBoxGatt.CCCD)
        val failure = when {
            ch == null -> "RaceBox UART service not found"
            cccd == null -> "notification descriptor not found"
            !g.setCharacteristicNotification(ch, true) -> "setCharacteristicNotification failed"
            !enableNotifications(g, cccd) -> "enabling notifications failed"
            else -> null
        }
        if (failure != null) retry(failure)
    }

    @Suppress("DEPRECATION") // The pre-Android 13 descriptor write.
    private fun enableNotifications(g: BluetoothGatt, cccd: BluetoothGattDescriptor): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }

    private fun data(value: ByteArray) {
        lastDataAt = SystemClock.elapsedRealtime()
        received.incrementAndGet()
        onData(value)
    }

    /** Callbacks arrive on a binder thread; anything that touches [gatt] is posted to [thread]. */
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g != gatt) return@post
                when {
                    status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        // The data message is 88 bytes; a larger MTU avoids splitting it. Reassembly copes either way.
                        if (!g.requestMtu(RequestedMtu)) g.discoverServices()
                    }
                    else -> retry("connection state $newState, status $status")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (g == gatt && status == BluetoothGatt.GATT_SUCCESS) gattProbe?.mtu(mtu)
                if (g == gatt && !g.discoverServices()) retry("discoverServices failed")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) subscribe(g) else retry("service discovery status $status")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                when {
                    g != gatt -> Unit
                    status != BluetoothGatt.GATT_SUCCESS -> retry("enable notifications status $status")
                    else -> gattProbe?.start(g, SystemClock.elapsedRealtime())
                }
            }
        }

        // The rest is for the diagnostics probe only; without one these callbacks never fire.
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handler.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) gattProbe?.deviceInfo(ch.uuid, value)
                gattProbe?.next(g, SystemClock.elapsedRealtime())
            }
        }

        @Deprecated("Called instead of the overload above before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onCharacteristicRead(g, ch, ch.value?.copyOf() ?: ByteArray(0), status)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            handler.post { if (g == gatt) gattProbe?.next(g, SystemClock.elapsedRealtime()) }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) gattProbe?.rssi(rssi)
                gattProbe?.next(g, SystemClock.elapsedRealtime())
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == RaceBoxGatt.UART_TX) data(value)
        }

        @Deprecated("Called instead of the overload above before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ch.uuid == RaceBoxGatt.UART_TX) {
                ch.value?.let { data(it.copyOf()) }
            }
        }
    }
}
