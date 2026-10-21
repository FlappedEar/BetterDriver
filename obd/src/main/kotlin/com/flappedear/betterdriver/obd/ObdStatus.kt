package com.flappedear.betterdriver.obd

import java.util.Locale

/** Where an OBD-II connection is, for the OBD page (FBBD-261, UI-104). */
enum class ObdLinkState {
    /** No adapter answered yet; [ObdStatus.detail] says why. */
    Searching,

    /** The adapter accepted the connection and is being set up, or the car is being asked. */
    Connecting,

    /** The car answers; [ObdStatus.readings] are its values. */
    Connected,
}

/** The newest value of one channel and how often it arrives. */
data class ObdReading(val id: String, val name: String, val unit: String, val value: Double, val hz: Double)

/** One look at an OBD-II connection: its state, a line about it, and every value the car sends. */
data class ObdStatus(val state: ObdLinkState, val detail: String, val readings: List<ObdReading> = emptyList())

/**
 * Builds [ObdStatus] from what a connection reports (FBBD-261). Fed from the reader's threads and
 * read from another, so every call is synchronized. Rates are arrivals over the last [windowMs].
 */
class ObdStatusTracker(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private val lock = Any()
    private var state = ObdLinkState.Searching
    private var detail = "Looking for the adapter"
    private var adapterName: String? = null
    private var channels: List<PidChannel> = emptyList()
    private var since = 0L
    private val latest = HashMap<String, Double>()
    private val arrivals = HashMap<String, ArrayDeque<Long>>()

    /** No adapter yet, with why: no Wi-Fi network, or none answered. */
    fun searching(text: String) = synchronized(lock) {
        state = ObdLinkState.Searching
        detail = text
        clear()
    }

    /** The adapter [name] accepted the connection; the page names it while it is connected (UI-106 A). */
    fun connecting(name: String) = synchronized(lock) {
        state = ObdLinkState.Connecting
        detail = "$name · setting up"
        adapterName = name
        clear()
    }

    /** The car answered; [channels] are polled from [nowMs]. */
    fun ready(adapter: String, protocol: String, voltage: String?, channels: List<PidChannel>, nowMs: Long) =
        synchronized(lock) {
            state = ObdLinkState.Connected
            detail = listOfNotNull(adapterName, adapter, protocolName(protocol), voltage).joinToString(" · ")
            this.channels = channels
            since = nowMs
        }

    /** Polling switched to [channels] on the same connection (FBBD-283). */
    fun polling(channels: List<PidChannel>) = synchronized(lock) {
        this.channels = channels
    }

    fun value(channel: PidChannel, value: Double, atMs: Long) = synchronized(lock) {
        latest[channel.id] = value
        // Evicted here too: with no one reading snapshots (a background drive) the history stays bounded.
        arrivals.getOrPut(channel.id) { ArrayDeque() }.apply {
            addLast(atMs)
            evict(atMs)
        }
    }

    /** The connection ended with [reason]; the next attempt follows. */
    fun ended(reason: String) = synchronized(lock) {
        state = ObdLinkState.Searching
        detail = "Connection ended: $reason"
        clear()
    }

    fun snapshot(nowMs: Long): ObdStatus = synchronized(lock) {
        val span = (nowMs - since).coerceIn(1L, windowMs)
        val readings = channels.mapNotNull { c ->
            val v = latest[c.id] ?: return@mapNotNull null
            val times = arrivals[c.id] ?: ArrayDeque()
            times.evict(nowMs)
            ObdReading(c.id, c.name, c.unit, v, times.size * MsPerSecond / span.toDouble())
        }
        ObdStatus(state, detail, readings)
    }

    private fun ArrayDeque<Long>.evict(nowMs: Long) {
        while (isNotEmpty() && first() < nowMs - windowMs) removeFirst()
    }

    private fun clear() {
        if (state == ObdLinkState.Searching) adapterName = null
        channels = emptyList()
        latest.clear()
        arrivals.clear()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 5_000L
        private const val MsPerSecond = 1_000.0

        /** The ELM327's ATDPN answer as a protocol name; "A6" is automatic, protocol 6. */
        fun protocolName(code: String): String? {
            val n = code.trim().uppercase(Locale.ROOT).removePrefix("A")
            return Protocols[n] ?: code.trim().takeIf { it.isNotEmpty() }?.let { "protocol $it" }
        }

        private val Protocols = mapOf(
            "1" to "SAE J1850 PWM",
            "2" to "SAE J1850 VPW",
            "3" to "ISO 9141-2",
            "4" to "ISO 14230-4 KWP (5 baud)",
            "5" to "ISO 14230-4 KWP (fast)",
            "6" to "ISO 15765-4 CAN (11 bit, 500 kbaud)",
            "7" to "ISO 15765-4 CAN (29 bit, 500 kbaud)",
            "8" to "ISO 15765-4 CAN (11 bit, 250 kbaud)",
            "9" to "ISO 15765-4 CAN (29 bit, 250 kbaud)",
            "A" to "SAE J1939 CAN",
        )
    }
}

/** A reading's value as the page shows it: whole numbers at 100 and above, else one decimal. */
fun ObdReading.valueText(): String {
    val number = if (kotlin.math.abs(value) >= WholeFrom) {
        String.format(Locale.ROOT, "%.0f", value)
    } else {
        String.format(Locale.ROOT, "%.1f", value)
    }
    return if (unit.isEmpty()) number else "$number $unit"
}

private const val WholeFrom = 100.0
