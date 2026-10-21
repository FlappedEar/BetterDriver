@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How a simulated adapter treats the ELM327 response count (`010C1`). */
private enum class CountSupport {
    /** Returns as soon as that many ECU messages are in. */
    MESSAGES,

    /** Returns as soon as that many CAN frames are in, cutting a longer answer short. */
    FRAMES,

    /** Answers `?`, like an adapter that does not know it. */
    REJECTED,

    /** Takes it but waits out its timeout anyway. */
    IGNORED,
}

/**
 * Arek's 2023 Corolla behind an ELM327 on a fake clock (FBBD-270), timed after his 16:23 log of
 * 2026-10-20 (1.2.0, cheap Wi-Fi ELM327 v1.5, protocol A6):
 * - an AT command takes [transportMs]: ATRV was logged 7 ms after the identity line.
 * - a mode 01 request takes [transportMs] + [ecuMs] for the first frame, [frameMs] for each
 *   further one, then the adapter's wait for more ECUs, [trailingWaitMs], unless a response count
 *   it honours is met. 7 + 25 + 200 is the logged latency_ms of 215-294 (about 230). How those
 *   230 ms split into ECU time and the adapter's wait is NOT in the log: [ecuMs] = 25 and
 *   [frameMs] = 3 are assumptions, checked against slower values below.
 *
 * The car has the PIDs it listed in that log, with fixed values. Answers are single-frame or
 * ISO-TP multi-frame, printed with headers on or off as `AT H1`/`AT H0` set. [multiPidEcu] false is
 * an ECU that answers only the first PID of a multi-PID request; [rejectHeaders] an adapter that
 * answers `?` to `AT H1`, [rejectHeadersOff] one that answers `?` to `AT H0` once headers
 * are on; [tcmSilentFor] keeps the TCM quiet for that many first requests. [tcm] adds a second ECU (7E9) that
 * answers speed and coolant too, as a transmission ECU can. [maxRequestBytes] is the longest mode 01
 * request (in data bytes, a count digit not included) the adapter takes; a longer one is answered
 * `?` at once, as the adapter itself refuses it. [echo] prints each mode 01 request before its answer
 * (AT commands are not echoed, so ATDPN still reads as CAN).
 *
 * The same clone on 2026-10-21 (1.4.1, Arek's 08:04 log) showed what the model above got wrong for it:
 * the 6-PID probe `010C0D11494A04` was answered `?` with headers on and off, within the 41 ms
 * the whole probe took, and the response count was taken (no `?`) but did not shorten anything:
 * latency_ms 226-241 with the count on, as without it. So this clone is [CountSupport.IGNORED]
 * with a [maxRequestBytes] below 7; which shorter request it takes is not in the log.
 */
