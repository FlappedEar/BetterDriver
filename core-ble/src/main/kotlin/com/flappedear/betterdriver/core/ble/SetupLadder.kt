package com.flappedear.betterdriver.core.ble

/**
 * How one connection attempt is made (FBBD-272): a direct connection or Android's background one
 * ([autoConnect]), with or without asking for a larger MTU before finding the services, and how
 * long it may take to bring data before it is given up.
 */
data class ConnectPlan(val autoConnect: Boolean, val askMtu: Boolean, val setupTimeoutMs: Long) {
    override fun toString(): String =
        (if (autoConnect) "background connection" else "direct connection") +
            (if (askMtu) ", MTU asked for" else ", no MTU request")
}

/**
 * Changes how a link connects after attempts that never brought data (FBBD-272). On 20 Oct 2026 a
 * RaceBox Micro was reached 9 times in 97 s, each time without an answer to the MTU request, and
 * Android dropped it about 6 s later with status 147 (GATT_CONNECTION_TIMEOUT); other sessions
 * that day lost the first two attempts the same way. Identical retries can repeat the stall, so each
 * failed setup moves one step: the same once more (the link also clears Android's cached
 * services), then without the MTU request, then Android's background connection, which has no
 * connection timeout of its own, every other attempt. Once data flows, the next drop starts again
 * from the step that worked.
 */
class SetupLadder(
    private val directTimeoutMs: Long = 10_000L,
    private val backgroundTimeoutMs: Long = 30_000L,
) {
    /** Setups in a row that ended before any data, counted from the step that last worked. */
    var failures = 0
        private set

    fun plan(): ConnectPlan = when {
        failures < NoMtuFrom -> ConnectPlan(autoConnect = false, askMtu = true, setupTimeoutMs = directTimeoutMs)
        failures % 2 == 0 -> ConnectPlan(autoConnect = false, askMtu = false, setupTimeoutMs = directTimeoutMs)
        else -> ConnectPlan(autoConnect = true, askMtu = false, setupTimeoutMs = backgroundTimeoutMs)
    }

    /** The attempt made with [plan] ended before any data. */
    fun failed() {
        failures++
    }

    /** Data flows on the attempt made with [plan]: a later drop starts from this step. */
    fun streaming() {
        if (failures < NoMtuFrom) failures = 0
    }

    private companion object {
        const val NoMtuFrom = 2
    }
}

/**
 * A second service lookup on the same connection when a lookup succeeds without the profile's
 * service (FBBD-272). On 21 Oct 2026 a RaceBox Micro twice answered with status 0 but without its
 * UART service (6e400001); closing the link then cost a new connection and MTU wait each time. So
 * the link clears Android's cached services, waits [delayMs] and looks again, once per connection,
 * with [windowMs] more for that lookup before the watchdog gives the setup up.
 */
class ServiceRecheck(val delayMs: Long = 500L, private val windowMs: Long = 6_000L) {
    private var used = false

    /** A new connection may look again once. */
    fun newConnection() {
        used = false
    }

    /**
     * The setup deadline for a second look at [nowMs], given the current [deadlineMs]; null when
     * this connection already looked twice, and the link should reconnect as before.
     */
    fun recheck(nowMs: Long, deadlineMs: Long): Long? {
        if (used) return null
        used = true
        return maxOf(deadlineMs, nowMs + delayMs + windowMs)
    }
}
