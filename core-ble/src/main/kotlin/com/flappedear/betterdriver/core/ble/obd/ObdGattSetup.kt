package com.flappedear.betterdriver.core.ble.obd

import com.flappedear.betterdriver.obd.BleSerialConnection

/** BluetoothGatt.GATT_SUCCESS and BluetoothProfile.STATE_CONNECTED, so this file needs no Android class. */
private const val GattSuccess = 0
private const val StateConnected = 2
private const val AttHeaderBytes = 3
private const val NanosPerMs = 1_000_000L

/**
 * The GATT calls one Bluetooth LE OBD setup makes ([ObdGattSetup]); [BleObdTarget] makes them on its
 * BluetoothGatt, the tests on a simulated adapter.
 */
internal interface ObdGattCalls {
    /** Asks for the larger MTU; false when the stack refused. */
    fun requestMtu(): Boolean

    /** Starts the service lookup; false when the stack refused. */
    fun discoverServices(): Boolean

    /** Finds the serial pair and starts turning its notifications on; null when that started, else why not. */
    fun subscribe(): String?

    /** The serial line over the subscribed pair, [payload] bytes a write; null without its write characteristic. */
    fun line(payload: Int): BleSerialConnection?
}

/**
 * Why a setup failed; [quick] when the stack reported it with a status or refused a call (GATT 133,
 * 147, 8, 19, 22, 62, a disconnect during the lookup), which a new connection a moment later usually
 * gets past (FBBD-271). A device without the serial line, a setup that ran out of time or a call that
 * threw is not tried again at once.
 */
internal data class ObdSetupFailure(val reason: String, val quick: Boolean)

/**
 * One connection attempt of a Bluetooth LE ELM327 (FBBD-121, FBBD-271): connected, larger MTU,
 * service lookup, notifications on, then the serial line. GATT callbacks arrive on binder threads and
 * call in here; [await] waits on [BleObdTarget.open]'s thread. Every step happens once per
 * connection, so a late or repeated answer never starts a step twice:
 * - The MTU answer may never come (a RaceBox on 20 and 21 Oct 2026, FBBD-272): after [mtuAnswerMs]
 *   the services are looked up without it, instead of the setup waiting out its 15 s.
 * - An MTU answer that does come after that, a second onMtuChanged (Android 14 exchanges the MTU on
 *   its own too) or a second lookup answer would otherwise start a second lookup or a second
 *   descriptor write, and a second onDescriptorWrite made a second line: notifications then went to
 *   it while the connector read the first, which heard nothing.
 * - Once [abandon] has been called (the setup timed out or the thread was stopped) no line is made.
 * A drop after the line is up ends the line ([BleSerialConnection.lost]), as before.
 */