@Suppress("LongParameterList")
private class TimedCorolla(
    val count: CountSupport,
    val multiPidEcu: Boolean = true,
    val tcm: Boolean = false,
    val transportMs: Long = 7,
    val ecuMs: Long = 25,
    val frameMs: Long = 3,
    val trailingWaitMs: Long = 200,
    val rejectHeaders: Boolean = false,
    val rejectHeadersOff: Boolean = false,
    val tcmSilentFor: Int = 0,
    val maxRequestBytes: Int = Int.MAX_VALUE,
    val echo: Boolean = false,
) : ObdClock {
    var now = 0L
    val sent = ArrayList<String>()
    lateinit var line: ElmLine
    private var headers = false
    private var mode01Requests = 0

    private val supported: Set<Int> = (
        "01,03,04,05,06,07,0C,0D,0E,0F,10,11,13,1C,1F,20,21,2E,30,31,33,34,35,3C,3E,40,41,42,43,44,45,46,47," +
            "49,4A,4C,4D,4E,51,55,56,5B,5C,60,62,63,65,69,6D,7A,80,87,8E,9A,9D,9E,A0,A6"
        ).split(',').map { it.toInt(16) }.toSet()

    /** Answer bytes of each PID: A, or A B. Oil 0x5C 0x7D = 85 °C, coolant 0x05 0x82 = 90 °C. */
    private val values = mapOf(
        0x04 to "4D", 0x05 to "82", 0x0C to "1AF8", 0x0D to "32", 0x0E to "90", 0x0F to "41", 0x10 to "0190",
        0x11 to "40", 0x42 to "3840", 0x44 to "8000", 0x46 to "3C", 0x49 to "33", 0x4A to "1A", 0x5C to "7D",
    )
    private val tcmPids = setOf(0x05, 0x0D)

    private val at = mapOf(
        "ATZ" to "\r\rELM327 v1.5", "ATE0" to "OK", "ATL0" to "OK", "ATS0" to "OK", "ATH0" to "OK",
        "ATH1" to "OK", "ATAT1" to "OK", "ATSP0" to "OK", "ATI" to "ELM327 v1.5",
        "AT@1" to "OBDII to RS232 Interpreter", "ATRV" to "12.9V", "ATDPN" to "A6",
    )

    fun write(bytes: ByteArray) {
        val cmd = String(bytes, Charsets.US_ASCII).trimEnd('\r')
        sent += cmd
        if (cmd == "ATH1" && rejectHeaders) {
            now += transportMs
            line.feed("?\r\r>".toByteArray(Charsets.US_ASCII))
            return
        }
        if (cmd == "ATH0" && headers && rejectHeadersOff) {
            now += transportMs
            line.feed("?\r\r>".toByteArray(Charsets.US_ASCII))
            return
        }
        if (cmd == "ATH1") headers = true
        if (cmd == "ATH0") headers = false
        val answer = at[cmd]?.also { now += transportMs } ?: if (cmd.startsWith("01")) mode01(cmd) else "?"
        val echoed = if (echo && cmd.startsWith("01")) cmd + "\r" + answer else answer
        line.feed((echoed + "\r\r>").toByteArray(Charsets.US_ASCII))
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun mode01(cmd: String): String {
        val digits = cmd.drop(2)
        val countDigit = if (digits.length % 2 == 1) digits.last().digitToInt(16) else null
        if (countDigit != null && count == CountSupport.REJECTED) {
            now += transportMs
            return "?"
        }
        val pids = digits.take(digits.length / 2 * 2).chunked(2).map { it.toInt(16) }
        if (1 + pids.size > maxRequestBytes) {
            now += transportMs
            return "?"
        }
        val asked = if (multiPidEcu) pids else pids.take(1)
        val messages = listOfNotNull(
            ecuAnswer(asked) { it in supported || it == 0x00 }?.let { 0x7E8 to it },
            if (tcm && ++mode01Requests > tcmSilentFor) {
                ecuAnswer(asked) { it in tcmPids }?.let { 0x7E9 to it }
            } else {
                null
            },
        ).map { (id, data) -> frames(id, data) }
        if (messages.isEmpty()) {
            now += transportMs + ecuMs + trailingWaitMs
            return "NO DATA"
        }
        now += transportMs + ecuMs + frameMs * (messages.sumOf { it.size } - 1)
        // Frames in the order they arrive: each ECU's in turn (no interleaving here).
        val lines = messages.flatten()
        val shown = when {
            countDigit == null || count == CountSupport.IGNORED -> null
            count == CountSupport.MESSAGES && messages.size >= countDigit -> lines
            count == CountSupport.FRAMES && lines.size >= countDigit -> lines.take(countDigit)
            else -> null
        }
        if (shown == null) now += trailingWaitMs
        return (shown ?: lines).joinToString("\r")
    }

    /** [data] from CAN id [id] as the adapter prints it: one line per frame. */
    @Suppress("ReturnCount")
    private fun frames(id: Int, data: String): List<String> {
        val bytes = data.chunked(2)
        val head = if (headers) "%03X".format(id) else ""
        if (bytes.size <= 7) return listOf(if (headers) head + "%02X".format(bytes.size) + data else data)
        if (!headers) {
            val out = mutableListOf("%03X".format(bytes.size), "0:" + bytes.take(6).joinToString(""))
            bytes.drop(6).chunked(7).forEachIndexed { i, c ->
                out += "${(i + 1) % 16}:" + c.joinToString("").padEnd(14, '0')
            }
            return out
        }
        val out = mutableListOf(head + "1%03X".format(bytes.size) + bytes.take(6).joinToString(""))
        bytes.drop(6).chunked(7).forEachIndexed { i, c ->
            out += head + "2%X".format((i + 1) % 16) + c.joinToString("").padEnd(14, '0')
        }
        return out
    }

    /** One ECU's answer to [pids], or null when it has none of them. */
    private fun ecuAnswer(pids: List<Int>, has: (Int) -> Boolean): String? {
        val parts = pids.filter(has).map { pid ->
            if (pid % 0x20 == 0) "%02X%s".format(pid, bitmask(pid)) else "%02X%s".format(pid, values[pid] ?: "00")
        }
        return if (parts.isEmpty()) null else "41" + parts.joinToString("")
    }

    private fun bitmask(base: Int): String {
        var bits = 0L
        for (i in 1..32) if (base + i in supported) bits = bits or (1L shl (32 - i))
        return "%08X".format(bits)
    }

    override fun nowMs() = now

    override fun sleep(ms: Long) {
        now += ms
    }
}

private class Timed : ObdListener {
    val values = ArrayList<Triple<String, Double, Long>>()
    val logs = ArrayList<String>()

    override fun ready(
        adapter: String,
        protocol: String,
        voltage: String?,
        supported: Set<Int>,
        channels: List<PidChannel>,
    ) = Unit

    override fun value(channel: PidChannel, value: Double, atMs: Long) {
        values += Triple(channel.id, value, atMs)
    }

    override fun log(text: String) {
        logs += text
    }
}

/**
 * FBBD-270: how fast the Corolla's channels are read with multi-PID requests and the response
 * count, against the same adapter timing that gave 0.4 Hz in Arek's log. Rates are counted over
 * 60 s after the first 5 s (setup and discovery).
 */
class ObdRatesTest {
    private class Run(val rates: Map<String, Double>, val listener: Timed, val car: TimedCorolla)

    private fun run(
        car: TimedCorolla,
        multiPid: Boolean = true,
        responseCount: Boolean = true,
        pollSet: () -> PollSet = { PollSet.All },
    ): Run {
        val got = Timed()
        val poller = ElmPoller(
            line = ElmLine(car::write).also { car.line = it },
            profile = CarProfile.Generic,
            listener = got,
            running = { car.now < END_MS },
            clock = car,
            rateLogMs = 30_000,
            multiPid = multiPid,
            responseCount = responseCount,
            pollSet = pollSet,
        )
        poller.run()
        val window = got.values.filter { it.third in START_MS until END_MS }
        val rates = window.groupingBy { it.first }.eachCount()
            .mapValues { it.value * 1000.0 / (END_MS - START_MS) }
        return Run(rates, got, car)
    }

    private fun table(name: String, run: Run) {
        val ids = listOf("rpm", "speed", "throttle", "pedal_d", "maf", "coolant_temp", "oil_temp")
        val rates = ids.joinToString(" ") { "$it=%.2f".format(Locale.ROOT, run.rates[it] ?: 0.0) }
        println("FBBD-270 %-34s %s".format(name, rates))
    }

    @Test
    fun `before FBBD-270 the simulated adapter gives the logged rates`() {
        val before = run(TimedCorolla(CountSupport.MESSAGES), multiPid = false, responseCount = false)
        table("before (one PID per request)", before)
        // Log: rpm/speed/throttle 0.4 Hz, temperatures about 0.08 Hz, latency about 230 ms.
        assertEquals(0.4, before.rates.getValue("rpm"), 0.05)
        assertEquals(0.4, before.rates.getValue("speed"), 0.05)
        assertEquals(0.085, before.rates.getValue("oil_temp"), 0.03)
        assertTrue(before.car.sent.none { it.startsWith("01") && it.length % 2 == 1 }, "no response count")
    }

    @Test
    fun `an adapter that honours the response count reads the driver's inputs at 10 Hz or more`() {
        val after = run(TimedCorolla(CountSupport.MESSAGES))
        table("multi-PID + count (honoured)", after)
        for (id in listOf("rpm", "speed", "throttle", "pedal_d")) {
            assertTrue(after.rates.getValue(id) >= 10.0, "$id ${after.rates[id]}")
        }
        // Temperatures stay at their 2 Hz cap: the rest goes to the fast channels.
        assertEquals(2.0, after.rates.getValue("oil_temp"), 0.1)
        assertTrue(after.car.sent.any { it == "010C0D11494A041" }, after.car.sent.take(40).toString())
        assertTrue(
            after.listener.logs.any {
                it.startsWith("polling rpm+speed+throttle+pedal_d+pedal_e+engine_load ") && "multi_pid=on" in it
            },
            after.listener.logs.toString(),
        )
    }

    @Test
    fun `with a slower ECU it is still several times the logged rate`() {
        val after = run(TimedCorolla(CountSupport.MESSAGES, ecuMs = 60, frameMs = 10, trailingWaitMs = 165))
        table("multi-PID + count, ECU 60 ms", after)
        assertTrue(after.rates.getValue("rpm") >= 5.0, "rpm ${after.rates["rpm"]}")
    }

    @Test
    fun `an adapter that rejects the response count still gains from multi-PID requests`() {
        val after = run(TimedCorolla(CountSupport.REJECTED))
        table("multi-PID, count rejected", after)
        assertTrue(after.listener.logs.any { it.startsWith("response_count off") })
        // Two fast requests instead of nine and one slow instead of five: over four times the logged rate.
        assertTrue(after.rates.getValue("rpm") >= 1.8, "rpm ${after.rates["rpm"]}")
        assertTrue(after.rates.getValue("oil_temp") >= 0.35, "oil ${after.rates["oil_temp"]}")
        // Once rejected, the count is not sent again.
        assertEquals(1, after.car.sent.count { it.startsWith("01") && it.length % 2 == 1 })
    }

    @Test
    fun `an adapter that ignores the response count is no slower than without it`() {
        val after = run(TimedCorolla(CountSupport.IGNORED))
        table("multi-PID, count ignored", after)
        assertTrue(after.rates.getValue("rpm") >= 1.8, "rpm ${after.rates["rpm"]}")
    }

    @Test
    fun `an ECU that answers one PID per request falls back to single PIDs`() {
        val after = run(TimedCorolla(CountSupport.MESSAGES, multiPidEcu = false))
        table("ECU without multi-PID, count", after)
        val logs = after.listener.logs
        assertTrue(logs.any { it.startsWith("multi_pid off: 010C0D11494A04") }, logs.toString())
        // The ECU answered, so fewer PIDs would not help: no smaller group is tried.
        assertTrue(after.car.sent.none { it == "010C0D11" || it == "010C0D" }, after.car.sent.take(40).toString())
        assertTrue(after.car.sent.any { it == "010C1" })
        // Every channel still read, faster than the log thanks to the count.
        assertTrue(after.rates.getValue("rpm") >= 1.5, "rpm ${after.rates["rpm"]}")
        assertTrue(after.rates.getValue("oil_temp") > 0.0)
    }

    @Test
    fun `the Wi-Fi clone of 21 Oct gives the logged rates when it takes no multi-PID request`() {
        // Worst case for that clone: it takes nothing longer than a single PID. Session report
        // 2026-10-21: fast 0.400-0.403 Hz, slow 0.079-0.081 Hz; rates_hz latency_ms 226-241.
        val clone = run(TimedCorolla(CountSupport.IGNORED, maxRequestBytes = 2))
        table("Wi-Fi clone 21 Oct, single PIDs", clone)
        val logs = clone.listener.logs
        assertTrue(logs.any { it == "multi_pid 010C0D11494A04 answered ?, trying 3 PIDs a request" }, logs.toString())
        assertTrue(logs.any { it == "multi_pid 010C0D11 answered ?, trying 2 PIDs a request" }, logs.toString())
        assertTrue(logs.any { it == "multi_pid off: 010C0D answered ?" }, logs.toString())
        for (id in listOf("rpm", "speed", "throttle", "pedal_d")) {
            assertEquals(0.40, clone.rates.getValue(id), 0.04, id)
        }
        for (id in listOf("coolant_temp", "oil_temp")) {
            assertEquals(0.08, clone.rates.getValue(id), 0.008, id)
        }
        val latency = Regex("latency_ms=([0-9]+)").find(logs.last { it.startsWith("rates_hz ") })!!
            .groupValues[1].toInt()
        assertTrue(latency in 226..241, "latency_ms $latency")
    }

    @Test
    fun `an adapter that refuses six PIDs a request is asked three`() {
        val after = run(TimedCorolla(CountSupport.IGNORED, maxRequestBytes = 4))
        table("six refused, three a request", after)
        assertTrue(after.car.sent.any { it == "010C0D11" }, after.car.sent.take(40).toString())
        assertTrue(after.car.sent.none { it.startsWith("01") && it.length > 9 && it != "010C0D11494A04" })
        assertTrue(
            after.listener.logs.any { it.startsWith("polling rpm+speed+throttle pedal_d+pedal_e+engine_load ") },
            after.listener.logs.toString(),
        )
        // Three PIDs for one request's wait: about three times the clone's 0.4 Hz.
        for (id in listOf("rpm", "speed", "throttle", "pedal_d")) {
            assertTrue(after.rates.getValue(id) >= 1.0, "$id ${after.rates[id]}")
        }
        assertTrue(after.rates.getValue("oil_temp") >= 0.2, "oil ${after.rates["oil_temp"]}")
        assertEquals(85.0, after.listener.values.last { it.first == "oil_temp" }.second)
    }

    @Test
    fun `a refusal after the echoed command still tries smaller groups`() {
        val after = run(TimedCorolla(CountSupport.IGNORED, maxRequestBytes = 4, echo = true))
        assertTrue(after.car.sent.any { it == "010C0D11" }, after.car.sent.take(40).toString())
        assertTrue(after.rates.getValue("rpm") >= 1.0, "rpm ${after.rates["rpm"]}")
    }

    @Test
    fun `an adapter that refuses three PIDs a request is asked two`() {
        val after = run(TimedCorolla(CountSupport.IGNORED, maxRequestBytes = 3))
        table("three refused, two a request", after)
        assertTrue(after.car.sent.any { it == "010C0D" }, after.car.sent.take(40).toString())
        assertTrue(after.listener.logs.any { it.startsWith("polling rpm+speed throttle+pedal_d ") })
        for (id in listOf("rpm", "speed", "throttle", "pedal_d")) {
            assertTrue(after.rates.getValue(id) >= 0.7, "$id ${after.rates[id]}")
        }
        assertEquals(1726.0, after.listener.values.last { it.first == "rpm" }.second)
    }

    @Test
    fun `with two ECUs answering the count waits for both`() {
        val after = run(TimedCorolla(CountSupport.MESSAGES, tcm = true))
        table("multi-PID + count, ECM and TCM", after)
        assertTrue(after.car.sent.any { it == "010C0D11494A042" }, after.car.sent.take(40).toString())
        assertTrue(after.rates.getValue("rpm") >= 10.0)
        assertTrue(after.rates.getValue("coolant_temp") > 1.0)
    }

    @Test
    fun `an adapter that counts CAN frames is told the frame count after one short answer`() {
        val after = run(TimedCorolla(CountSupport.FRAMES))
        table("multi-PID + count (frames)", after)
        val logs = after.listener.logs
        assertTrue(logs.any { it.startsWith("response_count counts frames") }, logs.toString())
        // 010C0D11494A04: 14 bytes, a first frame and two consecutive frames.
        assertTrue(after.car.sent.any { it == "010C0D11494A043" }, after.car.sent.take(40).toString())
        assertTrue(after.rates.getValue("rpm") >= 10.0, "rpm ${after.rates["rpm"]}")
        assertTrue(after.rates.getValue("oil_temp") >= 1.5, "oil ${after.rates["oil_temp"]}")
    }

    @Test
    fun `headers off when the adapter rejects AT H1`() {
        val after = run(TimedCorolla(CountSupport.MESSAGES, rejectHeaders = true))
        table("multi-PID + count, headers off", after)
        assertTrue(after.rates.getValue("rpm") >= 10.0, "rpm ${after.rates["rpm"]}")
        assertEquals(85.0, after.listener.values.last { it.first == "oil_temp" }.second)
    }

    @Test
    fun `an adapter stuck with headers on is still read`() {
        // The headers-on probe gets one PID back, and AT H0 is then rejected: polling goes on with
        // single PIDs, still read with headers on as the adapter prints them.
        val after = run(TimedCorolla(CountSupport.MESSAGES, multiPidEcu = false, rejectHeadersOff = true))
        table("ECU without multi-PID, stuck on H1", after)
        assertTrue(after.rates.getValue("rpm") >= 1.5, "rpm ${after.rates["rpm"]}")
        assertEquals(85.0, after.listener.values.last { it.first == "oil_temp" }.second)
    }

    @Test
    fun `requests per second counts the whole interval`() {
        val after = run(TimedCorolla(CountSupport.MESSAGES))
        val rate = after.listener.logs.last { it.startsWith("rates_hz ") }
        val perSecond = Regex("requests_per_s=([0-9.]+)").find(rate)!!.groupValues[1].toDouble()
        // Two fast requests at the rpm rate and the slow one at the oil rate.
        assertEquals(2 * after.rates.getValue("rpm") + after.rates.getValue("oil_temp"), perSecond, 2.0)
    }

    @Test
    fun `an ECU missing from the first answer is still waited for`() {
        // The TCM misses the probe; the count comes from several answers, so it includes the TCM.
        val after = run(TimedCorolla(CountSupport.MESSAGES, tcm = true, tcmSilentFor = 1))
        val counted = after.car.sent.filter { it.startsWith("010C0D11494A04") && it.length == 15 }
        assertTrue(counted.isNotEmpty() && counted.all { it.endsWith("2") }, counted.distinct().toString())
        assertTrue(after.rates.getValue("rpm") >= 10.0, "rpm ${after.rates["rpm"]}")
    }

    @Test
    fun `values are right and the log has the latest of each, oil temperature included`() {
        val after = run(TimedCorolla(CountSupport.MESSAGES))
        val last = after.listener.values.associate { it.first to it.second }
        assertEquals(1726.0, last.getValue("rpm"))
        assertEquals(50.0, last.getValue("speed"))
        assertEquals(85.0, last.getValue("oil_temp"))
        assertEquals(90.0, last.getValue("coolant_temp"))
        assertEquals(4.0, last.getValue("maf"))
        assertEquals(14.4, last.getValue("module_voltage"), 1e-9)
        val values = after.listener.logs.last { it.startsWith("values ") }
        assertTrue("oil_temp=85.0" in values && "coolant_temp=90.0" in values, values)
        assertTrue(after.listener.logs.any { it.startsWith("rates_hz ") && "response_count=on" in it })
    }

    @Test
    fun `only what is used on the Wi-Fi clone of 21 Oct, single PIDs`() {
        // FBBD-283: rpm, pedal and four temperatures instead of 14 channels, on the clone's 230 ms
        // single-PID requests. Before: 0.40 Hz fast, 0.08 Hz slow (test above).
        val clone = run(TimedCorolla(CountSupport.IGNORED, maxRequestBytes = 2), pollSet = { PollSet.Driver })
        table("FBBD-283 clone, single PIDs", clone)
        val read = setOf("rpm", "pedal_d", "coolant_temp", "oil_temp", "intake_air_temp", "ambient_temp")
        assertEquals(read, clone.rates.keys)
        for (id in listOf("rpm", "pedal_d")) assertTrue(clone.rates.getValue(id) >= 1.7, "$id ${clone.rates[id]}")
        for (id in listOf("coolant_temp", "oil_temp")) {
            assertTrue(clone.rates.getValue(id) >= 0.15, "$id ${clone.rates[id]}")
        }
        val logs = clone.listener.logs
        assertTrue(logs.any { it.startsWith("not polled (not used): engine_load speed ") }, logs.toString())
        assertTrue(clone.car.sent.none { it == "010D" || it == "0110" }, "speed and MAF are not asked")
    }

    @Test
    fun `only what is used on a clone that takes two PIDs a request`() {
        val clone = run(TimedCorolla(CountSupport.IGNORED, maxRequestBytes = 3), pollSet = { PollSet.Driver })
        table("FBBD-283 clone, two PIDs", clone)
        // The four temperatures' request is the probe: refused, so two PIDs a request for all.
        assertTrue(clone.car.sent.any { it == "010C49" }, clone.car.sent.take(40).toString())
        assertTrue(clone.listener.logs.any { it == "multi_pid 01050F465C answered ?, trying 3 PIDs a request" })
        for (id in listOf("rpm", "pedal_d")) assertTrue(clone.rates.getValue(id) >= 3.0, "$id ${clone.rates[id]}")
        // Two slow requests for one fast one's ten: under the cap, about 0.37 Hz each.
        assertTrue(clone.rates.getValue("oil_temp") >= 0.3, "oil ${clone.rates["oil_temp"]}")
    }

    @Test
    fun `only what is used on the Vgate, six PIDs a request and the count honoured`() {
        // Vgate vLinker MC (STN1151), log of 2026-10-21 12:52: 6-PID groups, latency_ms 47-64, fast
        // 8.7 Hz with all 14 channels. Simulated here with the count honoured (no trailing wait).
        val all = run(TimedCorolla(CountSupport.MESSAGES, transportMs = 20))
        val vgate = run(TimedCorolla(CountSupport.MESSAGES, transportMs = 20), pollSet = { PollSet.Driver })
        table("Vgate-like, all channels", all)
        table("FBBD-283 Vgate-like", vgate)
        assertTrue(vgate.car.sent.any { it.startsWith("010C49") }, vgate.car.sent.take(40).toString())
        assertTrue(vgate.rates.getValue("rpm") >= 1.5 * all.rates.getValue("rpm"), "rpm ${vgate.rates["rpm"]}")
        assertTrue(vgate.rates.getValue("pedal_d") >= 15.0, "pedal ${vgate.rates["pedal_d"]}")
        assertEquals(PollSet.DRIVER_SLOW_MAX_HZ, vgate.rates.getValue("oil_temp"), 0.05)
        assertEquals(85.0, vgate.listener.values.last { it.first == "oil_temp" }.second)
    }

    @Test
    fun `a tile chosen during the session is read from the next check on`() {
        // FBBD-283, Codex on #281: the driver puts MAF on a tile 30 s into the drive.
        val car = TimedCorolla(CountSupport.MESSAGES, transportMs = 20)
        val got = Timed()
        val set = { if (car.now < 30_000) PollSet.Driver else PollSet.Driver.plus(listOf("maf")) }
        ElmPoller(
            line = ElmLine(car::write).also { car.line = it },
            profile = CarProfile.Generic,
            listener = got,
            running = { car.now < END_MS },
            clock = car,
            pollSet = set,
        ).run()
        val maf = got.values.filter { it.first == "maf" }
        assertTrue(maf.isNotEmpty() && maf.first().third in 30_000L..(30_000L + ElmPoller.POLL_SET_CHECK_MS + 500))
        assertTrue(got.logs.any { it == "poll set changed" }, got.logs.toString())
        assertEquals(1, got.logs.count { it.startsWith("identity ") }, "same connection, no new setup")
        assertTrue(got.values.any { it.first == "oil_temp" && it.third > 40_000 }, "the rest goes on")
    }

    @Test
    fun `a non-CAN car keeps one PID per request and no count`() {
        val car = object : ObdClock {
            var now = 0L
            override fun nowMs() = now
            override fun sleep(ms: Long) {
                now += ms
            }
        }
        val sent = ArrayList<String>()
        val answers = mapOf(
            "ATZ" to "ELM327 v1.5", "ATDPN" to "A3", "0100" to "410000180000", "010C" to "410C1AF8",
            "010D" to "410D32",
        )
        lateinit var line: ElmLine
        line = ElmLine({ b ->
            val cmd = String(b, Charsets.US_ASCII).trimEnd('\r')
            sent += cmd
            car.now += 50
            line.feed(((answers[cmd] ?: "OK") + "\r\r>").toByteArray(Charsets.US_ASCII))
        })
        val got = Timed()
        ElmPoller(line, CarProfile.Generic, got, { car.now < 5_000 }, car).run()
        assertTrue("010C" in sent && "010D" in sent, sent.toString())
        assertTrue(sent.none { it.startsWith("010C0D") || it == "010C1" }, sent.toString())
    }

    private companion object {
        const val START_MS = 5_000L
        const val END_MS = 65_000L
    }
}
