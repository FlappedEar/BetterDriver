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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val RequestedMtu = 517
private const val WatchdogMs = 1_000L
private const val SilenceTimeoutMs = 3_000L
private const val SetupTimeoutMs = 10_000L

/**
 * Keeps a BLE link to one device (FBBD-43; generalised from the RaceBox link for the RaceChrono
 * DIY device, FBBD-118): connect, larger MTU, subscribe to the notifications of [profile], answer
 * its [GattHandshake] if it has one (the Dragy, FBBD-117), and reconnect on its own after a drop, a
 * GATT error, 10 s without data after connecting, or 3 s without data. No bonding is needed. Every
 * GATT call runs on one private thread; [onData] is called with the characteristic and its bytes,
 * on Android's binder thread for notifications and on the link's thread for [read] results, and
 * must return quickly.
 *
 * Bluetooth permissions are checked by the app before it starts a link. If one is missing, the
 * resulting SecurityException is treated like any other failure and retried.
 */
@SuppressLint("MissingPermission")
class GattLink(
    context: Context,
    private val address: String,
    private val profile: GattProfile,
    private val onData: (UUID, ByteArray) -> Unit,
    private val onState: (LinkState) -> Unit = {},
    probe: LinkProbe? = null,
) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread(profile.threadName).apply { start() }
    private val handler = Handler(thread.looper)
    private val policy = ReconnectPolicy()
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    // Touched only on [thread], except the counters written by the binder thread.
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var setupStartedAt = 0L

    /** Characteristics still to subscribe to on this connection; empty once all are on. */
    private val toSubscribe = ArrayDeque<BluetoothGattCharacteristic>()

    /** Between reading the handshake challenge and the answer's write being acknowledged. */
    private var handshaking = false
    private var subscribed = false

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

    /**
     * Reads characteristic [uuid] of the profile's service once; the value goes to [onData]. Ignored
     * until every subscription is on and the handshake (if any) is done, and when the device lacks
     * it. Not for a link with a
     * [LinkProbe], whose own reads and writes would collide with it (GATT does one at a time).
     */
    fun read(uuid: UUID) {
        handler.post {
            val g = gatt ?: return@post
            if (!subscribed) return@post
            val ch = g.getService(profile.service)?.getCharacteristic(uuid) ?: return@post
            try {
                g.readCharacteristic(ch)
            } catch (_: SecurityException) {
                // The next connection attempt reports it.
            }
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
        toSubscribe.clear()
        handshaking = false
        subscribed = false
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

    /**
     * Turns on the next notification in [toSubscribe]; [BluetoothGattCallback.onDescriptorWrite]
     * calls this again for the one after. When all are on, the handshake (if any) starts, else the
     * probe (if any).
     */
    private fun subscribeNext(g: BluetoothGatt) {
        val ch = toSubscribe.removeFirstOrNull()
        if (ch == null) {
            val handshake = profile.handshake
            if (handshake != null) {
                handshaking = true
                readChallenge(g, profile.service, handshake)?.let(::retry)
            } else {
                subscribed = true
                gattProbe?.start(g, SystemClock.elapsedRealtime())
            }
            return
        }
        val cccd = ch.getDescriptor(CccdUuid)
        val failure = when {
            cccd == null -> "notification descriptor of ${ch.uuid} not found"
            !g.setCharacteristicNotification(ch, true) -> "setCharacteristicNotification failed"
            !enableNotifications(g, cccd) -> "enabling notifications failed"
            else -> null
        }
        if (failure != null) retry(failure)
    }

    private fun data(uuid: UUID, value: ByteArray) {
        lastDataAt = SystemClock.elapsedRealtime()
        received.incrementAndGet()
        onData(uuid, value)
    }

    /** Callbacks arrive on a binder thread; anything that touches [gatt] is posted to [thread]. */
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g != gatt) return@post
                when {
                    status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        // The RaceBox data message is 88 bytes; a larger MTU avoids splitting it.
                        // Reassembly copes either way.
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
                val plan = if (status == BluetoothGatt.GATT_SUCCESS) {
                    subscriptions(g, profile)
                } else {
                    SubscriptionPlan(failure = "service discovery status $status")
                }
                toSubscribe.clear()
                toSubscribe.addAll(plan.characteristics)
                if (plan.failure != null) retry(plan.failure) else subscribeNext(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                when {
                    g != gatt -> Unit
                    status != BluetoothGatt.GATT_SUCCESS -> retry("enable notifications status $status")
                    else -> subscribeNext(g)
                }
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handler.post {
                if (g != gatt) return@post
                val handshake = profile.handshake?.takeIf { handshaking && ch.uuid == it.characteristic }
                if (handshake != null) {
                    answerChallenge(g, ch, handshake, value, status)?.let(::retry)
                    return@post
                }
                val ok = status == BluetoothGatt.GATT_SUCCESS
                // A read asked for with read(); never one of the probe's Device Information reads.
                if (ok && ch.uuid in profile.notify) data(ch.uuid, value)
                if (ok) gattProbe?.deviceInfo(ch.uuid, value)
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

        // The handshake's answer, then the rest of the probe's callbacks; without a probe these never fire.
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                val now = SystemClock.elapsedRealtime()
                when {
                    g != gatt -> Unit
                    !handshaking || ch.uuid != profile.handshake?.characteristic -> gattProbe?.next(g, now)
                    status != BluetoothGatt.GATT_SUCCESS -> retry("handshake write status $status")
                    else -> {
                        // Answered: the device starts streaming. Then the diagnostics reads, if any.
                        handshaking = false
                        subscribed = true
                        gattProbe?.start(g, now)
                    }
                }
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) gattProbe?.rssi(rssi)
                gattProbe?.next(g, SystemClock.elapsedRealtime())
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid in profile.notify) data(ch.uuid, value)
        }

        @Deprecated("Called instead of the overload above before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ch.uuid in profile.notify) {
                ch.value?.let { data(ch.uuid, it.copyOf()) }
            }
        }
    }
}

