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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val RequestedMtu = 517
private const val WatchdogMs = 1_000L
private const val TicksPerMinute = 60
private const val SilenceTimeoutMs = 3_000L

/** How long the MTU answer may take before the services are looked up without it (FBBD-272). */
private const val MtuAnswerMs = 2_000L

/**
 * How long a service lookup may go unanswered before it is cleared and asked again on the same
 * connection (FBBD-272): on 21 Oct 2026 12:27 a RaceBox's lookup never answered while another BLE
 * link was connecting; good lookups that day answered within 1.2 s of starting.
 */
private const val DiscoveryAnswerMs = 3_000L

/** A fast link is asked for again at most this often while the data says the link is too slow. */
private const val FastLinkEveryMs = 30_000L

/** Connection intervals are counted in units of 1.25 ms; supervision timeouts in units of 10 ms. */
private const val IntervalUnitMs = 1.25
private const val TimeoutUnitMs = 10
private const val Tag = "Link"

/**
 * Keeps a BLE link to one device (FBBD-43; generalised from the RaceBox link for the RaceChrono
 * DIY device, FBBD-118): connect, larger MTU, subscribe to the notifications of [profile], answer
 * its [GattHandshake] if it has one (the Dragy, FBBD-117), and reconnect on its own after a drop, a
 * GATT error, 10 s without data after connecting, or 3 s without data. Attempts that never bring
 * data change how the next one connects ([SetupLadder], FBBD-272), a lookup that misses the
 * profile's service or does not answer in 3 s is repeated once on the same connection
 * ([ServiceRecheck]) unless the connection gave nothing to work with (no services at all, or neither
 * the MTU nor the lookup answered), which starts a new connection at once; each setup step
 * is logged.
 * No bonding is needed. Every
 * GATT call runs on one private thread; [onData] is called with the characteristic and its bytes,
 * on Android's binder thread for notifications and on the link's thread for [read] results, and
 * must return quickly.
 *
 * Bluetooth permissions are checked by the app before it starts a link. If one is missing, the
 * resulting SecurityException is treated like any other failure and retried.
 */
