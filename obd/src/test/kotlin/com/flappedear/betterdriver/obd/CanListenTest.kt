@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FBBD-263: an ELM327 on a car that broadcasts frames [bus] (id to data, hex) at the OBD port. `AT MA`
 * prints nothing until the phone sends a byte, then every frame on the bus [repeats] times (only the
 * `AT CRA` id when a filter is set), `STOPPED` and the prompt, with headers as `AT H1`/`AT H0` set. With
 * [monitor] false the adapter answers `?` to `AT CAF0` and `AT MA`. [bareCra] false is an ELM327 older
 * than v1.4b, which answers `?` to `AT CRA` without an id; [autoReceive] false refuses `AT AR` too.
 * Mode 01: RPM (0C) only. [vin] is the `09 02` answer (FBBD-284), none for null.
 */
@Suppress("LongParameterList")
private class BusElm(
    val bus: Map<Int, String>,
    val monitor: Boolean = true,
    val repeats: Int = 5,
    val bareCra: Boolean = true,
    val autoReceive: Boolean = true,
    val vin: String? = FrsVin,
) : ObdClock {
    var now = 0L
    val sent = ArrayList<String>()
    lateinit var line: ElmLine
    private var headers = false
    private var filter: Int? = null
    private var monitoring = false

    private val answers = mapOf(
        "ATZ" to "\r\rELM327 v2.2", "ATE0" to "OK", "ATL0" to "OK", "ATS0" to "OK", "ATH0" to "OK",
        "ATH1" to "OK", "ATAT1" to "OK", "ATSP0" to "OK", "ATI" to "ELM327 v2.2", "ATRV" to "12.6V",
        "ATDPN" to "A6", "0100" to "SEARCHING...\r410000100000", "010C" to "410C1AF8",
    )

    fun write(bytes: ByteArray) {
        now += 25
        // Any byte stops the monitor.
        if (monitoring) return stopMonitor()
        val cmd = String(bytes, Charsets.US_ASCII).trimEnd('\r')
        sent += cmd
        if (!track(cmd)) return feed("?")
        when {
            monitor && cmd == "ATMA" -> monitoring = true
            monitor && (cmd == "ATCAF0" || cmd == "ATCAF1" || cmd.startsWith("ATCRA") || cmd == "ATAR") -> feed("OK")
            cmd == "0902" -> feed(vin?.let(::vinAnswer) ?: "NO DATA")
            else -> feed(answers[cmd] ?: "?")
        }
    }

    /** Headers and the receive filter, as the adapter keeps them; false for a filter reset it refuses. */
    private fun track(cmd: String): Boolean {
        val refused = (cmd == "ATCRA" && !bareCra) || (cmd == "ATAR" && !autoReceive)
        if (!refused) {
            when {
                cmd == "ATH1" -> headers = true
                cmd == "ATH0" -> headers = false
                cmd == "ATCRA" || cmd == "ATAR" -> filter = null
                cmd.startsWith("ATCRA") -> filter = cmd.removePrefix("ATCRA").toInt(16)
            }
        }
        return !refused
    }

    private fun stopMonitor() {
        monitoring = false
        val frames = bus.filterKeys { filter == null || it == filter }
            .map { (id, data) -> if (headers) "%03X".format(id) + data else data }
            .let { once -> List(repeats) { once }.flatten() }
        feed(frames.joinToString("\r") + "\rSTOPPED")
    }

    /** A VIN answer as an ELM327 prints it with headers off: 20 bytes in three ISO-TP frames. */
    private fun vinAnswer(vin: String): String {
        val bytes = "490201" + vin.map { "%02X".format(it.code) }.joinToString("")
        val pairs = bytes.chunked(2)
        return "014\r0:" + pairs.take(6).joinToString("") + "\r1:" + pairs.drop(6).take(7).joinToString("") +
            "\r2:" + pairs.drop(13).joinToString("")
    }

    private fun feed(answer: String) = line.feed((answer + "\r\r>").toByteArray(Charsets.US_ASCII))

    override fun nowMs() = now

    override fun sleep(ms: Long) {
        now += ms
    }
}

/** A made-up first-generation FR-S VIN: the real prefix, a zero serial. */
private const val FrsVin = "JF1ZNAA1XD1700000"

private class CanHeard : ObdListener {
    var channels: List<PidChannel> = emptyList()
    val values = ArrayList<Pair<String, Double>>()
    val stamps = ArrayList<Long>()
    val logs = ArrayList<String>()

    override fun ready(
        adapter: String,
        protocol: String,
        voltage: String?,
        supported: Set<Int>,
        channels: List<PidChannel>,
    ) {
        this.channels = channels
    }

    override fun value(channel: PidChannel, value: Double, atMs: Long) {
        values += channel.id to value
        if (channel.id == "steering_angle") stamps += atMs
    }

    override fun log(text: String) {
        logs += text
    }
}

class CanListenTest {
    /** FR-S frames: steering 0x012C = 300 → -30.0°, plus the ids the FR-S signal requires. */
    private val frs = mapOf(
        0x0D0 to "2C01000000000000",
        0x0D1 to "0000000000000000",
        0x140 to "0000000000000000",
        0x360 to "0000757D00000000",
        0x7E8 to "03410C1AF8000000",
    )