internal class ObdGattSetup(
    private val calls: ObdGattCalls,
    private val log: (String) -> Unit = {},
    private val mtuAnswerMs: Long = MTU_ANSWER_MS,
) {
    private val lock = Object()

    // All under [lock].
    private var connected = false
    private var awaitingMtu = false
    private var mtuAskedAtNs = 0L
    private var discovering = false
    private var subscribing = false
    private var abandoned = false
    private var mtu = BleSerialConnection.DEFAULT_PAYLOAD + AttHeaderBytes

    @Volatile
    var connection: BleSerialConnection? = null
        private set

    @Volatile
    var failure: ObdSetupFailure? = null
        private set

    /** onConnectionStateChange. */
    fun connectionState(status: Int, newState: Int) {
        if (status == GattSuccess && newState == StateConnected) {
            synchronized(lock) {
                // A second "connected" changes nothing: the MTU is asked for once.
                if (connected || abandoned) return
                connected = true
                awaitingMtu = true
                mtuAskedAtNs = System.nanoTime()
                // [await] may already wait until the 15 s deadline: it must now wake at the MTU's.
                lock.notifyAll()
            }
            if (!calls.requestMtu()) {
                synchronized(lock) { awaitingMtu = false }
                log("MTU request refused, finding services without it")
                discover()
            }
        } else {
            failed("connection state $newState, status $status", quick = true)
        }
    }

    /** onMtuChanged; [ok] when its status is GATT_SUCCESS. */
    fun mtuChanged(mtu: Int, ok: Boolean) {
        val next = synchronized(lock) {
            // Only an answer before the lookup sets the line's payload; a late one keeps the safe default.
            if (ok && connection == null && !discovering) this.mtu = mtu
            awaitingMtu.also { awaitingMtu = false }
        }
        if (next) discover() else log("MTU $mtu answered late or unasked: the setup goes on without it")
    }

    /** onServicesDiscovered. */
    fun servicesDiscovered(status: Int) {
        val first = synchronized(lock) {
            val f = discovering && !subscribing && !abandoned
            if (f) subscribing = true
            f
        }
        if (!first) return
        if (status != GattSuccess) {
            failed("service discovery status $status", quick = true)
            return
        }
        calls.subscribe()?.let { failed(it, quick = false) }
    }

    /** onDescriptorWrite of the serial line's notification descriptor. */
    fun descriptorWritten(status: Int) {
        val payload = synchronized(lock) {
            if (!subscribing || connection != null || abandoned) return
            mtu - AttHeaderBytes
        }
        val made = if (status == GattSuccess) calls.line(payload) else null
        if (made == null) {
            val ok = status == GattSuccess
            failed(if (ok) "write characteristic gone" else "enable notifications status $status", quick = !ok)
            return
        }
        val keep = synchronized(lock) {
            if (abandoned || connection != null) {
                false
            } else {
                connection = made
                lock.notifyAll()
                true
            }
        }
        if (!keep) made.close()
    }

    /**
     * The setup failed with [reason], or the open line dropped. A failure before the line is up ends
     * [await]; [quick] says whether to connect again at once ([ObdSetupFailure]).
     */
    fun failed(reason: String, quick: Boolean = false) {
        val open = synchronized(lock) {
            val c = connection
            if (c == null && failure == null) {
                failure = ObdSetupFailure(reason, quick)
                lock.notifyAll()
            }
            c
        }
        open?.lost(reason)
    }

    /** Nothing more is set up: a line made after this is closed at once. */
    fun abandon() {
        synchronized(lock) {
            abandoned = true
            lock.notifyAll()
        }
    }

    /**
     * Waits until the line is up or the setup failed, at most [timeoutMs]: the line, or null with
     * [failure] saying why (set to a slow failure on time out). Looks the services up without the MTU
     * once its answer is [mtuAnswerMs] late. Throws InterruptedException when the thread is stopped.
     */
    fun await(timeoutMs: Long): BleSerialConnection? {
        val until = System.nanoTime() + timeoutMs * NanosPerMs
        while (true) {
            val overdue = synchronized(lock) {
                val now = System.nanoTime()
                if (connection == null && failure == null && now >= until) {
                    failure = ObdSetupFailure("no serial line within ${timeoutMs / MS_PER_S} s", quick = false)
                }
                if (connection != null || failure != null) return connection
                val mtuDue = mtuAskedAtNs + mtuAnswerMs * NanosPerMs
                if (awaitingMtu && now >= mtuDue) {
                    awaitingMtu = false
                    true
                } else {
                    val wakeAt = if (awaitingMtu) minOf(until, mtuDue) else until
                    lock.wait(((wakeAt - now) / NanosPerMs).coerceAtLeast(1L))
                    false
                }
            }
            if (overdue) {
                log("no MTU answer in $mtuAnswerMs ms, finding services without it")
                discover()
            }
        }
    }

    private fun discover() {
        val first = synchronized(lock) {
            val f = !discovering && !abandoned
            if (f) discovering = true
            f
        }
        if (first && !calls.discoverServices()) failed("discoverServices failed", quick = true)
    }

    companion object {
        /** How long the MTU answer may take before the services are looked up without it (as GattLink, FBBD-272). */
        const val MTU_ANSWER_MS = 2_000L
        private const val MS_PER_S = 1_000L
    }
}

/**
 * Whether and when [BleObdTarget.open] connects again itself after a failed setup (FBBD-271), before
 * the connector's 5 s, 10 s, 20 s back-off: on 28 Oct 2026 18:40 a session started 29 s after the
 * previous Stop got GATT status 133, then 147, and connected only 15 s after Start; fresh starts
 * connect in 4 to 6 s. A status failure (133 is the classic one, often gone on the next connectGatt)
 * is tried again after [DELAYS_MS], while the open has taken less than [BUDGET_MS] all told, so an
 * adapter that is not there still fails as soon as before (a slow failure is never retried).
 */
internal object ObdQuickRetry {
    val DELAYS_MS = listOf(500L, 1_500L)
    const val BUDGET_MS = 15_000L

    /** The wait before the next connection, or null to give up: [retries] made, [elapsedMs] since open began. */
    fun delayMs(failure: ObdSetupFailure, retries: Int, elapsedMs: Long): Long? =
        if (failure.quick && retries < DELAYS_MS.size && elapsedMs < BUDGET_MS) DELAYS_MS[retries] else null
}
