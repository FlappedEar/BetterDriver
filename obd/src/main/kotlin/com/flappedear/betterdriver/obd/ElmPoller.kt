package com.flappedear.betterdriver.obd

import java.util.Locale

/** What an [ElmPoller] reports. Called on the poller's thread. */
interface ObdListener {
    /**
     * The adapter is set up and the car answered: [channels] are what will be polled. [voltage] is
     * the adapter's ATRV answer (the car's battery at the OBD port), null when it gave none.
     */
    fun ready(adapter: String, protocol: String, voltage: String?, supported: Set<Int>, channels: List<PidChannel>)

    /**
     * Polling switched to [channels] on the same connection, because the poll set changed (a tile
     * chosen during the session, FBBD-283). [channels] may hold channels [ready] did not.
     */
    fun polling(channels: List<PidChannel>) {}

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
 *   with the first group; an adapter that refuses it (`?`) is tried with three, then two PIDs a
 *   request; when the car answers only one of its PIDs, every PID is asked on its own.
 * - [responseCount]: once a request has had [LEARN_SAMPLES] full answers, it is sent with the most
 *   answers they got (`010C0D11494A042`), so the adapter returns as soon as they are in instead of waiting out
 *   its timeout. Whether an adapter counts messages or CAN frames is learned: messages first, and
 *   when that cuts an answer short, frames from then on. An adapter that rejects it (`?`) is asked
 *   without it from then on; a request that then gets no value is learned again without it, and
 *   every [RESAMPLE_EVERY] counted requests one goes without, so a late ECU is still seen.
 *
 * Only the channels in [pollSet] are polled (FBBD-283); the others the car has are logged as skipped.
 * It is asked again every [POLL_SET_CHECK_MS], and when it changed (a tile chosen during the session)
 * polling switches to the new set on the same connection.
 *
 * Listening to the car's own CAN frames (FBBD-263). Some values are not asked for but broadcast by
 * the car, steering angle among them ([CanBroadcasts]). On 11-bit CAN, right after discovery, a
 * probe monitors the bus (`AT MA`) for [probeMs] and logs which ids the OBD port shows and how often,
 * then counts the ids [CanBroadcasts] needs with a filter on each (`AT CRA`). The signals whose ids
 * came are recorded like polled channels: every [listenIntervalMs] polling pauses for one listen,
 * `AT CRA <id>`, `AT CAF0` (frames as sent, not read as ISO-TP), `AT MA` for [listenWindowMs], then
 * `AT CAF1` and `AT CRA` back. The adapter does one thing at a time, so a listen takes the place of
 * a few requests; continuous monitoring would stop polling altogether (FBBD-206).
 *
 * [clock] tells the time and waits.
 */
@Suppress("TooManyFunctions", "LongParameterList") // The FBBD-270 and FBBD-263 switches are for tests.
class ElmPoller(
    private val line: ElmLine,
    private val profile: CarProfile,
    private val listener: ObdListener,
    private val running: () -> Boolean,
    private val clock: ObdClock,
    private val rateLogMs: Long = DEFAULT_RATE_LOG_MS,
    private val multiPid: Boolean = true,
    private val responseCount: Boolean = true,
    private val pollSet: () -> PollSet = { PollSet.All },
    private val canListen: Boolean = true,
    private val probeMs: Long = DEFAULT_PROBE_MS,
    private val listenIntervalMs: Long = DEFAULT_LISTEN_INTERVAL_MS,
    private val listenWindowMs: Long = DEFAULT_LISTEN_WINDOW_MS,
) {
    /** What each group's request got without a count, by its lead request. */
    private class Learned {
        /** The most messages, frames and values any answer without a count got. */
        var messages = 0
        var frames = 0
        var values = 0

        /** Answers without a count so far, and counted requests since the last one. */
        var samples = 0
        var counted = 0

        /** Whether the count can be used: learned from enough answers, and not due a fresh one. */
        val ready: Boolean get() = samples >= LEARN_SAMPLES && counted < RESAMPLE_EVERY

        fun sample(messages: Int, frames: Int, values: Int) {
            this.messages = maxOf(this.messages, messages)
            this.frames = maxOf(this.frames, frames)
            this.values = maxOf(this.values, values)
            samples++
            counted = 0
        }
    }

    private val expected = HashMap<Request, Learned>()

    /** Whether the adapter takes a response count; off on non-CAN protocols and after a `?`. */
    private var countOn = false

    /** Whether the adapter's response count counts CAN frames rather than ECU messages. */
    private var countsFrames = false

    /** Whether polling answers carry CAN ids (`AT H1`). */
    private var headers = false

    private val lastValues = LinkedHashMap<String, Double>()
    private var requests = 0
    private var rateStartMs = 0L

    /** The broadcast signals listened for, and frames each got since the last rates line. */
    private var listens: List<CanSignal> = emptyList()
    private val listenFrames = HashMap<String, Int>()
    private var listenMs = 0L
    private var emptyListens = 0

    /** The adapter took a receive filter but would not drop it: no more listening on it. */
    private var filterStuck = false

    /**
     * Sets up the adapter, discovers the car's PIDs and polls until stopped. Returns why it ended,
     * for the log; throws an [java.io.IOException] when the line closes.
     */
    @Suppress("ReturnCount") // Each early return is one way the connection is not usable.
    fun run(): String {
        val (adapter, voltage) = setUp() ?: return "adapter not answering"
        val supported = discover() ?: return "car not answering"
        val protocol = line.command("ATDPN", AT_TIMEOUT_MS)?.trim().orEmpty()
        val available = profile.channelsToPoll(supported).filter { it.header == null && it.compiled != null }
        listens = broadcastsOn(protocol)
        val can = isCan(protocol)
        var set = pollSet()
        var first = true
        while (true) {
            val channels = set.select(available)
            val skipped = available - channels.toSet()
            if (skipped.isNotEmpty()) listener.log("not polled (not used): " + skipped.joinToString(" ") { it.id })
            val all = channels + listens.map { it.channel }
            if (first) listener.ready(adapter, protocol, voltage, supported, all) else listener.polling(all)
            first = false
            if (channels.isEmpty()) return "no channels to poll"
            countOn = responseCount && can
            expected.clear()
            val groups = groupsFor(channels, combine = multiPid && can)
            listener.log(
                "polling " + groups.joinToString(" ") { g -> g.joinToString("+") { it.id } } +
                    " multi_pid=${if (groups.any { g -> g.distinctBy { it.pid }.size > 1 }) "on" else "off"}" +
                    " response_count=${if (countOn) "on" else "off"}",
            )
            val ended = poll(channels, groups, set)
            if (ended != null) return ended
            set = pollSet()
            listener.log("poll set changed")
        }
    }

    /**
     * The requests to poll: [MultiPid] groups when [combine] and the multi-PID group with the most
     * PIDs gets more than one of them back, with headers on or, when the adapter's headers-on answer
     * cannot be read, off; else one PID per request. An adapter that answers `?` to the request itself
     * refuses its length, not the car (Arek's Wi-Fi ELM327 v1.5 clone, log of 2026-10-21, answered `?` to
     * `010C0D11494A04`), so smaller groups ([GROUP_SIZES]) are tried before single PIDs. An ECU
     * that answered only one PID would not answer more with fewer, so that goes straight to singles.
     */
    @Suppress("ReturnCount") // Each early return is one way multi-PID requests are not used.
    private fun groupsFor(channels: List<PidChannel>, combine: Boolean): List<List<PidChannel>> {
        val singles = MultiPid.groups(channels, combine = false)
        if (!combine) return singles
        for ((i, size) in GROUP_SIZES.withIndex()) {
            val groups = MultiPid.groups(channels, maxPids = size)
            // The longest request is the one an adapter may refuse: with only rpm and a pedal fast
            // (FBBD-283) the first group has two PIDs and the temperatures' group four.
            val probe = groups.filter { g -> g.distinctBy { it.pid }.size > 1 }
                .maxByOrNull { g -> g.distinctBy { it.pid }.size } ?: return singles
            val cmd = MultiPid.command(probe)
            var answer = ""
            var refused = true
            for (withHeaders in listOf(true, false)) {
                if (!setHeaders(withHeaders)) continue
                val text = line.command(cmd, REQUEST_TIMEOUT_MS)
                val messages = messagesOf(text, cmd)
                val got = decode(probe, messages)
                if (got.keys.distinctBy { it.pid }.size > 1) {
                    expected.getOrPut(probe.first().request) { Learned() }
                        .sample(messages.size, frameCount(text), got.size)
                    return groups
                }
                answer = text?.trim()?.replace('\r', ' ') ?: "nothing"
                if (!refusal(text, cmd)) refused = false
            }
            val smaller = GROUP_SIZES.getOrNull(i + 1)
            if (!refused || answer.isEmpty() || smaller == null) {
                listener.log("multi_pid off: $cmd answered ${answer.ifEmpty { "nothing" }}")
                return singles
            }
            listener.log("multi_pid $cmd answered ?, trying $smaller PIDs a request")
        }
        return singles
    }

    /** Whether [text] is the adapter's `?` to [cmd], with or without the command echoed before it. */
    private fun refusal(text: String?, cmd: String): Boolean =
        text.orEmpty().split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() && it != cmd } == listOf("?")