    private fun run(elm: BusElm, stopAfterMs: Long = 3_000, pollSet: PollSet = PollSet.All): CanHeard {
        val got = CanHeard()
        ElmPoller(
            line = ElmLine(elm::write).also { elm.line = it },
            profile = CarProfile.Generic,
            listener = got,
            running = { elm.now < stopAfterMs },
            clock = elm,
            rateLogMs = 1_000,
            probeMs = 5,
            listenWindowMs = 5,
            pollSet = { pollSet },
        ).run()
        return got
    }

    @Test
    fun `only first-generation VINs are listened to`() {
        // FBBD-284, Codex on #284: model year (VIN position 10) 2012-2021 only; GR86 / BRZ from 2022
        // have JF1Z VINs too but other CAN frames.
        assertTrue(CanBroadcasts.wantedFor(FrsVin))
        assertTrue(CanBroadcasts.wantedFor("JF1ZCAC10M9600000"))
        assertTrue(!CanBroadcasts.wantedFor("JF1ZNBE10N9700000"), "2022 GR86")
        assertTrue(!CanBroadcasts.wantedFor("JTDBCMFE0PJ000000"), "Corolla")
    }

    @Test
    fun `the FR-S steering angle is heard between polls`() {
        val elm = BusElm(frs)
        val got = run(elm)
        assertEquals(listOf("rpm", "steering_angle"), got.channels.map { it.id })
        val logs = got.logs.toString()
        assertTrue(got.logs.any { it.startsWith("can_probe ms=5 frames=25 ids=5") && "0D0:5" in it }, logs)
        assertTrue(got.logs.any { it.startsWith("can_ids 0D0:5 0D1:5 140:5 360:5 025:0 0AA:0 0B4:0") }, logs)
        assertTrue(got.logs.any { it.endsWith("listen steering_angle=0D0") }, logs)
        val steering = got.values.filter { it.first == "steering_angle" }.map { it.second }
        assertTrue(steering.size >= 5, "steering $steering")
        assertTrue(steering.all { it == -30.0 }, "steering $steering")
        // Polling goes on: rpm is still read, and most of the adapter's time is still polling.
        val rpm = got.values.count { it.first == "rpm" }
        assertTrue(rpm > steering.size, "rpm $rpm steering ${steering.size}")
        assertTrue(got.logs.any { it.startsWith("can_hz steering_angle=") && "listen_share=" in it }, logs)
        // A listen sets the filter, turns CAN formatting off, monitors, and puts both back.
        val i = elm.sent.indexOfLast { it == "ATCRA0D0" }
        assertEquals(listOf("ATCRA0D0", "ATMA", "ATCRA", "ATCAF1"), elm.sent.subList(i, i + 4))
        assertEquals("ATCAF0", elm.sent[i - 1])
    }

    @Test
    fun `a Toyota steering frame reads the angle and its fraction`() {
        // STEER_ANGLE 0xFEC = -20 → -30.0°, STEER_FRACTION 0x3 = +0.3°.
        val toyota = mapOf(
            0x025 to "0FEC000030000000",
            0x0AA to "1A6F1A6F1A6F1A6F",
            0x0B4 to "0000000000000000",
            0x7E8 to "03410C1AF8000000",
        )
        // Listened for only when the session's car says so: by VIN a Toyota is never (FBBD-284).
        val got = run(BusElm(toyota, vin = null), pollSet = PollSet.All.copy(broadcastCar = true))
        val steering = got.values.filter { it.first == "steering_angle" }.map { it.second }
        assertTrue(steering.isNotEmpty(), got.logs.toString())
        assertTrue(steering.all { kotlin.math.abs(it - -29.7) < 1e-9 }, "steering $steering")
        assertTrue(got.logs.any { it.endsWith("listen steering_angle=025") }, got.logs.toString())
    }

    @Test
    fun `the Corolla is never probed or listened to`() {
        // FBBD-284, Arek 2026-10-21: "w Corolli mnie to nie interesuje". A 2023 Corolla's VIN starts
        // JTD (made-up serial); even if it showed 0x025 at the port, nothing is monitored.
        val toyota = mapOf(0x025 to "0FEC000030000000", 0x0AA to "1A6F1A6F1A6F1A6F", 0x0B4 to "0", 0x7E8 to "0")
        val elm = BusElm(toyota, vin = "JTDBCMFE0PJ000000")
        val got = run(elm)
        assertEquals(listOf("rpm"), got.channels.map { it.id })
        assertTrue("can_listen off: vin JTDBCMFE" in got.logs, got.logs.toString())
        assertTrue(elm.sent.none { it == "ATMA" || it.startsWith("ATCRA") || it == "ATCAF0" }, elm.sent.toString())
    }

    @Test
    fun `without a VIN only the session's car turns listening on`() {
        val elm = BusElm(frs, vin = null)
        val got = run(elm)
        assertTrue("can_listen off: vin not read" in got.logs, got.logs.toString())
        assertTrue(elm.sent.none { it == "ATMA" })
        val named = run(BusElm(frs, vin = null), pollSet = PollSet.All.copy(broadcastCar = true))
        assertTrue(named.values.any { it.first == "steering_angle" }, named.logs.toString())
    }

