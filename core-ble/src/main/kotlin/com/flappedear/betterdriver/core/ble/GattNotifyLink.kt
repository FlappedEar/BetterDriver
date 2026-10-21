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
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val RequestedMtu = 517
private const val WatchdogMs = 1_000L
private const val SetupTimeoutMs = 15_000L
private const val BackgroundSetupTimeoutMs = 30_000L

/** How long the MTU answer may take before the services are looked up without it, as in [GattLink]. */
private const val MtuAnswerMs = 2_000L
private const val Tag = "Link"

/** Retries as for an NMEA receiver: 1 s, 2 s, 4 s, then every 5 s. */
private const val FirstRetryMs = 1_000L
private const val MaxRetryMs = 5_000L

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
 * How far a [GattNotifyLink] setup got (FBBD-279): the reason given when it brings no data names
 * the step it was waiting for, so a drive's log tells a device that never answered from one that
 * connected and sent nothing.
 */
internal enum class NotifyStep(private val waitingFor: String) {
    CONNECT("no answer to the connection"),
    MTU("no MTU answer"),
    SERVICES("services not found"),
    NOTIFICATIONS("notifications not confirmed"),
    FIRST_VALUE("notifications on, no value sent"),
    ;

    /** The reconnect reason after [elapsedMs] without data; it starts as before FBBD-279. */
    fun noDataReason(elapsedMs: Long): String = "no data after connecting: $waitingFor in $elapsedMs ms"
}

/**
 * Keeps a BLE link to one device that notifies a standard characteristic (Location and Speed,
 * Heart Rate Measurement, ...): connect, larger MTU, subscribe, and reconnect on its own after a
 * drop, a GATT error, no data within 15 s of connecting (30 s on a background connection), or
 * [GattNotifySpec.silenceTimeoutMs] without data, until [stop]. The same life cycle as
 * [RaceBoxLink], without the RaceBox's diagnostics probe. Each setup step is logged (FBBD-279);
 * setups that bring no data change how the next one connects ([SetupLadder]: without the MTU
 * request, then Android's background connection every other attempt), and an MTU request without
 * an answer in 2 s is skipped. No bonding is asked for; a device that needs it for the
 * notification descriptor makes Android pair on the descriptor write. Every GATT call runs on one
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
    private val policy = ReconnectPolicy(FirstRetryMs, MaxRetryMs)
    private val ladder = SetupLadder(SetupTimeoutMs, BackgroundSetupTimeoutMs)
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    // Touched only on [thread], except the counters written by the binder thread.
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var setupStartedAt = 0L
    private var plan = ladder.plan()
    private var step = NotifyStep.CONNECT
    private var awaitingMtu = false

    @Volatile
    private var lastDataAt = 0L

    private val received = AtomicInteger()

    override fun start() {
        handler.post {
            if (running) return@post
            running = true
            connect()
            handler.postDelayed(::watchdog, WatchdogMs)
        }
    }

    /** Disconnects and ends the link's thread. The link cannot be started again. */
    override fun stop() {
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
        plan = ladder.plan()
        step = NotifyStep.CONNECT
        val failure = try {
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                "bluetooth off"
            } else {
                val device = adapter.getRemoteDevice(address)
                DebugLog.i(Tag, "$address connecting ($plan, ${bondName(device)}, setup ${ladder.failures})")
                gatt = device.connectGatt(appContext, plan.autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
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
        val failed = gatt
        if (failed != null && lastDataAt == 0L) {
            ladder.failed()
            // Android may hold a stale copy of the device's services; the next connection finds them again.
            if (!refreshCache(failed)) DebugLog.i(Tag, "$address service cache could not be cleared")
        }
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
            last != 0L && now - last > spec.silenceTimeoutMs -> retry("no data for ${now - last} ms")
            last == 0L && now - setupStartedAt > plan.setupTimeoutMs -> retry(step.noDataReason(now - setupStartedAt))
            last != 0L -> {
                policy.reset()
                ladder.streaming()
                setState(LinkState.Streaming(count))
            }
        }
        handler.postDelayed(::watchdog, WatchdogMs)
    }

    private fun closeGatt() {
        handler.removeCallbacks(mtuOverdue)
        awaitingMtu = false
        val g = gatt ?: return
        gatt = null
        try {
            g.disconnect()
            g.close()
        } catch (_: SecurityException) {
            // Nothing more to release.
        }
    }

    private fun elapsed() = SystemClock.elapsedRealtime() - setupStartedAt

    /** Looks up the device's services, the step after the MTU exchange (or instead of it). */
    private fun discover(g: BluetoothGatt) {
        step = NotifyStep.SERVICES
        if (!g.discoverServices()) retry("discoverServices failed")
    }

    private val mtuOverdue = Runnable {
        val g = gatt
        if (g != null && awaitingMtu) {
            awaitingMtu = false
            DebugLog.i(Tag, "$address no MTU answer in $MtuAnswerMs ms, finding services without it")
            discover(g)
        }
    }

    private fun setState(s: LinkState) {
        if (_state.value == s) return
        _state.value = s
        onState(s)
    }

    private fun subscribe(g: BluetoothGatt) {
        val service = g.getService(spec.service)
        val ch = service?.getCharacteristic(spec.characteristic)
        val cccd = ch?.getDescriptor(RaceBoxGatt.CCCD)
        if (service == null) {
            val found = g.services.joinToString { it.uuid.toString() }
            DebugLog.i(Tag, "$address services without ${spec.service}: [$found]")
        }
        step = NotifyStep.NOTIFICATIONS
        val failure = when {
            service == null -> "service ${spec.service} not found"
            ch == null -> "characteristic ${spec.characteristic} not found"
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
        val now = SystemClock.elapsedRealtime()
        // Written on the binder thread; the link's thread only reads it, so a rare double log is harmless.
        if (lastDataAt == 0L) DebugLog.i(Tag, "$address first value, ${now - setupStartedAt} ms after connecting began")
        lastDataAt = now
        received.incrementAndGet()
        onData(value)
    }

    /** Callbacks arrive on a binder thread; anything that touches [gatt] is posted to [thread]. */
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    DebugLog.i(Tag, "$address connected in ${elapsed()} ms ($plan)")
                    // A Location and Speed value with every field is 28 bytes, more than the default
                    // MTU carries; without a larger one the device splits it over notifications.
                    // Skipped when it held setups up: a split value is lost, the link is not.
                    if (plan.askMtu && g.requestMtu(RequestedMtu)) {
                        step = NotifyStep.MTU
                        awaitingMtu = true
                        handler.postDelayed(mtuOverdue, MtuAnswerMs)
                    } else {
                        discover(g)
                    }
                } else {
                    retry("connection state $newState, status $status")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (g != gatt) return@post
                DebugLog.i(Tag, "$address MTU $mtu, status $status, ${elapsed()} ms after connecting began")
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
                DebugLog.i(Tag, "$address services found, status $status, ${elapsed()} ms after connecting began")
                if (status == BluetoothGatt.GATT_SUCCESS) subscribe(g) else retry("service discovery status $status")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (g != gatt) return@post
                DebugLog.i(Tag, "$address notification switch-on, status $status, ${elapsed()} ms after connecting began")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    step = NotifyStep.FIRST_VALUE
                } else {
                    // 5 or 15: the device wants pairing first; the bond state says whether it happened.
                    retry("enable notifications status $status, ${bondName(g.device)}")
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == spec.characteristic) data(value)
        }

        @Deprecated("Called instead of the overload above before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ch.uuid == spec.characteristic) {
                ch.value?.let { data(it.copyOf()) }
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