    /**
     * `AT H1` or `AT H0`; true when the adapter took it. [headers] follows only an acknowledged
     * change, so the parser keeps reading what the adapter prints.
     */
    private fun setHeaders(on: Boolean): Boolean {
        val ok = line.command(if (on) "ATH1" else "ATH0", AT_TIMEOUT_MS)?.contains("OK") == true
        if (ok) headers = on
        return ok
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

    /** The broadcasts to listen for: probed on 11-bit CAN when [canListen], else none (FBBD-263). */
    private fun broadcastsOn(protocol: String): List<CanSignal> =
        if (canListen && isCan11(protocol)) probe() else emptyList()

    /** Whether a listen is due at [nowMs], the next one being at [nextListenMs]. */
    private fun listenDue(nowMs: Long, nextListenMs: Long): Boolean = listens.isNotEmpty() && nowMs >= nextListenMs

    /** When a wait for a request due at [requestMs] ends: no later than the next listen. */
    private fun wakeAt(requestMs: Long, nextListenMs: Long): Long =
        if (listens.isEmpty()) requestMs else minOf(requestMs, nextListenMs)

    /**
     * Which CAN ids the OBD port shows (FBBD-263), for the log, and the [CanBroadcasts] signals whose
     * required ids came. Headers on and CAN formatting off for the monitor; both back afterwards.
     */
    @Suppress("ReturnCount") // Each early return is one way the adapter cannot monitor.
    private fun probe(): List<CanSignal> {
        if (line.command("ATCAF0", AT_TIMEOUT_MS)?.contains("OK") != true) {
            listener.log("can_probe off: ATCAF0 not taken")
            return emptyList()
        }
        if (!setHeaders(true)) {
            line.command("ATCAF1", AT_TIMEOUT_MS)
            listener.log("can_probe off: ATH1 not taken")
            return emptyList()
        }
        val text = line.monitor("ATMA", probeMs, MONITOR_STOP_MS)
        val seen = CanMonitor.count(text)
        val full = if (text?.contains("BUFFER FULL") == true) " buffer_full" else ""
        val top = seen.entries.sortedByDescending { it.value }.take(PROBE_LOG_IDS)
            .joinToString(" ") { (id, n) -> "${hex(id, CAN_ID_DIGITS)}:$n" }
        listener.log("can_probe ms=$probeMs frames=${seen.values.sum()} ids=${seen.size}$full $top".trimEnd())
        // The open monitor can fill the adapter's buffer on a busy bus and miss ids: count the needed
        // ones again, each behind its own filter.
        val needed = CanBroadcasts.all.flatMap { it.requires }.distinct()
        val filtered = LinkedHashMap<Int, Int>()
        for (id in needed) {
            filtered[id] = listenFor(id, PROBE_FILTER_MS).size
            if (filterStuck) break
        }
        line.command("ATCAF1", AT_TIMEOUT_MS)
        setHeaders(false)
        val matched = if (filterStuck) emptyList() else CanBroadcasts.matching(filtered)
        val counts = filtered.entries.joinToString(" ") { (id, n) -> "${hex(id, CAN_ID_DIGITS)}:$n" }
        val listening = matched.joinToString(" ") { "${it.id}=${hex(it.canId, CAN_ID_DIGITS)}" }.ifEmpty { "none" }
        listener.log("can_ids $counts listen $listening")
        return matched
    }

    /**
     * The data of the frames with [canId] that came in [windowMs] of monitoring behind a filter on
     * it; restores the default filters after. Expects CAN formatting off; reads with the current
     * [headers] setting.
     */
    @Suppress("ReturnCount") // Each early return is a filter the adapter would not set or drop.
    private fun listenFor(canId: Int, windowMs: Long): List<ByteArray> {
        val filter = line.command("ATCRA" + hex(canId, CAN_ID_DIGITS), AT_TIMEOUT_MS)
        if (filter == null || '?' in filter) return emptyList()
        val text = line.monitor("ATMA", windowMs, MONITOR_STOP_MS)
        if (!restoreFilter()) return emptyList()
        return if (headers) {
            CanMonitor.frames(text).filter { it.first == canId }.map { it.second }
        } else {
            CanMonitor.dataLines(text)
        }
    }

    /**
     * Puts the default receive filter back after a listen: `AT CRA` without an id, which an ELM327
     * older than v1.4b does not know, else `AT AR` (automatic receive). When neither is taken the
     * filter would hide every answer to a request, so listening is switched off for good.
     */
    private fun restoreFilter(): Boolean {
        val restored = line.command("ATCRA", AT_TIMEOUT_MS)?.contains("OK") == true ||
            line.command("ATAR", AT_TIMEOUT_MS)?.contains("OK") == true
        if (!restored) {
            listener.log("can_listen off: receive filter not restored (AT CRA and AT AR refused)")
            filterStuck = true
            listens = emptyList()
        }
        return restored
    }

    /**
     * One listen for [signal] between polls: reports each frame's value, stamped back from the end of
     * the monitor at the signal's period. Listening stops after [MAX_EMPTY_LISTENS] without a frame.
     */
    private fun listen(signal: CanSignal) {
        val start = clock.nowMs()
        line.command("ATCAF0", AT_TIMEOUT_MS)
        val frames = listenFor(signal.canId, listenWindowMs)
        val end = clock.nowMs()
        line.command("ATCAF1", AT_TIMEOUT_MS)
        listenMs += clock.nowMs() - start
        val values = frames.mapNotNull { signal.decode(it) }
        values.forEachIndexed { i, v ->
            lastValues[signal.id] = v
            listener.value(signal.channel, v, end - (values.size - 1 - i) * signal.periodMs)
        }
        listenFrames[signal.id] = (listenFrames[signal.id] ?: 0) + values.size
        emptyListens = if (values.isEmpty()) emptyListens + 1 else 0
        if (emptyListens >= MAX_EMPTY_LISTENS) {
            listener.log("can_listen off: no ${hex(signal.canId, CAN_ID_DIGITS)} frames in $MAX_EMPTY_LISTENS listens")
            listens = emptyList()
        }
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

    /** Polls [groups] until stopped (why, for the log) or until [pollSet] is no longer [set] (null). */
    private fun poll(channels: List<PidChannel>, groups: List<List<PidChannel>>, set: PollSet): String? {
        val scheduler = PollScheduler(
            channels,
            fastWeight = set.fastWeight,
            slowMaxHz = set.slowMaxHz,
            groups = groups,
        )
        val byRequest = groups.associateBy { it.first().request }
        var timeouts = 0
        rateStartMs = clock.nowMs()
        var nextRateLog = rateStartMs + rateLogMs
        var nextSetCheck = rateStartMs + POLL_SET_CHECK_MS
        var nextListen = rateStartMs
        var turn = 0
        var ended: String? = null
        while (ended == null && running()) {
            val now = clock.nowMs()
            if (now >= nextSetCheck) {
                nextSetCheck = now + POLL_SET_CHECK_MS
                if (pollSet() != set) return null
            }
            if (listenDue(now, nextListen)) {
                listen(listens[turn++ % listens.size])
                nextListen = clock.nowMs() + listenIntervalMs
                continue
            }
            val next = scheduler.next(now)
            when {
                next == null -> ended = "car stopped answering"
                // Only slow requests left and none due: wait, but not past the next listen.
                next.notBeforeMs > now -> clock.sleep(wakeAt(next.notBeforeMs, nextListen) - now)
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
        val count = learned?.takeIf { it.ready }?.let { if (countsFrames) it.frames else it.messages }
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
        val answered = values.keys.mapTo(HashSet()) { it.request }
        scheduler.completed(request, sent, done - sent, answered.isNotEmpty(), answered)
        return text != null
    }

    /**
     * Updates what [request] is expected to get after an answer [text] of [messages] messages and
     * [values] values, sent with [count] (null: none) while [learned] was known.
     */
    private fun learn(request: Request, learned: Learned?, count: Int?, text: String?, messages: Int, values: Int) {
        if (count == null || learned == null) {
            // Without a count every ECU had its time to answer: the most seen over a few answers,
            // and again now and then, is what the count waits for, so a slower ECU is not cut off.
            if (messages > 0) expected.getOrPut(request) { Learned() }.sample(messages, frameCount(text), values)
            return
        }
        learned.counted++
        when {
            values >= learned.values -> Unit
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
        // Over the whole interval, idle waits for slow channels included.
        val elapsed = nowMs - rateStartMs
        val perSecond = if (elapsed > 0) requests * MS_PER_S / elapsed else 0.0
        listener.log(
            "rates_hz $rates latency_ms=$latency requests_per_s=${"%.1f".format(Locale.ROOT, perSecond)}" +
                " response_count=${if (countOn) "on" else "off"}",
        )
        if (listenFrames.isNotEmpty() && elapsed > 0) {
            val can = listenFrames.entries.joinToString(" ") { (id, n) ->
                "$id=${"%.1f".format(Locale.ROOT, n * MS_PER_S / elapsed)}"
            }
            listener.log("can_hz $can listen_share=${"%.2f".format(Locale.ROOT, listenMs.toDouble() / elapsed)}")
            listenFrames.clear()
            listenMs = 0
        }
        requests = 0
        rateStartMs = nowMs
        // The latest value of every channel, so slow ones (oil, coolant) can be read from the log.
        if (lastValues.isNotEmpty()) {
            val values = lastValues.entries.joinToString(" ") { (id, v) -> "$id=${"%.1f".format(Locale.ROOT, v)}" }
            listener.log("values $values")
        }
    }

    /** ATDPN `6` or `8` (or `A6`, `A8`): ISO 15765-4 CAN with 11-bit ids, where FBBD-263 listens. */
    private fun isCan11(protocol: String): Boolean = protocol.trim().removePrefix("A").let { it == "6" || it == "8" }

    /** ATDPN `6`..`9` (or `A6`.. when found automatically): ISO 15765-4 CAN, where FBBD-270 applies. */
    private fun isCan(protocol: String): Boolean =
        protocol.trim().removePrefix("A").let { it.length == 1 && it[0] in '6'..'9' }

    companion object {
        /**
         * PIDs a multi-PID request is tried with, largest first: six fill one CAN frame (J1979's
         * limit), three and two are for adapters that refuse a longer request with `?`.
         */
        val GROUP_SIZES = listOf(MultiPid.MAX_PIDS, 3, 2)

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

        /** How often [pollSet] is asked again during polling. */
        const val POLL_SET_CHECK_MS = 5_000L

        /** FBBD-263: the open probe at connect, and each filtered count after it. */
        const val DEFAULT_PROBE_MS = 2_000L
        private const val PROBE_FILTER_MS = 300L
        private const val PROBE_LOG_IDS = 60

        /** FBBD-263: a listen every 400 ms, 100 ms of frames each (5 steering frames at 50 Hz). */
        const val DEFAULT_LISTEN_INTERVAL_MS = 400L
        const val DEFAULT_LISTEN_WINDOW_MS = 100L

        /** How long a stopped monitor may take to print its prompt. */
        private const val MONITOR_STOP_MS = 1_000L
        private const val MAX_EMPTY_LISTENS = 10

        /** An 11-bit CAN id in hex, as `AT CRA` takes it and the log shows it. */
        private const val CAN_ID_DIGITS = 3

        /** A headers-off multi-frame answer starts with its length in three hex digits (`00E`). */
        private const val LENGTH_LINE = 3

        /** One frame as printed: hex, after a frame number (`1:`) with headers off. */
        private val DATA_LINE = Regex("""([0-9A-F]:)?[0-9A-F ]+""")

        /** Answers without a count before one is sent, and counted requests between such answers. */
        private const val LEARN_SAMPLES = 3
        private const val RESAMPLE_EVERY = 50

        /** The ELM327 response count is one hex digit. */
        private const val MAX_COUNT = 0xF
        private const val MS_PER_S = 1000.0
    }
}