@Suppress("DEPRECATION") // The pre-Android 13 descriptor write.
@SuppressLint("MissingPermission")
private fun enableNotifications(g: BluetoothGatt, cccd: BluetoothGattDescriptor): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
    } else {
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(cccd)
    }

/** Reads the [handshake]'s challenge; null when the read started, else why it failed. */
@SuppressLint("MissingPermission")
private fun readChallenge(g: BluetoothGatt, service: UUID, handshake: GattHandshake): String? {
    val ch = g.getService(service)?.getCharacteristic(handshake.characteristic)
    return when {
        ch == null -> "handshake characteristic ${handshake.characteristic} not found"
        g.readCharacteristic(ch) -> null
        else -> "handshake read failed"
    }
}

/**
 * Writes the [handshake]'s answer to [challenge], read with [status], back to [ch] with a response;
 * null when the write started, else why the handshake failed.
 */
private fun answerChallenge(
    g: BluetoothGatt,
    ch: BluetoothGattCharacteristic,
    handshake: GattHandshake,
    challenge: ByteArray,
    status: Int,
): String? {
    val answer = if (status == BluetoothGatt.GATT_SUCCESS) handshake.respond(challenge) else null
    return when {
        status != BluetoothGatt.GATT_SUCCESS -> "handshake read status $status"
        answer == null -> "handshake challenge unusable (${challenge.size} bytes)"
        writeWithResponse(g, ch, answer) -> null
        else -> "handshake write failed"
    }
}

@Suppress("DEPRECATION") // The pre-Android 13 characteristic write.
@SuppressLint("MissingPermission")
private fun writeWithResponse(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
    } else {
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = value
        g.writeCharacteristic(ch)
    }

private class SubscriptionPlan(
    val characteristics: List<BluetoothGattCharacteristic> = emptyList(),
    val failure: String? = null,
)

/** What to subscribe to on [g] for [profile], in order, or why the device does not fit it. */
private fun subscriptions(g: BluetoothGatt, profile: GattProfile): SubscriptionPlan {
    val service = g.getService(profile.service)
        ?: return SubscriptionPlan(failure = "service ${profile.service} not found")
    val found = profile.notify.map { it to service.getCharacteristic(it) }
    val missing = found.firstOrNull { (uuid, ch) -> ch == null && uuid !in profile.optional }
    return if (missing != null) {
        SubscriptionPlan(failure = "characteristic ${missing.first} not found")
    } else {
        SubscriptionPlan(found.mapNotNull { (uuid, ch) -> ch?.takeIf { uuid !in profile.optional || it.notifies } })
    }
}

private val BluetoothGattCharacteristic.notifies: Boolean
    get() = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
