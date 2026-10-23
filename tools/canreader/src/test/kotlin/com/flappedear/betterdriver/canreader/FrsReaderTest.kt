package com.flappedear.betterdriver.canreader

import com.flappedear.betterdriver.obd.ElmLine
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader against a simulated FR-S and vLinker (FBBD-319): what goes in the log, and that it
 * only ever sends read requests.
 */
class FrsReaderTest {
    /** A gen 1 FR-S behind an STN adapter, 11-bit CAN, headers on, spaces off. */
    private class FakeFrs(private val clock: FakeClock) {
        lateinit var line: ElmLine
        lateinit var tap: FrameTap
        val sent = ArrayList<String>()
        private var header = "7DF"
        private var filter: String? = null
        private var monitoring = false

        fun write(bytes: ByteArray) {
            val text = String(bytes, Charsets.US_ASCII)
            if (monitoring) {
                monitoring = false
                feed(">")
                return
            }
            val cmd = text.trim()
            sent += cmd
            clock.now += 20
            when {
                cmd == "ATMA" -> monitor()
                cmd.startsWith("ATSH") -> header = cmd.removePrefix("ATSH").also { feed("OK\r\r>") }
                cmd.startsWith("ATCRA") -> filter = cmd.removePrefix("ATCRA").ifEmpty { null }.also { feed("OK\r\r>") }
                cmd == "ATDPN" -> feed("A6\r\r>")
                cmd == "ATI" -> feed("ELM327 v2.2\r\r>")
                cmd == "STI" -> feed("STN1151 v4.6.1\r\r>")
                cmd == "ATRV" -> feed("14.1V\r\r>")
                cmd.startsWith("AT") || cmd.startsWith("ST") -> feed("OK\r\r>")
                else -> feed(answer(cmd) + "\r\r>")
            }
        }

        private fun answer(cmd: String): String = when ("$header:$cmd") {
            "7DF:0100" -> "7E8064100BE3EB813"
            "7DF:0120" -> "7E8064120A007B011"
            "7DF:0140" -> "7E8064140FED08000"
            "7DF:010C" -> "7E804410C1AF8"
            "7DF:0105" -> "7E803410587"
            "7DF:0902" -> "7E8101449020" + "14A4631\r7E8215A4E41413139" + "44\r7E82230303030303030"
            "7DF:03" -> "7E80443010133"
            "7B0:2141" -> "7B803614128"
            "7E0:2105" -> "7E8056105AABBCC"
            "7B0:2105" -> "7B803 7F2112".replace(" ", "")
            else -> "NO DATA"
        }

        private fun monitor() {
            monitoring = true
            val frames = listOf("0D0" to "6400000000000000", "0D1" to "0000120000000000", "360" to "0000785A00000000")
            repeat(3) { i ->
                frames.filter { filter == null || it.first == filter }.forEach { (id, data) ->
                    clock.now += 5
                    feed("$id${data.take(6)}${"%02X".format(i)}${data.drop(8)}\r")
                }
            }
        }

        private fun feed(s: String) {
            val bytes = s.toByteArray(Charsets.US_ASCII)
            line.feed(bytes)
            tap.feed(bytes)
        }
    }

    private class FakeClock {
        var now = 1_000L
    }

    private fun run(drive: Boolean = false): Pair<String, FakeFrs> {
        val clock = FakeClock()
        val frs = FakeFrs(clock)
        val out = StringWriter()
        val log = ReaderLog(out) { clock.now }
        frs.line = ElmLine(write = frs::write)
        frs.tap = FrameTap { clock.now }
        var left = if (drive) 8 else Int.MAX_VALUE
        val config = ReaderConfig(
            pidReads = 3, surveyMs = 50, listenMs = 30, sweepHeaders = listOf(0x7E0, 0x7B0), driveListenMs = 20,
        )
        val reader = FrsReader(frs.line, frs.tap, log, { clock.now }, { if (drive) left-- > 0 else true }, config)
        if (drive) reader.drive() else reader.checks()
        return out.toString() to frs
    }

    @Test
    fun `checks log the PIDs, the wish list, the bus and the sweep`() {
        val (log, _) = run()
        assertTrue(log, log.contains("info protocol A6 (11-bit CAN)"))
        assertTrue(log, log.contains("info VIN JF1ZNAA19D0****** gen1 FR-S/86/BRZ: true"))
        assertTrue(log, Regex("""pid 7DF 010C ok=3/3 hz=[0-9.]+ .* rpm=1726\.00rpm""").containsMatchIn(log))
        assertTrue(log, Regex("""diag 7B0 2141 ok=3/3 .* brake=33\.00%""").containsMatchIn(log))
        assertTrue(log, log.contains("dtc mode 03: 7E8:P0133"))
        assertTrue(log, log.contains("summary CAN survey: 3 ids: 0D0 0D1 360"))
        assertTrue(log, Regex("""listen 0D0 n=3 hz=[0-9.]+ len=8 b3:3vals 00\.\.02""").containsMatchIn(log))
        assertTrue(log, Regex("""frame \d+ 0D1 000012""").containsMatchIn(log))
        assertTrue(log, log.contains("sweep 7E0 2105 -> 7E8: 61 05 AA BB CC"))
        assertTrue(log, log.contains("summary sweep 7B0: 1 answered, 1 refused"))
    }

    @Test
    fun `nothing but reads is sent`() {
        val (_, frs) = run()
        val requests = frs.sent.filterNot { it.startsWith("AT") || it.startsWith("ST") }
        val modes = requests.map { it.take(2) }.toSet()
        assertEquals(setOf("01", "03", "07", "0A", "09", "21"), modes)
        assertFalse(frs.sent.any { it == "04" || it.startsWith("10") || it.startsWith("2E") || it.startsWith("3B") })
    }

    @Test
    fun `the drive log listens to the wish list ids and reads the brake`() {
        val (log, _) = run(drive = true)
        assertTrue(log, Regex("""frame \d+ 0D0 """).containsMatchIn(log))
        assertTrue(log, log.contains("value brake=33.00 raw=7B8: 61 41 28"))
        assertTrue(log, log.contains("step drive log stopped"))
    }

    @Test
    fun `trouble codes`() {
        assertEquals(listOf("P0133"), Dtc.codes(byteArrayOf(0x43, 0x01, 0x01, 0x33)))
        val nonCan = byteArrayOf(0x43, 0x43, 0x00, 0xC1.toByte(), 0x00, 0x00, 0x00)
        assertEquals(listOf("C0300", "U0100"), Dtc.codes(nonCan))
    }
}