    @Test
    fun `an FR-S VIN turns listening on`() {
        val got = run(BusElm(frs))
        assertTrue("can_listen on: vin JF1ZNAA1" in got.logs, got.logs.toString())
        assertEquals("JF1ZNAA1XD1700000", Vin.of(byteArrayOf(0x49, 0x02, 0x01) + FrsVin.toByteArray()))
        assertEquals(null, Vin.of(byteArrayOf(0x41, 0x0C, 0x1A)))
    }

    @Test
    fun `a car without known broadcasts is only polled`() {
        val elm = BusElm(mapOf(0x123 to "0102030405060708", 0x7E8 to "03410C1AF8000000"))
        val got = run(elm)
        assertEquals(listOf("rpm"), got.channels.map { it.id })
        assertTrue(got.logs.any { it.startsWith("can_probe ") && "123:5" in it }, got.logs.toString())
        assertTrue(got.logs.any { it.startsWith("can_ids ") && it.endsWith("listen none") }, got.logs.toString())
        // After the probe, nothing but polling.
        val lastMonitor = elm.sent.indexOfLast { it == "ATMA" }
        assertTrue(elm.sent.drop(lastMonitor + 1).none { it.startsWith("ATCRA") && it != "ATCRA" || it == "ATMA" })
        assertTrue(got.values.any { it.first == "rpm" })
    }

    @Test
    fun `an ELM327 older than v1_4b drops the filter with AT AR`() {
        val elm = BusElm(frs, bareCra = false)
        val got = run(elm)
        assertTrue(got.values.count { it.first == "steering_angle" } >= 5, got.logs.toString())
        assertTrue(got.values.any { it.first == "rpm" })
        assertTrue(elm.sent.count { it == "ATAR" } > 1)
    }

    @Test
    fun `an adapter that keeps its filter stops listening and polls`() {
        val elm = BusElm(frs, bareCra = false, autoReceive = false)
        val got = run(elm)
        assertEquals(listOf("rpm"), got.channels.map { it.id })
        assertTrue(got.logs.any { it.startsWith("can_listen off: receive filter not restored") }, got.logs.toString())
        // The probe stopped at its first filtered count, and no listen ran after it.
        assertEquals(1, elm.sent.count { it.startsWith("ATCRA") && it != "ATCRA" })
        assertTrue(got.values.any { it.first == "rpm" })
    }

    @Test
    fun `an adapter that cannot monitor is polled as before`() {
        val elm = BusElm(frs, monitor = false)
        val got = run(elm)
        assertEquals(listOf("rpm"), got.channels.map { it.id })
        assertTrue("can_probe off: ATCAF0 not taken" in got.logs, got.logs.toString())
        assertTrue(elm.sent.none { it == "ATMA" || it == "ATH1" })
        assertTrue(got.values.any { it.first == "rpm" })
    }

    @Test
    fun `a listen's frames are stamped back from its end at the signal's period`() {
        // Five FR-S steering frames in one listen: 20 ms apart.
        val got = run(BusElm(frs))
        assertEquals(listOf(20L, 20L, 20L, 20L), got.stamps.take(5).zipWithNext { a, b -> b - a })
        // The next listen starts after the interval, so its first frame is later than the last one.
        assertTrue(got.stamps[5] > got.stamps[4], got.stamps.take(10).toString())
    }

    @Test
    fun `monitor output is read frame by frame`() {
        val text = "0D02C01000000000000\r0D1 00 00 00 00 00 00 00 00\rBUFFER FULL\r3600007\rSTOPPED\r\r"
        assertEquals(mapOf(0x0D0 to 1, 0x0D1 to 1, 0x360 to 1), CanMonitor.count(text))
        assertEquals(listOf(0x2C, 0x01, 0, 0, 0, 0, 0, 0), CanMonitor.frames(text)[0].second.map { it.toInt() })
        val data = CanMonitor.dataLines("2C01\rSTOPPED").map { d -> d.map { it.toInt() } }
        assertEquals(listOf(listOf(0x2C, 0x01)), data)
    }

    @Test
    fun `a broadcast needs every id it requires`() {
        val frsOnly = mapOf(0x0D0 to 9, 0x0D1 to 9, 0x140 to 9)
        assertEquals(emptyList(), CanBroadcasts.matching(frsOnly))
        assertEquals(listOf(CanBroadcasts.FrsSteering), CanBroadcasts.matching(frsOnly + (0x360 to 3)))
        val toyota = mapOf(0x025 to 3, 0x0AA to 3, 0x0B4 to 3)
        assertEquals(listOf(CanBroadcasts.ToyotaSteering), CanBroadcasts.matching(toyota))
        // Another make's 0x025 alone is not a Toyota steering angle.
        assertEquals(emptyList(), CanBroadcasts.matching(mapOf(0x025 to 9)))
        assertEquals(emptyList(), CanBroadcasts.matching(toyota + (0x025 to 2)))
    }
}