@SuppressLint("MissingPermission")
@Suppress("TooManyFunctions") // One function per link step, one callback per GATT event.
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
    private val ladder = SetupLadder()
    private val recheck = ServiceRecheck()
    private val lateMtu = LateMtu()
    private val setups = BleSetups.Shared
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    // Touched only on [thread], except the counters written by the binder thread.
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var setupStartedAt = 0L

    /** When the watchdog gives up on a setup that brought no data; a second service lookup moves it. */
    private var setupDeadlineAt = 0L
    private var plan = ladder.plan()

    /** The MTU request is out and its answer, which starts the service lookup, has not come. */
    private var awaitingMtu = false

    /** A service lookup is out and has not answered; a late answer to an earlier one is ignored. */
    private var awaitingServices = false

    /** This connection left the MTU request unanswered for [MtuAnswerMs]. */
    private var mtuUnanswered = false

    /** Characteristics still to subscribe to on this connection; empty once all are on. */
    private val toSubscribe = ArrayDeque<BluetoothGattCharacteristic>()

    /** Between reading the handshake challenge and the answer's write being acknowledged. */
    private var handshaking = false
    private var subscribed = false

    @Volatile
    private var lastDataAt = 0L

    private val received = AtomicInteger()

    /**
     * Each minute: notifications as Android handed them over, before any check, and those the
     * connection gate dropped, with the connection's interval and latency then. Tells a message the
     * receiver never sent apart from one lost in the app.
     */
    private val notified = AtomicInteger()
    private val gateDropped = AtomicInteger()

    @Volatile
    private var linkTiming = "not reported"
    private var watchdogTicks = 0

    /** Notifications count only from [gatt]; one from a closed connection is dropped (audit A09). */
    private val connection = CurrentConnection<BluetoothGatt>()
    private val gattProbe = probe?.let(::GattProbe)
    private var lastFastLinkAt = 0L

    fun start() {
        // Counted as connecting at once, before the post: a Bluetooth LE OBD adapter started right
        // after this link must see it and wait ([BleSetups], FBBD-282).
        setups.started(address)
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
            setups.ended(address)
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

    /**
     * Asks Android again for the shortest connection interval and the 2 Mbit/s radio (FBBD-218), at
     * most every 30 s; [reason] goes to the debug log. For a device whose data shows the link carries
     * fewer messages than it sends: the RaceBox then queues them, and drops one when its queue is full.
     */
    fun requestFastLink(reason: String) {
        handler.post {
            val g = gatt ?: return@post
            val now = SystemClock.elapsedRealtime()
            if (!subscribed || now - lastFastLinkAt < FastLinkEveryMs) return@post
            fastLink(g, now, reason)
        }
    }

    /**
     * High connection priority (an interval of 11.25 to 15 ms) and the LE 2M PHY. Asked for when
     * connected and again once streaming, as a request made while the MTU exchange is still running
     * can be dropped, and Android can relax the interval later on.
     */
    private fun fastLink(g: BluetoothGatt, now: Long, reason: String) {
        lastFastLinkAt = now
        val priority = try {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        } catch (_: SecurityException) {
            false
        }
        try {
            g.setPreferredPhy(
                BluetoothDevice.PHY_LE_2M_MASK,
                BluetoothDevice.PHY_LE_2M_MASK,
                BluetoothDevice.PHY_OPTION_NO_PREFERRED,
            )
        } catch (_: SecurityException) {
            // Reported by the next connection attempt.
        }
        val answer = if (priority) "requested" else "refused"
        DebugLog.i(Tag, "$address fast link asked for ($reason): high priority $answer, 2M PHY requested")
    }

    private fun connect() {
        if (!running) return
        closeGatt()
        setState(LinkState.Connecting)
        setupStartedAt = SystemClock.elapsedRealtime()
        lastDataAt = 0L
        gattProbe?.clear()
        plan = ladder.plan()
        setupDeadlineAt = setupStartedAt + plan.setupTimeoutMs
        recheck.newConnection()
        lateMtu.newConnection(plan.askMtu)
        setups.started(address)
        if (ladder.failures > 0) DebugLog.i(Tag, "$address ${ladder.failures} setup(s) without data: $plan")
        val failure = try {
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                "bluetooth off"
            } else {
                gatt = adapter.getRemoteDevice(address)
                    .connectGatt(appContext, plan.autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
                gatt?.let(connection::open)
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

    private fun logMinute() {
        val raw = notified.getAndSet(0)
        val dropped = gateDropped.getAndSet(0)
        DebugLog.i(
            Tag,
            "$address minute: $raw notifications from Android, $dropped dropped by the gate, $linkTiming",
        )
    }

    private fun notification(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
        notified.incrementAndGet()
        if (!connection.deliver(g) { data(uuid, value) }) gateDropped.incrementAndGet()
    }

    /** Once a second: report the rate, and restart a link that went silent or never finished setting up. */
    private fun watchdog() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        val count = received.getAndSet(0)
        val last = lastDataAt
        when {
            gatt == null -> Unit
            last != 0L && now - last > SilenceTimeoutMs -> retry("no data for ${now - last} ms")
            last == 0L && now > setupDeadlineAt -> retry("no data after connecting")
            last != 0L -> {
                policy.reset()
                ladder.streaming()
                setups.ended(address)
                setState(LinkState.Streaming(count))
                if (++watchdogTicks % TicksPerMinute == 0) logMinute()
                gatt?.let { g ->
                    gattProbe?.tick(g, now)
                    if (subscribed && lateMtu.ask(now)) askMtuAgain(g)
                }
            }
        }
        handler.postDelayed(::watchdog, WatchdogMs)
    }

    /**
     * The link came up without the MTU request and streams: asks for the larger MTU now ([LateMtu],
     * FBBD-282), as at the default MTU every RaceBox message takes 5 notifications.
     */
    private fun askMtuAgain(g: BluetoothGatt) {
        val sent = try {
            g.requestMtu(RequestedMtu)
        } catch (_: SecurityException) {
            false
        }
        if (sent) {
            DebugLog.i(Tag, "$address streaming without the MTU request: MTU asked for again")
        } else {
            lateMtu.notSent()
        }
    }

    private fun closeGatt() {
        if (gatt != null && lateMtu.waiting) {
            DebugLog.i(Tag, "$address no answer to the late MTU request: not asked again")
        }
        lateMtu.closed()
        handler.removeCallbacks(mtuOverdue)
        handler.removeCallbacks(lookAgain)
        handler.removeCallbacks(discoveryOverdue)
        awaitingMtu = false
        awaitingServices = false
        mtuUnanswered = false
        toSubscribe.clear()
        handshaking = false
        subscribed = false
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
        if (!g.discoverServices()) {
            retry("discoverServices failed")
            return
        }
        awaitingServices = true
        handler.removeCallbacks(discoveryOverdue)
        handler.postDelayed(discoveryOverdue, DiscoveryAnswerMs)
    }

    /** A lookup that never answers is treated like one that missed the service (FBBD-272). */
    private val discoveryOverdue = Runnable {
        val g = gatt
        if (g != null && awaitingServices) {
            DebugLog.i(Tag, "$address no answer to the service lookup in $DiscoveryAnswerMs ms")
            if (recheck.startOver(servicesFound = null, mtuUnanswered = mtuUnanswered)) {
                retry("no answer to the MTU request or the service lookup")
            } else if (!lookAgainForService(g, foundAny = false)) {
                DebugLog.i(Tag, "$address looked twice on this connection, waiting for the setup timeout")
            }
        }
    }

    private val lookAgain = Runnable { gatt?.let(::discover) }

    /**
     * The lookup succeeded but [profile]'s service is missing, or it never answered ([foundAny] false)
     * (FBBD-272): logs what was found, then clears Android's cached services and looks again on this
     * connection, once per connection; true when it will.
     */
    private fun lookAgainForService(g: BluetoothGatt, foundAny: Boolean = true): Boolean {
        if (foundAny) {
            val found = g.services.joinToString { it.uuid.toString() }
            DebugLog.i(Tag, "$address services without ${profile.service}: [$found]")
        }
        val deadline = recheck.recheck(SystemClock.elapsedRealtime(), setupDeadlineAt) ?: return false
        if (!refreshCache(g)) DebugLog.i(Tag, "$address service cache could not be cleared")
        setupDeadlineAt = deadline
        DebugLog.i(Tag, "$address finding services again in ${recheck.delayMs} ms on the same connection")
        handler.postDelayed(lookAgain, recheck.delayMs)
        return true
    }

    private val mtuOverdue = Runnable {
        val g = gatt
        if (g != null && awaitingMtu) {
            awaitingMtu = false
            mtuUnanswered = true
            DebugLog.i(Tag, "$address no MTU answer in $MtuAnswerMs ms, finding services without it")
            discover(g)
        }
    }

    private fun elapsed() = SystemClock.elapsedRealtime() - setupStartedAt

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
                val now = SystemClock.elapsedRealtime()
                fastLink(g, now, "streaming")
                gattProbe?.start(g, now)
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
                        DebugLog.i(Tag, "$address connected in ${elapsed()} ms ($plan)")
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        // The RaceBox data message is 88 bytes; a larger MTU avoids splitting it.
                        // Reassembly copes either way, so the MTU is skipped when it held setups up.
                        if (plan.askMtu && g.requestMtu(RequestedMtu)) {
                            awaitingMtu = true
                            handler.postDelayed(mtuOverdue, MtuAnswerMs)
                        } else {
                            discover(g)
                        }
                    }
                    else -> retry("connection state $newState, status $status")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (g != gatt) return@post
                DebugLog.i(Tag, "$address MTU $mtu, status $status, ${elapsed()} ms after connecting began")
                if (status == BluetoothGatt.GATT_SUCCESS) gattProbe?.mtu(mtu)
                lateMtu.answered()
                mtuUnanswered = false
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
                // A second answer, to a lookup asked again after the first went quiet, changes nothing.
                if (!awaitingServices) return@post
                awaitingServices = false
                handler.removeCallbacks(discoveryOverdue)
                handler.removeCallbacks(lookAgain)
                val ok = status == BluetoothGatt.GATT_SUCCESS
                if (ok && recheck.startOver(g.services.size, mtuUnanswered)) {
                    retry("the service lookup found no services")
                    return@post
                }
                if (ok && g.getService(profile.service) == null && lookAgainForService(g)) return@post
                val subs = if (ok) {
                    subscriptions(g, profile)
                } else {
                    SubscriptionPlan(failure = "service discovery status $status")
                }
                toSubscribe.clear()
                toSubscribe.addAll(subs.characteristics)
                if (subs.failure != null) retry(subs.failure) else subscribeNext(g)
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
                        fastLink(g, now, "streaming")
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
            if (ch.uuid in profile.notify) notification(g, ch.uuid, value)
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            DebugLog.i(Tag, "$address PHY tx $txPhy rx $rxPhy (2 = 2M), status $status")
        }

        /**
         * Not in the public SDK but called by Android (since 8.0) whenever the connection's interval
         * changes; defining it is enough to receive it. Logged so a drive's log shows whether the
         * interval was relaxed while the data fell behind (FBBD-218).
         */
        @Suppress("unused", "UNUSED_PARAMETER")
        fun onConnectionUpdated(g: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
            val ms = interval * IntervalUnitMs
            linkTiming = "interval ${"%.2f".format(Locale.ROOT, ms)} ms, latency $latency"
            DebugLog.i(
                Tag,
                "$address connection interval ${"%.2f".format(Locale.ROOT, ms)} ms, " +
                    "latency $latency, timeout ${timeout * TimeoutUnitMs} ms, status $status",
            )
        }

        @Deprecated("Called instead of the overload above before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ch.uuid in profile.notify) {
                ch.value?.let { v -> notification(g, ch.uuid, v.copyOf()) }
            }
        }
    }
}

/**
 * Clears Android's cached services of the device on [g] with the hidden BluetoothGatt.refresh(),
 * which has no public equivalent; false when Android refused or does not have it.
 */
internal fun refreshCache(g: BluetoothGatt): Boolean =
    try {
        g.javaClass.getMethod("refresh").invoke(g) as? Boolean ?: false
    } catch (_: ReflectiveOperationException) {
        false
    } catch (_: SecurityException) {
        false
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
