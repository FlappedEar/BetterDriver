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
 * Mode 01: RPM (0C) only, and intake air (0F) unasked. [vin] is the `09 02` answer (FBBD-284), none for null.
 * With [stn] it takes STN pass filters (`STFCP`, `STFAP id,mask`) and monitors through them (`STM`),
 * as the vLinker's STN1151 does (FBBD-320). The bus goes quiet at [quietFromMs]: only [quietIds] when
 * given. `21 82` is answered only behind header 7E1 (the FR-S transmission, ATF).
 */
@Suppress("LongParameterList")
private class BusElm(
    val bus: Map<Int, String>,
    val monitor: Boolean = true,
    val repeats: Int = 5,
    val bareCra: Boolean = true,
    val autoReceive: Boolean = true,
    val vin: String? = FrsVin,
    val stn: Boolean = false,
    val quietFromMs: Long = Long.MAX_VALUE,
    val quietIds: Set<Int>? = null,
) : ObdClock {
    var now = 0L
    val sent = ArrayList<String>()
    lateinit var line: ElmLine
    private var headers = false
    private var filter: Int? = null
    private var monitoring = false
    private var stnMonitor = false
    private val passFilters = LinkedHashSet<Int>()
    private var header = 0x7DF

    private val answers = mapOf(
        "ATZ" to "\r\rELM327 v2.2", "ATE0" to "OK", "ATL0" to "OK", "ATS0" to "OK", "ATH0" to "OK",
        "ATH1" to "OK", "ATAT1" to "OK", "ATSP0" to "OK", "ATI" to "ELM327 v2.2", "ATRV" to "12.6V",
        "ATDPN" to "A6", "0100" to "SEARCHING...\r410000100000", "010C" to "410C1AF8",
    )

    @Suppress("CyclomaticComplexMethod") // One branch per command the fake adapter knows.
    fun write(bytes: ByteArray) {
        now += 25
        // Any byte stops the monitor.
        if (monitoring) return stopMonitor()
        val cmd = String(bytes, Charsets.US_ASCII).trimEnd('\r')
        sent += cmd
        if (!track(cmd)) return feed("?")
        when {
            monitor && cmd == "ATMA" -> monitoring = true
            stn && cmd == "STM" -> {
                monitoring = true
                stnMonitor = true
            }
            stn && cmd == "STFCP" -> passFilters.clear().also { feed("OK") }
            stn && cmd.startsWith("STFAP") -> {
                passFilters += cmd.removePrefix("STFAP").trim().substringBefore(',').toInt(16)
                feed("OK")
            }
            cmd.startsWith("ATSH") -> {
                header = cmd.removePrefix("ATSH").toInt(16)
                feed("OK")
            }
            cmd == "2182" -> feed(if (header == 0x7E1) "61825A66" else "NO DATA")
            cmd == "010F" -> feed(if (header == 0x7DF) "410F41" else "NO DATA")
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
        val passes = if (stnMonitor) passFilters.toSet() else null
        stnMonitor = false
        val quiet = now >= quietFromMs
        if (quiet && quietIds == null) return feed("STOPPED")
        val frames = bus.filterKeys { if (passes != null) it in passes else filter == null || it == filter }
            .filterKeys { !(quiet && quietIds?.contains(it) == true) }
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
    val scans = ArrayList<CarScan>()

    override fun scanned(scan: CarScan) {
        scans += scan
    }

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
    /**
     * FR-S frames: steering 0x012C = 300 → -30.0°; brake byte 0x20; pedal 0x33, rpm 0x02EE = 750,
     * throttle 0x19; oil 0x75 and coolant 0x7D, 40 under.
     */
    private val frs = mapOf(
        0x0D0 to "2C01000000000000",
        0x0D1 to "0000200000000000",
        0x140 to "3300EE0200001900",
        0x360 to "0000757D00000000",
        0x7E8 to "03410C1AF8000000",
    )

    private fun run(
        elm: BusElm,
        stopAfterMs: Long = 3_000,
        pollSet: PollSet = PollSet.All,
        profile: CarProfile = CarProfile.Generic,
        scan: Boolean = false,
    ): CanHeard {
        val got = CanHeard()
        ElmPoller(
            line = ElmLine(elm::write).also { elm.line = it },
            profile = profile,
            listener = got,
            running = { elm.now < stopAfterMs },
            clock = elm,
            rateLogMs = 1_000,
            probeMs = 5,
            listenWindowMs = 5,
            maxListenMs = 5,
            pollSet = { pollSet },
            scan = scan,
            scanMs = 5,
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
    fun `on the FR-S the bus replaces polling`() {
        // FBBD-320: everything the bus has comes from it; only the ATF (7E1 21 82) is still asked.
        val elm = BusElm(frs)
        val got = run(elm)
        assertEquals(
            listOf(
                "atf_temp", "steering_angle", "brake_pressure", "brake", "accelerator", "throttle", "rpm",
                "oil_temp", "coolant_temp",
            ),
            got.channels.map { it.id },
        )
        val logs = got.logs.toString()
        assertTrue(got.logs.any { it.startsWith("can_probe ms=5 frames=25 ids=5") && "0D0:5" in it }, logs)
        assertTrue(got.logs.any { it.startsWith("can_ids 0D0:5 0D1:5 140:5 360:5 025:0 0AA:0 0B4:0") }, logs)
        assertTrue("not polled (from CAN): rpm brake accelerator oil_temp" in got.logs, logs)
        fun values(id: String) = got.values.filter { it.first == id }.map { it.second }.distinct()
        assertEquals(listOf(-30.0), values("steering_angle"))
        assertEquals(listOf(40.96), values("brake_pressure"))
        assertEquals(listOf(750.0), values("rpm"))
        assertEquals(listOf(20.0), values("accelerator"))
        assertEquals(listOf(77.0, 85.0), values("oil_temp") + values("coolant_temp"))
        assertTrue(kotlin.math.abs(values("brake").single() - 32 / 0.7) < 1e-9, logs)
        assertTrue(kotlin.math.abs(values("throttle").single() - 25 / 2.55) < 1e-9, logs)
        // ATF through its own header, and back to the broadcast header for nothing else.
        assertEquals(listOf(49.0), values("atf_temp"))
        val atf = elm.sent.indexOf("2182")
        assertEquals("ATSH7E1", elm.sent[atf - 1])
        // Not polled: engine speed, pedal, brake, oil.
        assertTrue(elm.sent.none { it == "010C" || it == "015A" || it == "2141" || it == "2101" }, elm.sent.toString())
        // A plain ELM327 listens to one id after another.
        assertTrue("can_listen one id at a time: STFCP not taken" in got.logs, logs)
        val ids = elm.sent.filter { it.startsWith("ATCRA") && it != "ATCRA" }.takeLast(8).distinct().sorted()
        assertEquals(listOf("ATCRA0D0", "ATCRA0D1", "ATCRA140", "ATCRA360"), ids)
        assertTrue(got.logs.any { it.startsWith("can_hz 0D0=") && it.endsWith("mode=one_id") }, logs)
        // Most of the adapter's time is listening.
        val share = got.logs.last { it.startsWith("can_hz") }.substringAfter("listen_share=").substringBefore(' ')
        assertTrue(share.toDouble() > 0.5, logs)
    }

    @Test
    fun `an STN adapter hears every FR-S id in one monitor`() {
        val elm = BusElm(frs, stn = true)
        val got = run(elm)
        assertTrue("can_listen all ids at once: STFAP + STM" in got.logs, got.logs.toString())
        val i = elm.sent.indexOf("STM")
        assertEquals(
            listOf("STFCP", "STFAP 0D0,7FF", "STFAP 0D1,7FF", "STFAP 140,7FF", "STFAP 360,7FF"),
            elm.sent.subList(i - 5, i),
        )
        assertEquals("STFCP", elm.sent[i + 1])
        val probeEnd = elm.sent.indexOfFirst { it == "STFCP" }
        assertTrue(elm.sent.drop(probeEnd).none { it.startsWith("ATCRA") }, elm.sent.toString())
        assertTrue(got.values.any { it.first == "coolant_temp" } && got.values.any { it.first == "steering_angle" })
        assertTrue(got.logs.any { it.startsWith("can_hz ") && it.endsWith("mode=stn") }, got.logs.toString())
    }

    @Test
    fun `one frame that stops gives its channels back to polling`() {
        // Codex on #328: 0x140 stops while steering goes on; rpm and the pedal must be polled again.
        val elm = BusElm(frs, stn = true, quietIds = setOf(0x140), quietFromMs = 1_500)
        val got = run(elm, stopAfterMs = 8_000)
        val logs = got.logs.toString()
        assertTrue(got.logs.any { it.startsWith("can_listen dropped 140: no frames in 10 listens") }, logs)
        assertTrue("polling again what CAN gave" in got.logs, logs)
        val dropped = elm.sent.lastIndexOf("STFAP 140,7FF")
        assertTrue(elm.sent.drop(dropped).any { it == "010C" }, elm.sent.toString())
        assertTrue(elm.sent.drop(dropped + 1).none { it == "STFAP 140,7FF" }, elm.sent.toString())
        // Steering is still heard after rpm went back to polling.
        val polledRpm = got.values.indexOfFirst { it.first == "rpm" && it.second == 1726.0 }
        assertTrue(polledRpm >= 0 && got.values.indexOfLast { it.first == "steering_angle" } > polledRpm, logs)
    }

    @Test
    fun `when the bus goes quiet the channels it gave are polled again`() {
        val elm = BusElm(frs, quietFromMs = 1_500, stn = true)
        val got = run(elm, stopAfterMs = 6_000)
        val logs = got.logs.toString()
        assertTrue("can_listen off: no frames in 10 listens" in got.logs, logs)
        assertTrue("polling again what CAN gave" in got.logs, logs)
        val quiet = elm.sent.lastIndexOf("STM")
        assertTrue(elm.sent.drop(quiet).any { it == "010C" }, elm.sent.toString())
        val last = got.values.takeLast(3)
        assertTrue(last.any { it.first == "rpm" && it.second == 1726.0 }, last.toString())
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
        assertEquals(CanBroadcasts.Frs, CanBroadcasts.matching(frsOnly + (0x360 to 3)))
        val toyota = mapOf(0x025 to 3, 0x0AA to 3, 0x0B4 to 3)
        assertEquals(listOf(CanBroadcasts.ToyotaSteering), CanBroadcasts.matching(toyota))
        // Another make's 0x025 alone is not a Toyota steering angle.
        assertEquals(emptyList(), CanBroadcasts.matching(mapOf(0x025 to 9)))
        assertEquals(emptyList(), CanBroadcasts.matching(toyota + (0x025 to 2)))
    }

    /** FBBD-344: the Corolla's bus with an id no built-in signal knows. */
    private val corolla = mapOf(
        0x025 to "0FEC000030000000",
        0x0AA to "1A6F1A6F1A6F1A6F",
        0x0B4 to "0000000000000000",
        0x3B7 to "00000000005A0000",
        0x7E8 to "03410C1AF8000000",
    )
    private val corollaVin = "JTDBCMFE0PJ000000"

    @Test
    fun `the driver's own broadcast is listened to on any car`() {
        // FBBD-344: a parameter the driver added is read whatever the VIN; the Toyota steering the
        // app knows stays off on the Corolla (FBBD-284).
        val own = OwnChannels.can("gear", "Gear", 0x3B7, "F / 10.0", "")!!
        val got = run(
            BusElm(corolla, vin = corollaVin),
            profile = CarProfile.Generic.copy(signals = listOf(own)),
            pollSet = PollSet.Driver,
        )
        assertTrue("can_listen on: vin JTDBCMFE own=1" in got.logs, got.logs.toString())
        assertTrue(got.logs.any { it.endsWith("listen custom_gear=3B7") }, got.logs.toString())
        val gear = got.values.filter { it.first == "custom_gear" }.map { it.second }
        assertTrue(gear.size >= 5 && gear.all { kotlin.math.abs(it - 9.0) < 1e-9 }, gear.toString())
        assertTrue(got.values.none { it.first == "steering_angle" }, got.logs.toString())
        assertTrue(got.values.any { it.first == "rpm" })
    }

    @Test
    fun `a slow own broadcast is listened to from one frame`() {
        // A frame sent once a second shows once per monitor: enough for the driver's own (FBBD-344).
        val own = OwnChannels.can("gear", "Gear", 0x3B7, "F / 10.0", "")!!
        val elm = BusElm(corolla, vin = corollaVin, repeats = 1)
        val got = run(elm, profile = CarProfile.Generic.copy(signals = listOf(own)))
        assertTrue(got.logs.any { it.endsWith("listen custom_gear=3B7") }, got.logs.toString())
        assertTrue(got.values.any { it.first == "custom_gear" }, got.logs.toString())
        assertTrue("ATCRA3B7" in elm.sent, elm.sent.toString())
    }

    @Test
    fun `an own broadcast the bus does not carry is not listened to`() {
        val own = OwnChannels.can("missing", "Missing", 0x123, "A", "")!!
        val got = run(BusElm(corolla, vin = corollaVin), profile = CarProfile.Generic.copy(signals = listOf(own)))
        assertTrue(got.logs.any { it.startsWith("can_ids 123:0 listen none") }, got.logs.toString())
        assertTrue(got.values.none { it.first == "custom_missing" })
        assertTrue(got.values.any { it.first == "rpm" })
    }

    @Test
    fun `own parameters on the FR-S come with its profile`() {
        // An own request on the FR-S is polled next to the FR-S profile's ATF, and an own broadcast on
        // an id the app already reads shares its frames.
        val pid = OwnChannels.pid("iat", "Intake", null, 0x01, 0x0F, "A-40", "°C", fast = false)!!
        val can = OwnChannels.can("pedal_raw", "Pedal raw", 0x140, "A", "")!!
        val got = run(
            BusElm(frs),
            profile = CarProfile.Generic.copy(channels = listOf(pid), signals = listOf(can)),
            pollSet = PollSet.Driver,
        )
        val ids = got.channels.map { it.id }
        assertTrue("atf_temp" in ids && "custom_iat" in ids && "custom_pedal_raw" in ids, ids.toString())
        assertTrue(got.values.any { it.first == "custom_iat" && it.second == 25.0 }, got.values.toString())
        assertTrue(got.values.any { it.first == "custom_pedal_raw" && it.second == 51.0 })
        assertTrue(got.values.any { it.first == "steering_angle" })
    }

    @Test
    fun `a scan reports what the car offers without listening on the Corolla`() {
        val elm = BusElm(corolla, vin = corollaVin)
        val got = run(elm, scan = true)
        val scan = got.scans.single()
        assertEquals("A6", scan.protocol)
        assertEquals(setOf(0x0C), scan.supported)
        assertEquals("JTDBCMFE", scan.vinPrefix)
        assertTrue(scan.canHeard)
        assertEquals(setOf(0x025, 0x0AA, 0x0B4, 0x3B7, 0x7E8), scan.canIds.map { it.id }.toSet())
        assertEquals(5, scan.canIds.first { it.id == 0x3B7 }.frames)
        assertEquals("00000000005A0000", scan.canIds.first { it.id == 0x3B7 }.last)
        // Scanned, but the built-in Toyota steering stays off: nothing is listened to after the probe.
        assertTrue(got.values.none { it.first == "steering_angle" }, got.logs.toString())
        assertTrue(elm.sent.none { it.startsWith("ATCRA") && it != "ATCRA" }, elm.sent.toString())
        assertTrue(got.values.any { it.first == "rpm" })
    }

    @Test
    fun `a scan with an adapter that cannot monitor still reports the PIDs`() {
        val got = run(BusElm(frs, monitor = false), scan = true)
        val scan = got.scans.single()
        assertEquals(setOf(0x0C), scan.supported)
        assertTrue(!scan.canHeard && scan.canIds.isEmpty())
        assertTrue(got.values.any { it.first == "rpm" })
    }

    @Test
    fun `without a scan nothing is reported`() {
        assertTrue(run(BusElm(frs)).scans.isEmpty())
    }
}
