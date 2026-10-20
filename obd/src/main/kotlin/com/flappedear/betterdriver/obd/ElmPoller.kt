package com.flappedear.betterdriver.obd

import java.util.Locale

/** What an [ElmPoller] reports. Called on the poller's thread. */
interface ObdListener {
    /**
     * The adapter is set up and the car answered: [channels] are what will be polled. [voltage] is
     * the adapter's ATRV answer (the car's battery at the OBD port), null when it gave none.
     */
    fun ready(adapter: String, protocol: String, voltage: String?, supported: Set<Int>, channels: List<PidChannel>)

    /** [channel] read [value] at [atMs] on the poller's clock. */
    fun value(channel: PidChannel, value: Double, atMs: Long)

    /** A line for the app log: init answers, rates, why polling stopped. */
    fun log(text: String)
}

/** The poller's monotonic clock in ms, and how it waits; passed in for tests. */
interface ObdClock {
    fun nowMs(): Long

    fun sleep(ms: Long)
}

/**
 * Talks to one connected ELM327 (FBBD-15): sets it up, finds the mode 01 PIDs the car has, then
 * polls them with the [PollScheduler] until [running] turns false or the car or adapter stops
 * answering. One [run] is one connection; the caller reconnects and runs it again.
 *
 * Setup: `AT Z` (reset), then echo, line feeds, spaces and headers off, adaptive timing, automatic
 * protocol. A clone that rejects one of these (`?`) is still used; only an adapter that answers
 * none of them fails. Requests go out with the default header (the functional broadcast), so only
 * channels with no [PidChannel.header] are polled: the generic mode 01 PIDs, and the profile's own
 * mode 01 channels.
 *
 * [clock] tells the time and waits.
 */
