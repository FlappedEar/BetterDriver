package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.log.CheckLog
import com.flappedear.betterdriver.recorder.log.LogLine
import com.flappedear.betterdriver.recorder.log.ReachLog
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings › Equipment's In range / Not seen against the recording that follows (FBBD-280), from
 * the continuous log alone: the page's [ReachLog] lines, then the recorder's connection lines.
 */
class ReachQualityTest {
    private val start = 1_760_000_000_000L
    private val id = "20261021-081338-9b1e766b"
    private val raceBox = "AA:BB:CC:DD:EE:01"
    private val strap = "AA:BB:CC:DD:EE:02"
    private val obd = "AA:BB:CC:DD:EE:03"
    private val lines = mutableListOf<LogLine>()

    /** A line [atMs] after the recording's start (negative: before it). */
    private fun line(atMs: Long, tag: String, message: String, session: String? = null, process: String = "main") {
        lines += LogLine(start + atMs, null, process, 1, "t", 'I', tag, session, message)
    }

    private fun page(atMs: Long, message: String) = line(atMs, ReachLog.TAG, message)

    private fun shown(atMs: Long, item: String, model: String, address: String?, reach: String?) =
        page(atMs, ReachLog.item(item, model, address, reach))

    private fun link(atMs: Long, tag: String, message: String) = line(atMs, tag, message, id, "recorder")

    private fun recording() {
        line(0, CheckLog.SESSION, "start utc_ms=$start source=RaceBox_Micro", id, "recorder")
        line(18 * 60_000L, CheckLog.SESSION, "stop t_us=${18 * 60_000_000L} reason=user", id, "recorder")
    }

    private fun section() = driveChecks(lines).single().sections.firstOrNull { it.title == "Equipment" }

    @Test
    fun `items shown rightly agree with the recording`() {
        page(-90_000, ReachLog.look(true, 4))
        shown(-90_000, "rb", "RACEBOX", raceBox, "NotSeen")
        shown(-90_000, "hr", "HEART_RATE", strap, "NotSeen")
        shown(-90_000, "obd", "OBD_BLUETOOTH", obd, "NotSeen")
        shown(-90_000, "cam", "PHONE_CAMERA", null, "BuiltIn")
        shown(-88_500, "rb", "RACEBOX", raceBox, "InRange")
        shown(-88_000, "obd", "OBD_BLUETOOTH", obd, "InRange")
        page(-30_000, ReachLog.look(false, 0))
        recording()
        link(40, "Link", "$raceBox Connecting")
        link(2_600, "Link", "$raceBox connected in 2564 ms (direct connection, MTU asked for)")
        link(45_000, "Link", "$raceBox Streaming(hz=25)")
        link(50, "Link", "$strap Connecting")
        link(15_000, "Link", "$strap Reconnecting(attempt=1, delayMs=1000, reason=no data after connecting)")
        link(3_000, "Obd", "recorder: connected to $obd over Bluetooth LE")

        val metric = section()!!.metrics.single()
        assertEquals("equipment.reach", metric.id)
        assertEquals(Verdict.PASS, metric.verdict)
        assertEquals(0.0, metric.value)
        assertEquals(listOf("FBBD-280"), metric.keys)
        assertTrue(
            "RACEBOX $raceBox shown In range 30.0 s before Start, connected 2.6 s after it: agrees" in metric.how,
        )
        assertTrue("HEART_RATE $strap shown Not seen 30.0 s before Start, never connected: agrees" in metric.how)
        assertTrue("OBD_BLUETOOTH $obd shown In range 30.0 s before Start, connected 3.0 s after it" in metric.how)
        assertTrue("of 3:" in metric.how, metric.how)
    }

    @Test
    fun `In range for a device the recording never reached, and Not seen for one it reached at once, are wrong`() {
        page(-20_000, ReachLog.look(true, 2))
        shown(-20_000, "rb", "RACEBOX", raceBox, "NotSeen")
        shown(-20_000, "hr", "HEART_RATE", strap, "InRange")
        page(-10_000, ReachLog.look(false, 0))
        recording()
        link(2_000, "Link", "$raceBox connected in 1900 ms (direct connection, MTU asked for)")
        link(50, "Link", "$strap Connecting")
        link(15_000, "Link", "$strap Reconnecting(attempt=1, delayMs=1000, reason=no data after connecting)")

        val metric = section()!!.metrics.single()
        assertEquals(Verdict.FAIL, metric.verdict)
        assertEquals(2.0, metric.value)
        assertTrue("RACEBOX $raceBox shown Not seen 10.0 s before Start, connected 2.0 s after it: WRONG" in metric.how)
        assertTrue("HEART_RATE $strap shown In range 10.0 s before Start, never connected: WRONG" in metric.how)
    }

    @Test
    fun `a page closed too long before Start, or items the recording did not use, are not judged`() {
        page(-10 * 60_000L, ReachLog.look(true, 2))
        shown(-10 * 60_000L, "rb", "RACEBOX", raceBox, "InRange")
        // Not seen 2 min before Start: the device may have been switched on since.
        page(-3 * 60_000L, ReachLog.look(true, 2))
        shown(-3 * 60_000L, "hr", "HEART_RATE", strap, "NotSeen")
        page(-2 * 60_000L, ReachLog.look(false, 0))
        recording()
        link(2_000, "Link", "$strap connected in 900 ms (direct connection, MTU asked for)")

        val metric = section()!!.metrics.single()
        assertEquals(Verdict.NOT_MEASURED, metric.verdict)
    }

    @Test
    fun `no Equipment section when the page showed nothing before the recording`() {
        recording()
        link(2_000, "Link", "$raceBox connected in 1900 ms (direct connection, MTU asked for)")
        assertNull(section())
    }

    @Test
    fun `a page still open when the recording starts shows its last state up to Start`() {
        page(-5_000, ReachLog.look(true, 1))
        shown(-5_000, "rb", "RACEBOX", raceBox, "InRange")
        recording()
        // The page then shows the session's items: the recording started, it stopped looking.
        page(10, ReachLog.look(false, 0))
        shown(10, "rb", "RACEBOX", raceBox, "InSession")
        link(2_000, "Link", "$raceBox connected in 1900 ms (direct connection, MTU asked for)")

        val metric = section()!!.metrics.single()
        assertEquals(Verdict.PASS, metric.verdict)
        assertTrue("shown In range 0.0 s before Start" in metric.how, metric.how)
    }
}
