package com.flappedear.betterdriver.canreader

import com.flappedear.betterdriver.obd.CanBroadcasts
import com.flappedear.betterdriver.obd.EcuMessage
import com.flappedear.betterdriver.obd.ElmLine
import com.flappedear.betterdriver.obd.ElmParser
import com.flappedear.betterdriver.obd.ElmResponse
import com.flappedear.betterdriver.obd.PidChannel
import com.flappedear.betterdriver.obd.StandardPids
import com.flappedear.betterdriver.obd.SupportedPids
import com.flappedear.betterdriver.obd.Vin

/**
 * The FR-S reader's steps (FBBD-319), over an ELM327 [line]: what the car gives over OBD-II and
 * what its CAN bus carries at the OBD port, written to [log] for a later analysis thread. Nothing
 * here writes to the car: only reads (modes 01, 03, 07, 09, 0A, 21) and bus monitoring; codes are
 * never cleared. [running] turning false stops the current step at the next request.
 *
 * Unlike the main app, which polls OBD-II and listens to one CAN id in short slots (FBBD-206,
 * FBBD-263), this tool monitors the bus freely: it is a diagnostic tool, run with the car standing
 * or on a short drive, never during a session.
 */
@Suppress("TooManyFunctions") // One function per step of the log.
class FrsReader(
    private val line: ElmLine,
    tap: FrameTap,
    private val log: ReaderLog,
    private val clock: () -> Long,
    private val running: () -> Boolean,
    private val config: ReaderConfig = ReaderConfig(),
) {
    private val can = CanSteps(line, tap, log, running, config)
    private var header: Int? = null

    /** Whether the car talks 11-bit CAN, so its bus can be monitored; set by [setUp]. */
    private var can11 = false

    /** Every check, standing with the engine on: about six minutes. */
    fun checks() {
        if (!setUp()) return
        identity()
        val supported = supportedPids()
        log.step("OBD-II PIDs: ${supported.size} supported, reading each ${config.pidReads} times")
        pidRates(supported)
        multiPid(supported)
        dtcs()
        log.step("FR-S wish list requests")
        config.wishList.forEach { if (running()) readRepeated("diag", it.header, it.command, it) }
        if (can11) {
            val seen = can.survey()
            can.listenEach((config.wishIds + seen.keys).distinct().take(config.maxListenIds))
        } else {
            log.info("not 11-bit CAN: bus monitoring skipped")
        }
        sweep()
        log.step("checks done")
    }

    /**
     * The drive log: the wish list's CAN ids round robin, each for [ReaderConfig.driveListenMs], and
     * its OBD-II requests every [ReaderConfig.driveDiagEveryMs], until [running] turns false. Brake
     * pressure from CAN and from 7B0 21 41 then sit side by side in one log.
     */
    fun drive() {
        if (!setUp()) return
        log.step("drive log: CAN ${config.wishIds.joinToString(" ") { hex3(it) }} + wish list requests")
        var nextDiag = 0L
        var since = clock()
        val counts = HashMap<Int, Int>()
        while (running()) {
            if (can11) listenRound(counts)
            if (clock() >= nextDiag) {
                nextDiag = clock() + config.driveDiagEveryMs
                readWishList()
            }
            if (clock() - since >= RATES_EVERY_MS) {
                val frames = counts.entries.joinToString(" ") { "${hex3(it.key)}=${it.value}" }
                log.summary("frames in ${(clock() - since) / MS_PER_S} s: $frames")
                counts.clear()
                since = clock()
            }
            log.flush()
        }
        log.step("drive log stopped")
    }

    /** Each wish list id once, adding its frames to [counts]. */
    private fun listenRound(counts: MutableMap<Int, Int>) {
        can.enterMonitor()
        for (id in config.wishIds) {
            if (!running()) break
            counts[id] = (counts[id] ?: 0) + can.listen(id, config.driveListenMs, summary = false)
        }
        can.leaveMonitor()
    }

    /** Resets the adapter and finds the protocol; false when the car does not answer. */
    private fun setUp(): Boolean {
        log.step("adapter setup")
        cmd("ATZ", RESET_MS)
        SETUP.forEach { cmd(it) }
        header = null
        // The first request also searches for the protocol, which can take seconds.
        val first = cmd("0100", SEARCH_MS)
        val protocol = cmd("ATDPN")?.trim().orEmpty()
        can11 = protocol.removePrefix("A") == "6" || protocol.removePrefix("A") == "8"
        log.info("protocol $protocol (${if (can11) "11-bit CAN" else "not 11-bit CAN"})")
        val answered = first?.let { parse(it) } is ElmResponse.Messages
        if (!answered) log.info("the car did not answer 01 00: is the ignition on?")
        return answered
    }

    /** The adapter's identity and the car's VIN, model year and serial cut off. */
    private fun identity() {
        IDENTITY.forEach { cmd(it) }
        val vin = (parse(cmd("0902", REQUEST_MS)) as? ElmResponse.Messages)?.messages
            ?.firstNotNullOfOrNull { Vin.of(it.bytes) }
        if (vin == null) {
            log.info("VIN: no answer")
        } else {
            // The serial (last six characters) is left out: the log is shared.
            log.info("VIN ${vin.take(VIN_KEPT)}****** gen1 FR-S/86/BRZ: ${CanBroadcasts.wantedFor(vin)}")
        }
        listOf("0904", "090A").forEach { cmd(it, REQUEST_MS) }
    }

    private fun supportedPids(): Set<Int> {
        val supported = sortedSetOf<Int>()
        var base: Int? = SupportedPids.bitmaskPids.first()
        while (base != null && running()) {
            val response = parse(cmd("01%02X".format(base), REQUEST_MS)) as? ElmResponse.Messages ?: break
            val found = SupportedPids.fromMessages(response.messages)
            supported += found
            base = SupportedPids.nextBitmask(base, found)
        }
        log.info("supported 01 PIDs: " + supported.joinToString(" ") { "%02X".format(it) })
        return supported - SupportedPids.bitmaskPids.toSet()
    }

    private fun pidRates(supported: Set<Int>) {
        val known = StandardPids.all.associateBy { it.pid }
        for (pid in supported) {
            if (!running()) return
            readRepeated("pid", null, "01%02X".format(pid), known[pid])
        }
    }

    /** Six fast PIDs in one request (FBBD-270): its rate against the single-PID ones. */
    private fun multiPid(supported: Set<Int>) {
        val pids = MULTI.filter { it in supported }
        if (pids.size < 2 || !running()) return
        readRepeated("pid", null, "01" + pids.joinToString("") { "%02X".format(it) }, null)
    }

    private fun dtcs() {
        log.step("trouble codes (read only)")
        for (mode in listOf("03", "07", "0A")) {
            setHeader(null)
            val messages = (parse(cmd(mode, REQUEST_MS)) as? ElmResponse.Messages)?.messages.orEmpty()
            val codes = messages.flatMap { m -> Dtc.codes(m.bytes).map { "${ecu(m)}:$it" } }
            log.data("dtc", "mode $mode: ${codes.ifEmpty { listOf("none") }.joinToString(" ")}")
            if (codes.isNotEmpty()) log.info("mode $mode codes: ${codes.joinToString(" ")}")
        }
    }

    /**
     * Reads [command] at [to] [ReaderConfig.pidReads] times and logs as [kind]: answers, rate,
     * latency, which ECUs answered, the last answer and, with [channel], its value.
     */
    private fun readRepeated(kind: String, to: Int?, command: String, channel: PidChannel?) {
        setHeader(to)
        var ok = 0
        var latencyMax = 0L
        var latencySum = 0L
        var last: List<EcuMessage> = emptyList()
        val began = clock()
        repeat(config.pidReads) {
            if (!running()) return
            val t0 = clock()
            val text = line.command(command, REQUEST_MS)
            val latency = clock() - t0
            latencySum += latency
            latencyMax = maxOf(latencyMax, latency)
            val messages = (text?.let { parse(it) } as? ElmResponse.Messages)?.messages.orEmpty()
            if (messages.isNotEmpty()) {
                ok++
                last = messages
            } else if (it == 0) {
                log.data("cmd", "$command -> ${clean(text)}")
            }
        }
        val seconds = (clock() - began).coerceAtLeast(1) / MS_PER_S
        val value = channel?.decode(last)?.let { " ${channel.id}=${"%.2f".format(it)}${channel.unit}" }.orEmpty()
        val ecus = last.mapNotNull { it.header }.distinct().joinToString(",") { hex3(it) }
        val rate = "ok=$ok/${config.pidReads} hz=${"%.1f".format(ok / seconds)}"
        val latency = "lat_avg=${latencySum / config.pidReads} lat_max=$latencyMax"
        val answer = "ecus=$ecus$value last=${last.joinToString(" | ")}"
        log.data(kind, "${hex3(to ?: DEFAULT_HEADER)} $command $rate $latency $answer")
    }

    /** Each wish list request once, for the drive log. */
    private fun readWishList() = config.wishList.forEach { if (running()) readOnce(it) }

    /** One read of [channel] for the drive log. */
    private fun readOnce(channel: PidChannel) {
        setHeader(channel.header)
        val messages = (parse(line.command(channel.command, REQUEST_MS)) as? ElmResponse.Messages)?.messages.orEmpty()
        val value = channel.decode(messages)?.let { "%.2f".format(it) } ?: "-"
        log.data("value", "${channel.id}=$value raw=${messages.joinToString(" | ")}")
    }

    /**
     * Every mode 21 local identifier 00..FF of each [ReaderConfig.sweepHeaders] ECU: which answer
     * and with what, to find TPMS and other values the wish list lacks. Read only.
     */
    private fun sweep() {
        for (h in config.sweepHeaders) {
            if (!running()) return
            log.step("mode 21 sweep of ${hex3(h)}")
            setHeader(h)
            cmd("ATST19")
            var answered = 0
            var refused = 0
            for (id in 0..LAST_LOCAL_ID) {
                if (!running()) break
                val command = "21%02X".format(id)
                val text = line.command(command, REQUEST_MS)
                val messages = (text?.let { parse(it) } as? ElmResponse.Messages)?.messages.orEmpty()
                val positive = messages.filter { it.bytes.firstOrNull()?.toInt() == POSITIVE_21 }
                when {
                    positive.isNotEmpty() -> {
                        answered++
                        log.data("sweep", "${hex3(h)} $command -> ${positive.joinToString(" | ")}")
                    }
                    messages.isNotEmpty() -> refused++
                }
            }
            cmd("ATST32")
            log.summary("sweep ${hex3(h)}: $answered answered, $refused refused")
        }
        setHeader(null)
    }

    private fun setHeader(to: Int?) {
        if (to == header) return
        cmd("ATSH" + hex3(to ?: DEFAULT_HEADER))
        header = to
    }

    private fun cmd(text: String, timeoutMs: Long = AT_MS): String? {
        val t0 = clock()
        val answer = line.command(text, timeoutMs)
        log.data("cmd", "$text ${clock() - t0}ms -> ${clean(answer)}")
        return answer
    }

    private fun parse(text: String?): ElmResponse? = text?.let { ElmParser.parse(it, headers = true) }

    private fun ecu(m: EcuMessage) = m.header?.let { hex3(it) } ?: "?"

    companion object {
        /** Echo, line feeds and spaces off, headers on (so each ECU's answer shows its id), adaptive timing. */
        val SETUP = listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATSP0")
        val IDENTITY = listOf("ATI", "AT@1", "STI", "STDI", "ATRV")

        /** rpm, speed, throttle, accelerator, pedal D, engine load. */
        val MULTI = listOf(0x0C, 0x0D, 0x11, 0x5A, 0x49, 0x04)

        const val AT_MS = 1_000L
        const val REQUEST_MS = 2_000L
        const val RESET_MS = 3_000L
        const val SEARCH_MS = 10_000L
        const val RATES_EVERY_MS = 60_000L
        const val DEFAULT_HEADER = 0x7DF
        private const val MS_PER_S = 1_000.0
        private const val VIN_KEPT = 11
        private const val LAST_LOCAL_ID = 0xFF
        private const val POSITIVE_21 = 0x61
    }
}

/** An 11-bit CAN id as three hex digits. */
fun hex3(id: Int): String = "%03X".format(id)

/** An answer on one line for the log. */
fun clean(text: String?): String =
    text?.split('\r', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" | ") ?: "(no prompt)"