class ElmPoller(
    private val line: ElmLine,
    private val profile: CarProfile,
    private val listener: ObdListener,
    private val running: () -> Boolean,
    private val clock: ObdClock,
    private val rateLogMs: Long = DEFAULT_RATE_LOG_MS,
) {
    /**
     * Sets up the adapter, discovers the car's PIDs and polls until stopped. Returns why it ended,
     * for the log; throws an [java.io.IOException] when the line closes.
     */
    @Suppress("ReturnCount") // Each early return is one way the connection is not usable.
    fun run(): String {
        val (adapter, voltage) = setUp() ?: return "adapter not answering"
        val supported = discover() ?: return "car not answering"
        val protocol = line.command("ATDPN", AT_TIMEOUT_MS)?.trim().orEmpty()
        val channels = profile.channelsToPoll(supported).filter { it.header == null && it.compiled != null }
        listener.ready(adapter, protocol, voltage, supported, channels)
        if (channels.isEmpty()) return "no channels to poll"
        return poll(channels)
    }

    /** The adapter's identity (`ELM327 v1.5`) and voltage, or null when it answered no setup command. */
    private fun setUp(): Pair<String, String?>? {
        val reset = line.command("ATZ", RESET_TIMEOUT_MS)
        var answered = if (reset != null) 1 else 0
        for (cmd in SETUP) {
            val answer = line.command(cmd, AT_TIMEOUT_MS)
            if (answer != null) answered++
            if (answer?.contains('?') == true) listener.log("$cmd not supported")
        }
        if (answered == 0) return null
        val id = line.command("ATI", AT_TIMEOUT_MS)?.trim()
        logIdentity(id)
        val volts = line.command("ATRV", AT_TIMEOUT_MS)?.trim()
        listener.log("adapter ${id ?: reset?.trim().orEmpty()} voltage ${volts ?: "n/a"}")
        val name = id ?: reset?.trim()?.lines()?.lastOrNull { it.isNotBlank() } ?: "ELM327"
        return name to volts?.takeIf { it.isNotEmpty() && '?' !in it }
    }

    /**
     * Logs what the adapter says it is (FBBD-121): ATI (the ELM327 version it claims), AT@1 (its
     * description) and, on an STN chip (OBDLink and some Vgate adapters), STI and STDI (chip and
     * device). A plain ELM327 answers `?` to the ST commands, which is logged too, so the first log of
     * a new adapter shows which chip it has.
     */
    private fun logIdentity(ati: String?) {
        val answers = listOf("ATI" to ati) + IDENTITY.map { it to line.command(it, AT_TIMEOUT_MS)?.trim() }
        val text = answers.joinToString(" ") { (cmd, answer) ->
            "$cmd=" + (answer?.replace(Regex("\\s+"), " ")?.ifEmpty { "(empty)" } ?: "(no answer)")
        }
        listener.log("identity $text")
    }

    /** The mode 01 PIDs the car has, from the `01 00`, `01 20`, … bitmasks; null when none answered. */
    private fun discover(): Set<Int>? {
        val supported = sortedSetOf<Int>()
        var base: Int? = SupportedPids.bitmaskPids.first()
        while (base != null) {
            val cmd = "01" + hex(base, 2)
            // The first request also finds the protocol ("SEARCHING..."), which can take seconds.
            val text = line.command(cmd, if (supported.isEmpty()) SEARCH_TIMEOUT_MS else REQUEST_TIMEOUT_MS)
            val response = text?.let { ElmParser.parse(it, headers = false, command = cmd) }
            if (response !is ElmResponse.Messages) {
                if (supported.isEmpty()) listener.log("$cmd: ${text?.trim()?.replace('\r', ' ') ?: "no answer"}")
                break
            }
            val found = SupportedPids.fromMessages(response.messages)
            supported += found
            base = SupportedPids.nextBitmask(base, found)
        }
        return supported.takeIf { it.isNotEmpty() }
    }

    private fun poll(channels: List<PidChannel>): String {
        val scheduler = PollScheduler(channels)
        val byRequest = channels.groupBy { it.request }
        var timeouts = 0
        var nextRateLog = clock.nowMs() + rateLogMs
        var ended: String? = null
        while (ended == null && running()) {
            val now = clock.nowMs()
            val next = scheduler.next(now)
            when {
                next == null -> ended = "car stopped answering"
                next.notBeforeMs > now -> clock.sleep(next.notBeforeMs - now)
                else -> {
                    timeouts = if (ask(next.request, byRequest, scheduler)) 0 else timeouts + 1
                    if (timeouts >= MAX_TIMEOUTS) ended = "adapter stopped answering"
                    val done = clock.nowMs()
                    if (done >= nextRateLog) {
                        nextRateLog = done + rateLogMs
                        logRates(scheduler, channels, done)
                    }
                }
            }
        }
        return ended ?: "stopped"
    }

    /** Sends [request] and reports its channels' values; false when no prompt came back in time. */
    private fun ask(request: Request, byRequest: Map<Request, List<PidChannel>>, scheduler: PollScheduler): Boolean {
        val cmd = hex(request.mode, 2) + hex(request.pid, 2 * request.pidLength)
        val sent = clock.nowMs()
        val text = line.command(cmd, REQUEST_TIMEOUT_MS)
        val done = clock.nowMs()
        val response = text?.let { ElmParser.parse(it, headers = false, command = cmd) }
        val messages = (response as? ElmResponse.Messages)?.messages.orEmpty()
        // The answer is stamped halfway through the request: the ECU read it some time in between.
        val at = sent + (done - sent) / 2
        var answered = false
        for (channel in byRequest[request].orEmpty()) {
            val value = channel.decode(messages)?.takeIf { it.isFinite() } ?: continue
            answered = true
            listener.value(channel, value, at)
        }
        scheduler.completed(request, sent, done - sent, answered)
        return text != null
    }

    private fun logRates(scheduler: PollScheduler, channels: List<PidChannel>, nowMs: Long) {
        val rates = channels.joinToString(" ") {
            "${it.id}=${"%.1f".format(Locale.ROOT, scheduler.achievedHz(it, nowMs))}"
        }
        val latency = scheduler.latencyMs?.let { "%.0f".format(Locale.ROOT, it) } ?: "n/a"
        listener.log("rates_hz $rates latency_ms=$latency")
    }

    companion object {
        /** Echo, line feeds, spaces and headers off; adaptive timing; automatic protocol. */
        val SETUP = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")

        /** Asked after ATI, for the log only: description, STN chip and STN device. */
        val IDENTITY = listOf("AT@1", "STI", "STDI")

        const val RESET_TIMEOUT_MS = 3_000L
        const val AT_TIMEOUT_MS = 1_500L
        const val SEARCH_TIMEOUT_MS = 12_000L
        const val REQUEST_TIMEOUT_MS = 2_000L

        /** Requests in a row with no prompt before the adapter is taken as gone. */
        const val MAX_TIMEOUTS = 5

        const val DEFAULT_RATE_LOG_MS = 60_000L
    }
}
