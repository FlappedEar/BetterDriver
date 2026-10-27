package com.flappedear.betterdriver.obd

import com.flappedear.betterdriver.format.fixed

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
 *   every [RESAMPLE_EVERY] counted requests one goes without, so a late ECU is still seen. A request
 *   with a fast channel does so only every [RESAMPLE_EVERY_FAST] (FBBD-284): each one waits out the
 *   adapter's timeout, a gap in engine speed (the Vgate's longest fast gap on 2026-10-21 12:52 was
 *   0.23-0.39 s at 0.115 s a reading).
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
 * CAN first on the FR-S (FBBD-320, Arek 2026-10-23: "Integrujemy CAN skoro jest szybszy niż obd"). Its
 * bus carries steering, brake, pedal, throttle, rpm, oil and coolant ([CanBroadcasts.Frs]) at 20 to
 * 100 a second, faster than any request. A channel the bus gives is not polled; what is left (ATF
 * temperature through its own header 7E1, intake air) is slow, so the adapter listens all the time
 * and stops only when a request is due, at most [CAN_SLOW_MAX_HZ] each. A listen takes every frame id
 * at once behind STN pass filters (`STFAP`, then `STM`) when the adapter has them, as the vLinker's
 * STN1151 does; a plain ELM327 listens to one id at a time (`AT CRA`) in turn. Frames are stamped as
 * they arrive. When the bus goes quiet the polled channels come back.
 *
 * Only on a car whose broadcasts are wanted (FBBD-284): the FR-S / 86 / BRZ of the first generation,
 * told by [PollSet.broadcastCar] (the session's car) or by its VIN (`09 02`, read once at connect). On any
 * other car (Arek's Corolla: "w Corolli mnie to nie interesuje") there is no probe and no listen, so
 * every moment of the adapter goes to the requests.
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
    private val maxListenMs: Long = DEFAULT_MAX_LISTEN_MS,
) {
    /** What each group's request got without a count, by its lead request. */
    private class Learned(private val resampleEvery: Int) {
        /** The most messages, frames and values any answer without a count got. */
        var messages = 0
        var frames = 0
        var values = 0

        /** Answers without a count so far, and counted requests since the last one. */
        var samples = 0
        var counted = 0

        /** Whether the count can be used: learned from enough answers, and not due a fresh one. */
        val ready: Boolean get() = samples >= LEARN_SAMPLES && counted < resampleEvery

        fun sample(messages: Int, frames: Int, values: Int) {
            this.messages = maxOf(this.messages, messages)
            this.frames = maxOf(this.frames, frames)
            this.values = maxOf(this.values, values)
            samples++
            counted = 0
        }
    }

    private val expected = HashMap<Request, Learned>()

    private fun learnedFor(fast: Boolean) = Learned(if (fast) RESAMPLE_EVERY_FAST else RESAMPLE_EVERY)

    /** Whether the adapter takes a response count; off on non-CAN protocols and after a `?`. */
    private var countOn = false

    /** Whether the adapter's response count counts CAN frames rather than ECU messages. */
    private var countsFrames = false

    /** Whether polling answers carry CAN ids (`AT H1`). */
    private var headers = false

    private val lastValues = LinkedHashMap<String, Double>()
    private var requests = 0
    private var rateStartMs = 0L
    private var nextRateLog = 0L
    private var nextSetCheck = 0L

    /** The broadcast signals listened for, by frame id, and frames of each id since the last rates line. */
    private var listens: List<CanSignal> = emptyList()
    private var byFrame: Map<Int, List<CanSignal>> = emptyMap()
    private val listenFrames = LinkedHashMap<Int, Int>()
    private var listenMs = 0L
    private var emptyListens = 0

    /** Listens in a row without a frame, by frame id, and the ids heard in the current listen (FBBD-320). */
    private val silentListens = HashMap<Int, Int>()
    private val heardNow = HashSet<Int>()
    private var turn = 0

    /** Listening stopped: the channels it gave are polled again (FBBD-320). */
    private var listensLost = false

    /** Whether the adapter takes STN pass filters for several ids at once; null before the first try. */
    private var stnFilters: Boolean? = null

    /** The header requests go to now; null is the adapter's default (FBBD-320). */
    private var currentHeader: Int? = null

    /** FBBD-284: the [ST_STEPS] step in use (-1: the adapter's own), requests left in its trial, done. */
    private var stStep = -1
    private var stLeft = 0
    private var stWarmup = 0
    private var stDone = false

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
        setListens(broadcastsOn(protocol))
        val available = availableOn(protocol, supported)
        val can = isCan(protocol)
        resetTimeout(can)
        var set = pollSet()
        var first = true
        while (true) {
            val channels = toPoll(available, set)
            val all = channels + listens.map { it.channel }
            if (first) listener.ready(adapter, protocol, voltage, supported, all) else listener.polling(all)
            first = false
            if (channels.isEmpty() && listens.isEmpty()) return "no channels to poll"
            countOn = responseCount && can
            expected.clear()
            // The multi-PID check goes to every ECU, whatever header the last loop's requests used.
            headerFor(null)
            val groups = groupsFor(channels, combine = multiPid && can)
            listener.log(
                "polling " + groups.joinToString(" ") { g -> g.joinToString("+") { it.id } } +
                    " multi_pid=${if (groups.any { g -> g.distinctBy { it.pid }.size > 1 }) "on" else "off"}" +
                    " response_count=${if (countOn) "on" else "off"}",
            )
            val ended = poll(channels, groups, set)
            if (ended != null) return ended
            if (listensLost) {
                listensLost = false
                listener.log("polling again what CAN gave")
            } else {
                listener.log("poll set changed")
            }
            set = pollSet()
        }
    }

    /**
     * The channels the car has that can be polled: its profile's, and on an FR-S whose broadcasts are
     * heard the FR-S profile's own requests too (ATF through 7E1, FBBD-320). A request to a header of
     * its own needs 11-bit CAN.
     */
    private fun availableOn(protocol: String, supported: Set<Int>): List<PidChannel> {
        val frs = profile == CarProfile.Generic && listens.any { it in CanBroadcasts.Frs }
        val car = if (frs) CarProfiles.ScionFrs2013 else profile
        val can11 = isCan11(protocol)
        return car.channelsToPoll(supported).filter { (it.header == null || can11) && it.compiled != null }
    }

    /** What [set] polls of [available]: not what it leaves out, nor what the bus gives (logged). */
    private fun toPoll(available: List<PidChannel>, set: PollSet): List<PidChannel> {
        val covered = listens.mapTo(HashSet()) { it.id }
        val chosen = set.select(available)
        val channels = chosen.filter { it.id !in covered }
        val skipped = available - chosen.toSet()
        if (skipped.isNotEmpty()) listener.log("not polled (not used): " + skipped.joinToString(" ") { it.id })
        val fromCan = chosen - channels.toSet()
        if (fromCan.isNotEmpty()) listener.log("not polled (from CAN): " + fromCan.joinToString(" ") { it.id })
        return channels
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
                    val fast = probe.any { it.pollClass == PollClass.FAST }
                    expected.getOrPut(probe.first().request) { learnedFor(fast) }
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

    /**
     * Points requests at [header] (`AT SH`, FBBD-320: the FR-S's ATF is asked of 7E1), or back at the
     * functional broadcast for null; false when the adapter refused. Only on 11-bit CAN.
     */
    private fun headerFor(header: Int?): Boolean {
        if (header == currentHeader) return true
        val cmd = "ATSH" + hex(header ?: FUNCTIONAL_11, CAN_ID_DIGITS)
        val ok = line.command(cmd, AT_TIMEOUT_MS)?.contains("OK") == true
        if (ok) currentHeader = header else listener.log("$cmd not taken")
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
    @Suppress("ReturnCount") // Each early return is one reason not to listen.
    private fun broadcastsOn(protocol: String): List<CanSignal> {
        if (!canListen || !isCan11(protocol)) return emptyList()
        if (!pollSet().broadcastCar) {
            val vin = readVin()
            val wanted = vin != null && CanBroadcasts.wantedFor(vin)
            listener.log("can_listen ${if (wanted) "on" else "off"}: vin ${vin?.take(VIN_LOGGED) ?: "not read"}")
            if (!wanted) return emptyList()
        }
        return probe()
    }

    /** The car's VIN from `09 02`, or null when no ECU gives one (FBBD-284). */
    private fun readVin(): String? {
        val text = line.command("0902", REQUEST_TIMEOUT_MS)
        val response = text?.let { ElmParser.parse(it, headers = headers, command = "0902") }
        return (response as? ElmResponse.Messages)?.messages.orEmpty().firstNotNullOfOrNull { Vin.of(it.bytes) }
    }

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
            filterStuck = true
            stopListening("receive filter not restored (AT CRA and AT AR refused)")
        }
        return restored
    }

    private fun setListens(signals: List<CanSignal>) {
        listens = signals
        byFrame = signals.groupBy { it.canId }
    }

    /** Listening stops for good on this connection; the poll loop then polls what it gave (FBBD-320). */
    private fun stopListening(why: String) {
        listener.log("can_listen off: $why")
        if (listens.isNotEmpty()) listensLost = true
        setListens(emptyList())
    }

    /**
     * One listen of about [windowMs] (FBBD-263, FBBD-320): every listened id at once behind STN pass
     * filters, else one id in turn behind `AT CRA` for at most [ROTATE_WINDOW_MS]. Headers go on so the
     * ids can be told apart, and CAN formatting off; both go back after. Listening stops after
     * [MAX_EMPTY_LISTENS] listens without a frame.
     */
    private fun listenRound(windowMs: Long) {
        val start = clock.nowMs()
        val window = windowMs.coerceAtMost(maxListenMs).coerceAtLeast(listenWindowMs)
        line.command("ATCAF0", AT_TIMEOUT_MS)
        val hadHeaders = headers
        val ids = byFrame.keys.toList()
        var frames = 0
        var listened = emptyList<Int>()
        heardNow.clear()
        if (headers || setHeaders(true)) {
            if (ids.size > 1 && setStnFilters(ids)) {
                frames = monitorStn(window)
                if (stnFilters == true) listened = ids
            } else {
                val id = ids[turn++ % ids.size]
                val text = listenText(id, minOf(window, ROTATE_WINDOW_MS))
                if (text != null) frames = report(listOf(text))
                listened = listOf(id)
            }
        }
        line.command("ATCAF1", AT_TIMEOUT_MS)
        if (!hadHeaders && headers) setHeaders(false)
        listenMs += clock.nowMs() - start
        emptyListens = if (frames == 0) emptyListens + 1 else 0
        if (emptyListens >= MAX_EMPTY_LISTENS && listens.isNotEmpty()) {
            stopListening("no frames in $MAX_EMPTY_LISTENS listens")
        } else {
            dropSilent(listened)
        }
    }

    /**
     * One frame id that stopped while the others go on (Codex on #328): after [MAX_EMPTY_LISTENS] of its
     * listens without a frame its signals are dropped, so the channels it gave are polled again.
     */
    private fun dropSilent(listened: List<Int>) {
        for (id in listened) silentListens[id] = if (id in heardNow) 0 else (silentListens[id] ?: 0) + 1
        val silent = listened.filter { (silentListens[it] ?: 0) >= MAX_EMPTY_LISTENS }
        if (silent.isEmpty()) return
        val gone = listens.filter { it.canId in silent }
        listener.log(
            "can_listen dropped ${silent.joinToString(" ") { hex(it, CAN_ID_DIGITS) }}: no frames in " +
                "$MAX_EMPTY_LISTENS listens (" + gone.joinToString(" ") { it.id } + ")",
        )
        silent.forEach { silentListens.remove(it) }
        setListens(listens - gone.toSet())
        listensLost = true
    }

    /**
     * Sets one STN pass filter for each of [ids] (`STFCP`, then `STFAP <id>,7FF`); false when the adapter
     * is not an STN one or refused, and from then on for this connection.
     */
    @Suppress("ReturnCount") // Each early return is one way the adapter has no STN filters.
    private fun setStnFilters(ids: List<Int>): Boolean {
        if (stnFilters == false) return false
        if (line.command("STFCP", AT_TIMEOUT_MS)?.contains("OK") != true) return noStn("STFCP not taken")
        for (id in ids) {
            if (line.command("STFAP ${hex(id, CAN_ID_DIGITS)},7FF", AT_TIMEOUT_MS)?.contains("OK") != true) {
                line.command("STFCP", AT_TIMEOUT_MS)
                return noStn("STFAP not taken")
            }
        }
        return true
    }

    private fun noStn(why: String): Boolean {
        if (stnFilters != false) listener.log("can_listen one id at a time: $why")
        stnFilters = false
        return false
    }

    /** `STM` behind the pass filters for [windowMs], then the filters cleared; the frames heard. */
    private fun monitorStn(windowMs: Long): Int {
        var frames = 0
        var refused = false
        line.monitorLines("STM", windowMs, MONITOR_STOP_MS) { lines ->
            if (lines.any { it.trim() == "?" }) refused = true
            frames += report(lines)
        }
        line.command("STFCP", AT_TIMEOUT_MS)
        if (refused) {
            noStn("STM answered ?")
        } else if (stnFilters == null) {
            stnFilters = true
            listener.log("can_listen all ids at once: STFAP + STM")
        }
        return frames
    }

    /** What the adapter printed in [windowMs] of `AT MA` behind `AT CRA` [canId]; null when it would not filter. */
    @Suppress("ReturnCount") // Each early return is a filter the adapter would not set or drop.
    private fun listenText(canId: Int, windowMs: Long): String? {
        val filter = line.command("ATCRA" + hex(canId, CAN_ID_DIGITS), AT_TIMEOUT_MS)
        if (filter == null || '?' in filter) return null
        val lines = ArrayList<String>()
        line.monitorLines("ATMA", windowMs, MONITOR_STOP_MS) { lines += it }
        if (!restoreFilter()) return null
        return lines.joinToString("\r")
    }

    /**
     * Reports the values in the frames of [lines], which have just arrived: the frames of one id in a
     * batch are stamped back from now at its period (the adapter sends them in bursts). The frames read.
     */
    private fun report(lines: List<String>): Int {
        val at = clock.nowMs()
        val heard = CanMonitor.frames(lines.joinToString("\r")).filter { it.first in byFrame }
        for ((id, frames) in heard.groupBy { it.first }) {
            val signals = byFrame.getValue(id)
            val period = signals.minOf { it.periodMs }
            frames.forEachIndexed { i, (_, data) ->
                val t = at - (frames.size - 1 - i) * period
                for (signal in signals) {
                    val v = signal.decode(data) ?: continue
                    lastValues[signal.id] = v
                    listener.value(signal.channel, v, t)
                }
            }
            listenFrames[id] = (listenFrames[id] ?: 0) + frames.size
            heardNow += id
        }
        return heard.size
    }

    /** The mode 01 PIDs the car has, from the `01 00`, `01 20`, … bitmasks; null when none answered. */
    private fun discover(): Set<Int>? {
        val supported = LinkedHashSet<Int>()
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
        return supported.takeIf { it.isNotEmpty() }?.sorted()?.toSet()
    }

    /** Polls [groups] until stopped (why, for the log) or until [pollSet] is no longer [set] (null). */
    private fun poll(channels: List<PidChannel>, groups: List<List<PidChannel>>, set: PollSet): String? {
        val canFirst = listens.isNotEmpty() && channels.none { it.pollClass == PollClass.FAST }
        val scheduler = PollScheduler(
            channels,
            fastWeight = set.fastWeight,
            slowMaxHz = slowMaxHz(set),
            groups = groups,
        )
        val byRequest = groups.associateBy { it.first().request }
        var timeouts = 0
        rateStartMs = clock.nowMs()
        nextRateLog = rateStartMs + rateLogMs
        nextSetCheck = rateStartMs + POLL_SET_CHECK_MS
        var nextListen = rateStartMs
        var ended: String? = null
        while (ended == null && running()) {
            val now = clock.nowMs()
            if (setChanged(now, set) || listensLost) return null
            val next = scheduler.next(now)
            val listenFor = listenWindow(canFirst, now, next, nextListen)
            when {
                listenFor != null -> {
                    listenRound(listenFor)
                    nextListen = clock.nowMs() + listenIntervalMs
                }
                next == null -> ended = "car stopped answering"
                // Only slow requests left and none due: wait, but not past the next listen.
                next.notBeforeMs > now -> clock.sleep(wakeAt(next.notBeforeMs, nextListen) - now)
                else -> {
                    timeouts = if (ask(next.request, byRequest, scheduler)) 0 else timeouts + 1
                    if (timeouts >= MAX_TIMEOUTS) ended = "adapter stopped answering"
                }
            }
            logRatesIfDue(scheduler, channels)
        }
        return ended ?: "stopped"
    }

    private fun logRatesIfDue(scheduler: PollScheduler, channels: List<PidChannel>) {
        val now = clock.nowMs()
        if (now < nextRateLog) return
        nextRateLog = now + rateLogMs
        logRates(scheduler, channels, now)
    }

    /** How often a slow channel is asked at most: less often while the bus gives the rest (FBBD-320). */
    private fun slowMaxHz(set: PollSet): Double =
        if (listens.isEmpty()) set.slowMaxHz else minOf(set.slowMaxHz, CAN_SLOW_MAX_HZ)

    /** Whether [pollSet] is no longer [set], asked every [POLL_SET_CHECK_MS]. */
    private fun setChanged(now: Long, set: PollSet): Boolean {
        if (now < nextSetCheck) return false
        nextSetCheck = now + POLL_SET_CHECK_MS
        return pollSet() != set
    }

    /**
     * How long to listen now, or null to poll (FBBD-320): CAN first, until the next request is due;
     * else for [listenWindowMs] every [listenIntervalMs], and whenever nothing is left to poll.
     */
    private fun listenWindow(canFirst: Boolean, now: Long, next: PollScheduler.Next?, nextListen: Long): Long? {
        val requestDue = next != null && next.notBeforeMs <= now
        return when {
            listens.isEmpty() -> null
            canFirst -> if (requestDue) null else (next?.notBeforeMs ?: (now + maxListenMs)) - now
            listenDue(now, nextListen) || next == null -> listenWindowMs
            else -> null
        }
    }

    /** Sends [request]'s group and reports its channels' values; false when no prompt came back in time. */
    private fun ask(request: Request, byRequest: Map<Request, List<PidChannel>>, scheduler: PollScheduler): Boolean {
        val group = byRequest[request].orEmpty()
        val base = MultiPid.command(group)
        if (!headerFor(group.firstOrNull()?.header)) {
            scheduler.completed(request, clock.nowMs(), 0, answered = false)
            return true
        }
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
        learn(request, learned, count, text, messages.size, values.size, group.any { it.pollClass == PollClass.FAST })
        requests++
        val answered = values.keys.mapTo(HashSet()) { it.request }
        scheduler.completed(request, sent, done - sent, answered.isNotEmpty(), answered)
        if (text != null) tuneTimeout(learned?.values, values.size, scheduler.latencyMs)
        return text != null
    }

    /**
     * Shortens the adapter's wait for more answers (`AT ST`, FBBD-284) when requests stay slow: an
     * adapter that takes no response count, like Arek's Wi-Fi clone (latency_ms 226-241 with the count
     * "on"), waits out its timeout, about 200 ms, after the one ECU has answered. Each of [ST_STEPS] is
     * tried for [ST_TRIAL] requests; when one of them gets fewer values than the request got before
     * ([want], null when not known yet), the step before is put back and kept. [latencyMs] is the
     * scheduler's smoothed latency; nothing is tried while it is under [ST_TUNE_ABOVE_MS].
     */
    /** A new connection starts at the adapter's own timeout; only CAN answers are timed by [ST_STEPS]. */
    private fun resetTimeout(can: Boolean) {
        stStep = -1
        stLeft = 0
        stWarmup = 0
        stDone = !can
    }

    @Suppress("ReturnCount") // Each early return is one state of the tuning.
    private fun tuneTimeout(want: Int?, got: Int, latencyMs: Double?) {
        if (stDone) return
        if (stLeft > 0) {
            if (want != null && got < want) {
                val back = ST_STEPS.getOrNull(stStep - 1) ?: ST_DEFAULT
                line.command("ATST" + hex(back, 2), AT_TIMEOUT_MS)
                listener.log("timeout ATST${hex(ST_STEPS[stStep], 2)} lost an answer: back to ATST${hex(back, 2)}")
                stDone = true
                return
            }
            if (--stLeft == 0) listener.log("timeout ATST${hex(ST_STEPS[stStep], 2)} kept")
            return
        }
        if (stStep < 0) {
            val slow = latencyMs != null && latencyMs >= ST_TUNE_ABOVE_MS
            if (!slow || ++stWarmup < ST_TRIAL) return
        }
        val next = stStep + 1
        if (next >= ST_STEPS.size) {
            stDone = true
            return
        }
        if (line.command("ATST" + hex(ST_STEPS[next], 2), AT_TIMEOUT_MS)?.contains("OK") != true) {
            listener.log("timeout: ATST${hex(ST_STEPS[next], 2)} not taken")
            stDone = true
            return
        }
        stStep = next
        stLeft = ST_TRIAL
    }

    /**
     * Updates what [request] is expected to get after an answer [text] of [messages] messages and
     * [values] values, sent with [count] (null: none) while [learned] was known.
     */
    @Suppress("LongParameterList")
    private fun learn(
        request: Request,
        learned: Learned?,
        count: Int?,
        text: String?,
        messages: Int,
        values: Int,
        fast: Boolean,
    ) {
        if (count == null || learned == null) {
            // Without a count every ECU had its time to answer: the most seen over a few answers,
            // and again now and then, is what the count waits for, so a slower ECU is not cut off.
            if (messages > 0) expected.getOrPut(request) { learnedFor(fast) }.sample(messages, frameCount(text), values)
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
            "${it.id}=${fixed(scheduler.achievedHz(it, nowMs), 1)}"
        }
        val latency = scheduler.latencyMs?.let { fixed(it, 0) } ?: "n/a"
        // Over the whole interval, idle waits for slow channels included.
        val elapsed = nowMs - rateStartMs
        val perSecond = if (elapsed > 0) requests * MS_PER_S / elapsed else 0.0
        listener.log(
            "rates_hz $rates latency_ms=$latency requests_per_s=${fixed(perSecond, 1)}" +
                " response_count=${if (countOn) "on" else "off"}",
        )
        if (listenFrames.isNotEmpty() && elapsed > 0) {
            val can = listenFrames.entries.joinToString(" ") { (id, n) ->
                "${hex(id, CAN_ID_DIGITS)}=${fixed(n * MS_PER_S / elapsed, 1)}"
            }
            val mode = if (stnFilters == true) "stn" else "one_id"
            listener.log(
                "can_hz $can listen_share=${fixed(listenMs.toDouble() / elapsed, 2)} mode=$mode",
            )
            listenFrames.clear()
            listenMs = 0
        }
        requests = 0
        rateStartMs = nowMs
        // The latest value of every channel, so slow ones (oil, coolant) can be read from the log.
        if (lastValues.isNotEmpty()) {
            val values = lastValues.entries.joinToString(" ") { (id, v) -> "$id=${fixed(v, 1)}" }
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

        /**
         * FBBD-320, CAN first: the longest listen before the poll set and stop are looked at again, the
         * longest one id is listened to in turn on a plain ELM327, and how often each polled channel is
         * asked at most (ATF and intake air change over minutes).
         */
        const val DEFAULT_MAX_LISTEN_MS = 5_000L
        private const val ROTATE_WINDOW_MS = 250L
        const val CAN_SLOW_MAX_HZ = 0.2

        /** The functional broadcast header on 11-bit CAN, which requests go to by default. */
        private const val FUNCTIONAL_11 = 0x7DF

        /** How long a stopped monitor may take to print its prompt. */
        private const val MONITOR_STOP_MS = 1_000L
        private const val MAX_EMPTY_LISTENS = 10

        /**
         * FBBD-284: `AT ST` values tried in turn, in units of 4.096 ms: about 102, 78 and 61 ms, each
         * still above the 50 ms an ECU has to answer a mode 01 request (SAE J1979 P2). 0x32 (205 ms)
         * is the ELM327's default, put back when the first step loses an answer.
         */
        val ST_STEPS = listOf(0x19, 0x13, 0x0F)
        private const val ST_DEFAULT = 0x32
        private const val ST_TRIAL = 20
        private const val ST_TUNE_ABOVE_MS = 120.0

        /** Characters of the VIN in the log: the maker and model, not the car's serial. */
        private const val VIN_LOGGED = 8

        /** An 11-bit CAN id in hex, as `AT CRA` takes it and the log shows it. */
        private const val CAN_ID_DIGITS = 3

        /** A headers-off multi-frame answer starts with its length in three hex digits (`00E`). */
        private const val LENGTH_LINE = 3

        /** One frame as printed: hex, after a frame number (`1:`) with headers off. */
        private val DATA_LINE = Regex("""([0-9A-F]:)?[0-9A-F ]+""")

        /** Answers without a count before one is sent, and counted requests between such answers. */
        private const val LEARN_SAMPLES = 3
        private const val RESAMPLE_EVERY = 50
        private const val RESAMPLE_EVERY_FAST = 1_000

        /** The ELM327 response count is one hex digit. */
        private const val MAX_COUNT = 0xF
        private const val MS_PER_S = 1000.0
    }
}
