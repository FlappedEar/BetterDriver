package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.RecorderEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The OBD-II checks (FBBD-271): mid-drive link drops, how long car data was gone, and whether it came
 * back; the engine switched off at a stop is no drop.
 */
class ObdQualityTest {
    private val second = 1_000_000L

    private fun connected(t: Long) =
        RecorderEvent(t * second, ObdConnectedKind, "host=192.168.0.10:35000 transport=Wi-Fi")

    private fun ended(t: Long, reason: String) = RecorderEvent(t * second, ObdDisconnectedKind, "reason=$reason")

    private fun section(events: List<RecorderEvent>, end: Long) = assertNotNull(obdSection(events, end * second))

    private fun QualitySection.metric(id: String) = metrics.single { it.id == id }

    @Test
    fun `no adapter connected, no section`() {
        assertNull(obdSection(listOf(ended(10, "connection lost: closed")), 60 * second))
    }

    @Test
    fun `a session without drops has nothing to judge`() {
        val s = section(listOf(connected(2), ended(600, "stopped")), 600)
        assertEquals(0.0, s.metric("obd.drops").value)
        assertEquals(Verdict.NOT_MEASURED, s.metric("obd.not_back").verdict)
        assertEquals(0.0, s.metric("obd.time_without").value)
    }

    @Test
    fun `the 20 Oct drop, never back for the last 4 min, fails`() {
        // Connected at 0, lost at 1332 s (15:44:22), the driver stopped at 1572 s.
        val abort = "connection lost: read failed: Software caused connection abort"
        val s = section(listOf(connected(0), ended(1332, abort)), 1572)
        assertEquals(1.0, s.metric("obd.drops").value)
        assertEquals(240.0, s.metric("obd.longest_gap").value)
        assertEquals(Verdict.FAIL, s.metric("obd.not_back").verdict)
        assertEquals("OBD link dropped ($abort), never back", s.notes.single().text)
    }

    @Test
    fun `a drop that came back passes, with its gap`() {
        val s = section(
            listOf(
                connected(0),
                ended(300, "connection lost: GATT status 8 (link lost)"),
                connected(306),
                ended(900, "stopped"),
            ),
            900,
        )
        assertEquals(Verdict.PASS, s.metric("obd.not_back").verdict)
        assertEquals(6.0, s.metric("obd.longest_gap").value)
        assertEquals(6.0, s.metric("obd.time_without").value)
    }

    @Test
    fun `the engine off at a stop is no drop, but counts as time without OBD`() {
        val s = section(listOf(connected(0), ended(300, "car stopped answering"), connected(420)), 900)
        assertEquals(0.0, s.metric("obd.drops").value)
        assertEquals(Verdict.NOT_MEASURED, s.metric("obd.not_back").verdict)
        assertEquals(120.0, s.metric("obd.time_without").value)
    }

    @Test
    fun `a drop just before the end of the session is the drive ending`() {
        val s = section(listOf(connected(0), ended(890, "adapter stopped answering")), 900)
        assertEquals(0.0, s.metric("obd.drops").value)
        assertEquals("OBD link dropped (adapter stopped answering) as the recording ended", s.notes.single().text)
    }

    @Test
    fun `failed setup attempts after a stop are no drops`() {
        // Engine off at 300 s; retries open the adapter but lose it in setup, then the car answers at 420 s.
        val s = section(
            listOf(
                ended(5, "connection lost: read failed: before the car answered"),
                connected(10),
                ended(300, "car stopped answering"),
                ended(320, "connection lost: read failed: Connection reset"),
                ended(340, "adapter stopped answering"),
                connected(420),
            ),
            900,
        )
        assertEquals(0.0, s.metric("obd.drops").value)
        assertEquals(Verdict.NOT_MEASURED, s.metric("obd.not_back").verdict)
        assertEquals(120.0, s.metric("obd.time_without").value)
    }
}
