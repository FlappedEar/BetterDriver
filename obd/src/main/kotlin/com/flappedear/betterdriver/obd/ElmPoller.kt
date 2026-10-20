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
 * Faster polling on CAN (FBBD-270). Each request used to cost the ELM327's wait for more ECUs to
 * answer, about 200 of the 230 ms per request in Arek's Corolla log of 2026-10-20 (Wi-Fi clone), so:
 * - [multiPid]: up to six mode 01 PIDs go in one request ([MultiPid]). Polling switches headers on
 *   (`AT H1`) so that answers of several frames from several ECUs can be told apart. Checked once
 *   with the first group; when the car answers only one of its PIDs, every PID is asked on its own.
 * - [responseCount]: once a request has had a full answer, it is sent with how many answers to wait
 *   for (`010C0D11494A042`), so the adapter returns as soon as they are in instead of waiting out
 *   its timeout. Whether an adapter counts messages or CAN frames is learned: messages first, and
 *   when that cuts an answer short, frames from then on. An adapter that rejects it (`?`) is asked
 *   without it from then on; a request that then gets no value is learned again without it.
 *
 * [clock] tells the time and waits.
 */
@Suppress("TooManyFunctions", "LongParameterList") // The two FBBD-270 switches are for tests.
class ElmPoller(
    private val line: ElmLine,
    private val profile: CarProfile,
    private val listener: ObdListener,
    private val running: () -> Boolean,
    private val clock: ObdClock,
    private val rateLogMs: Long = DEFAULT_RATE_LOG_MS,
    private val multiPid: Boolean = true,
    private val responseCount: Boolean = true,
) {
    /** What each group's request got without a count, by its lead request. */
    private class Learned(val messages: Int, val frames: Int, val values: Int)

    private val expected = HashMap<Request, Learned>()

    /** Whether the adapter takes a response count; off on non-CAN protocols and after a `?`. */
    private var countOn = false

    /** Whether the adapter's response count counts CAN frames rather than ECU messages. */
    private var countsFrames = false

    /** Whether polling answers carry CAN ids (`AT H1`). */
    private var headers = false

    private val lastValues = LinkedHashMap<String, Double>()
    private var requests = 0
    private var requestMsTotal = 0L

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
        val can = isCan(protocol)
        countOn = responseCount && can
        val groups = groupsFor(channels, combine = multiPid && can)
        listener.log(
            "polling " + groups.joinToString(" ") { g -> g.joinToString("+") { it.id } } +
                " multi_pid=${if (groups.any { g -> g.distinctBy { it.pid }.size > 1 }) "on" else "off"}" +
                " response_count=${if (countOn) "on" else "off"}",
        )
        return poll(channels, groups)
    }

    /**
     * The requests to poll: [MultiPid] groups when [combine] and the first multi-PID group gets more
     * than one of its PIDs back, with headers on or, when the adapter's headers-on answer cannot be
     * read, off; else one PID per request.
     */
    @Suppress("ReturnCount") // Each early return is one way multi-PID requests are not used.
    private fun groupsFor(channels: List<PidChannel>, combine: Boolean): List<List<PidChannel>> {
        val singles = MultiPid.groups(channels, combine = false)
        if (!combine) return singles
        val groups = MultiPid.groups(channels)
        val probe = groups.firstOrNull { g -> g.distinctBy { it.pid }.size > 1 } ?: return singles
        val cmd = MultiPid.command(probe)
        var answer = ""
        for (withHeaders in listOf(true, false)) {
            if (!setHeaders(withHeaders)) continue
            val text = line.command(cmd, REQUEST_TIMEOUT_MS)
            val messages = messagesOf(text, cmd)
            val got = decode(probe, messages)
            if (got.keys.distinctBy { it.pid }.size > 1) {
                expected[probe.first().request] = Learned(messages.size, frameCount(text), got.size)
                return groups
            }
            answer = text?.trim()?.replace('\r', ' ') ?: "nothing"
        }
        listener.log("multi_pid off: $cmd answered $answer")
        return singles
    }

    /** `AT H1` or `AT H0`; true when the adapter took it. */
    private fun setHeaders(on: Boolean): Boolean {
        val ok = line.command(if (on) "ATH1" else "ATH0", AT_TIMEOUT_MS)?.contains("OK") == true
        headers = on && ok
        return ok || !on
    }

    /** The ECU messages in [text], the answer to [cmd], read with the current [headers] setting. */
    private fun messagesOf(text: String?, cmd: String): List<EcuMessage> {
        val response = text?.let { ElmParser.parse(it, headers = headers, command = cmd) }
        return (response as? ElmResponse.Messages)?.messages.orEmpty()
    }

    /** CAN frames in an answer: its data lines, less the length line of a headers-off multi-frame answer. */
    private fun frameCount(text: String?): Int = text.orEmpty().split('\r', '\n').map { it.trim().uppercase() }.count {
        DATA_LINE.matches(it) && !(!headers && it.length == LENGTH_LINE)
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

    private fun poll(channels: List<PidChannel>, groups: List<List<PidChannel>>): String {
        val scheduler = PollScheduler(channels, groups = groups)
        val byRequest = groups.associateBy { it.first().request }
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

    /** Sends [request]'s group and reports its channels' values; false when no prompt came back in time. */
    private fun ask(request: Request, byRequest: Map<Request, List<PidChannel>>, scheduler: PollScheduler): Boolean {
        val group = byRequest[request].orEmpty()
        val base = MultiPid.command(group)
        val learned = expected[request]
        val count = learned?.let { if (countsFrames) it.frames else it.messages }
            ?.takeIf { countOn && it in 1..MAX_COUNT }
        val cmd = if (count != null) base + hex(count, 1) else base
        val sent = clock.nowMs()
        var text = line.command(cmd, REQUEST_TIMEOUT_MS)
        if (count != null && text?.trim() == "?") {
            countOn = false
            listener.log("response_count off: $cmd answered ?")
            text = line.command(base, REQUEST_TIMEOUT_MS)
        }
        val done = clock.nowMs()
        val messages = messagesOf(text, cmd)
        // The answer is stamped halfway through the request: the ECU read it some time in between.
        val at = sent + (done - sent) / 2
        val values = decode(group, messages)
        for ((channel, value) in values) {
            lastValues[channel.id] = value
            listener.value(channel, value, at)
        }
        learn(request, learned, count, text, messages.size, values.size)
        requests++
        requestMsTotal += done - sent
        val answered = values.keys.mapTo(HashSet()) { it.request }
        scheduler.completed(request, sent, done - sent, answered.isNotEmpty(), answered)
        return text != null
    }

    /**
     * Updates what [request] is expected to get after an answer [text] of [messages] messages and
     * [values] values, sent with [count] (null: none) while [learned] was known.
     */
    private fun learn(request: Request, learned: Learned?, count: Int?, text: String?, messages: Int, values: Int) {
        when {
            // A full answer: from now on the adapter is told how many answers to wait for.
            learned == null -> if (messages > 0) expected[request] = Learned(messages, frameCount(text), values)
            count == null || values >= learned.values -> Unit
            // Cut short by a count of messages where the adapter counts frames.
            !countsFrames && learned.frames > learned.messages -> {
                countsFrames = true
                listener.log("response_count counts frames: answer ${text?.trim()?.replace('\r', ' ')}")
            }
            // An ECU that answered last time did not this time: learn again without a count.
            else -> expected.remove(request)
        }
    }

    /** The value of each channel of [group] in [messages], first ECU first. */
    private fun decode(group: List<PidChannel>, messages: List<EcuMessage>): Map<PidChannel, Double> {
        val multi = group.distinctBy { it.pid }.size > 1
        val parts = if (multi) messages.map { MultiPid.split(it.bytes) } else emptyList()
        val out = LinkedHashMap<PidChannel, Double>()
        for (channel in group) {
            val value = if (multi) {
                parts.firstNotNullOfOrNull { p -> p[channel.pid]?.let { channel.decode(it) } }
            } else {
                channel.decode(messages)
            }
            value?.takeIf { it.isFinite() }?.let { out[channel] = it }
        }
        return out
    }

    private fun logRates(scheduler: PollScheduler, channels: List<PidChannel>, nowMs: Long) {
        val rates = channels.joinToString(" ") {
            "${it.id}=${"%.1f".format(Locale.ROOT, scheduler.achievedHz(it, nowMs))}"
        }
        val latency = scheduler.latencyMs?.let { "%.0f".format(Locale.ROOT, it) } ?: "n/a"
        val perSecond = if (requestMsTotal > 0) requests * MS_PER_S / requestMsTotal else 0.0
        listener.log(
            "rates_hz $rates latency_ms=$latency requests_per_s=${"%.1f".format(Locale.ROOT, perSecond)}" +
                " response_count=${if (countOn) "on" else "off"}",
        )
        requests = 0
        requestMsTotal = 0
        // The latest value of every channel, so slow ones (oil, coolant) can be read from the log.
        if (lastValues.isNotEmpty()) {
            val values = lastValues.entries.joinToString(" ") { (id, v) -> "$id=${"%.1f".format(Locale.ROOT, v)}" }
            listener.log("values $values")
        }
    }

    /** ATDPN `6`..`9` (or `A6`.. when found automatically): ISO 15765-4 CAN, where FBBD-270 applies. */
    private fun isCan(protocol: String): Boolean =
        protocol.trim().removePrefix("A").let { it.length == 1 && it[0] in '6'..'9' }

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

        /** A headers-off multi-frame answer starts with its length in three hex digits (`00E`). */
        private const val LENGTH_LINE = 3

        /** One frame as printed: hex, after a frame number (`1:`) with headers off. */
        private val DATA_LINE = Regex("""([0-9A-F]:)?[0-9A-F ]+""")

        /** The ELM327 response count is one hex digit. */
        private const val MAX_COUNT = 0xF
        private const val MS_PER_S = 1000.0
    }
}
