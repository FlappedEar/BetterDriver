@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FBBD-15: the ELM327 reader against a simulated adapter. [answers] maps a command to what the
 * adapter prints before its prompt; a command not in it gets `?`, and [silent] commands get nothing.
 * Each command takes [latencyMs] on the fake clock.
 */
private class FakeElm(
    val answers: MutableMap<String, String>,
    val silent: Set<String> = emptySet(),
    val latencyMs: Long = 25,
) {
    var nowMs = 0L
    val sent = ArrayList<String>()
    lateinit var line: ElmLine

    fun write(bytes: ByteArray) {
        val cmd = String(bytes, Charsets.US_ASCII).trimEnd('\r')
        sent += cmd
        nowMs += latencyMs
        if (cmd in silent) return
        line.feed(((answers[cmd] ?: "?") + "\r\r>").toByteArray(Charsets.US_ASCII))
    }
}

private class Recorded : ObdListener {
    var supported: Set<Int> = emptySet()
    var channels: List<PidChannel> = emptyList()
    var adapter = ""
    val values = ArrayList<Pair<String, Double>>()
    val logs = ArrayList<String>()

    override fun ready(adapter: String, protocol: String, supported: Set<Int>, channels: List<PidChannel>) {
        this.adapter = adapter
        this.supported = supported
        this.channels = channels
    }

    override fun value(channel: PidChannel, value: Double, atMs: Long) {
        values += channel.id to value
    }

    override fun log(text: String) {
        logs += text
    }
}

class ElmPollerTest {
    /** A car that has RPM (0C), speed (0D), coolant (05) and throttle (11) in its 01 00 bitmask. */
    private fun car(): MutableMap<String, String> = mutableMapOf(
        "ATZ" to "\r\rELM327 v1.5",
        "ATE0" to "ATE0\rOK",
        "ATL0" to "OK",
        "ATS0" to "OK",
        "ATH0" to "OK",
        "ATAT1" to "OK",
        "ATSP0" to "OK",
        "ATI" to "ELM327 v1.5",
        "ATRV" to "12.6V",
        "ATDPN" to "A6",
        // Bits for 05, 0C, 0D and 11; bit 0x20 off, so 01 20 is not asked.
        "0100" to "SEARCHING...\r410008188000",
        "010C" to "410C1AF8",
        "010D" to "410D32",
        "0105" to "41055A",
        "0111" to "41117F",
    )

    private fun poller(elm: FakeElm, listener: ObdListener, stopAfter: Int) = ElmPoller(
        line = ElmLine(elm::write).also { elm.line = it },
        profile = CarProfile.Generic,
        listener = listener,
        running = { elm.sent.size < stopAfter },
        clockMs = { elm.nowMs },
        sleepMs = { elm.nowMs += it },
        rateLogMs = 1_000,
    )

    @Test
    fun `sets up, discovers the car's PIDs and polls them`() {
        val elm = FakeElm(car())
        val got = Recorded()
        assertEquals("stopped", poller(elm, got, stopAfter = 200).run())
        assertEquals("ELM327 v1.5", got.adapter)
        assertEquals(setOf(0x05, 0x0C, 0x0D, 0x11), got.supported)
        assertEquals(listOf("coolant_temp", "rpm", "speed", "throttle"), got.channels.map { it.id })
        assertTrue(Pair("rpm", 1726.0) in got.values)
        assertTrue(Pair("speed", 50.0) in got.values)
        assertTrue(Pair("coolant_temp", 50.0) in got.values)
        // Fast channels get most of the adapter; coolant is capped at 2 Hz.
        val rpm = got.values.count { it.first == "rpm" }
        val coolant = got.values.count { it.first == "coolant_temp" }
        assertTrue(rpm > 3 * coolant, "rpm $rpm coolant $coolant")
        assertTrue(got.logs.any { it.startsWith("rates_hz ") && "rpm=" in it }, got.logs.toString())
    }

    @Test
    fun `a clone that rejects setup commands is still used`() {
        val answers = car()
        listOf("ATL0", "ATAT1").forEach { answers.remove(it) }
        val got = Recorded()
        poller(FakeElm(answers), got, stopAfter = 60).run()
        assertTrue(got.values.isNotEmpty())
        assertTrue("ATAT1 not supported" in got.logs)
    }

    @Test
    fun `ignition off: the car does not answer the bitmask`() {
        val answers = car()
        answers["0100"] = "SEARCHING...\rUNABLE TO CONNECT"
        val got = Recorded()
        assertEquals("car not answering", poller(FakeElm(answers), got, stopAfter = 100).run())
        assertTrue(got.channels.isEmpty())
    }

    @Test
    fun `the car stops answering every PID`() {
        val answers = car()
        val elm = FakeElm(answers)
        val got = Recorded()
        val poller = poller(elm, got, stopAfter = 1_000)
        // After discovery every PID gets NO DATA, as when the engine is switched off.
        val pids = listOf("010C", "010D", "0105", "0111")
        pids.forEach { answers[it] = "NO DATA" }
        assertEquals("car stopped answering", poller.run())
    }

    @Test
    fun `an adapter that goes quiet ends the connection`() {
        val elm = FakeElm(car(), silent = setOf("010C", "010D", "0105", "0111"), latencyMs = 0)
        val got = Recorded()
        val line = ElmLine(elm::write).also { elm.line = it }
        val poller = ElmPoller(line, CarProfile.Generic, got, { true }, { elm.nowMs }, { elm.nowMs += it })
        assertEquals("adapter stopped answering", poller.run())
    }

    @Test
    fun `an adapter that never answers`() {
        val elm = FakeElm(car(), silent = setOf("ATZ") + ElmPoller.SETUP, latencyMs = 0)
        val got = Recorded()
        val line = ElmLine(elm::write).also { elm.line = it }
        assertEquals("adapter not answering", ElmPoller(line, CarProfile.Generic, got, { true }, { 0 }, {}).run())
    }

    @Test
    fun `a closed line throws`() {
        val line = ElmLine {}
        line.close("socket closed")
        assertFailsWith<IOException> { line.command("ATZ", 10) }
    }

    @Test
    fun `stale bytes from a timed-out command are dropped`() {
        lateinit var line: ElmLine
        var first = true
        line = ElmLine {
            if (first) {
                first = false
            } else {
                line.feed("410D32\r\r>".toByteArray())
            }
        }
        assertNull(line.command("010C", 1))
        line.feed("410C1A".toByteArray()) // The late answer to 010C, with no prompt yet.
        assertEquals("410D32\r\r", line.command("010D", 1_000))
    }
}
