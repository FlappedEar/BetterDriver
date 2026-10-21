package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.log.CheckLog
import com.flappedear.betterdriver.recorder.log.HeartRateLog
import com.flappedear.betterdriver.recorder.log.HeartRateLogIds
import com.flappedear.betterdriver.recorder.log.HeartRateWindow
import com.flappedear.betterdriver.recorder.log.LogLine
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The heart-rate monitor checks (FBBD-225): rate, gaps and reconnects of a listed strap, from the
 * stored session and from the continuous log, which must agree.
 */
class HeartRateQualityTest {
    private val second = 1_000_000L
    private val device = RecorderEvent(0, HeartRateLogIds.DEVICE, "address=AA:BB:CC:DD:EE:FF name=Polar H10 1234")

    private fun hr(t: Long, bpm: Int = 100) =
        SampleSet(t, listOf(ChannelValue(HeartRateLogIds.CHANNEL, bpm.toDouble())))

    private fun section(sets: List<SampleSet>, events: List<RecorderEvent>, end: Long): QualitySection =
        assertNotNull(heartRateSection(HeartRateWindow.of(sets, events), events, end))

    private fun QualitySection.metric(id: String) = metrics.single { it.id == id }

    @Test
    fun `no monitor listed, no section`() {
        assertNull(heartRateSection(emptyList(), emptyList(), 60 * second))
    }

    @Test
    fun `a strap sending once a second passes`() {
        val sets = (0 until 600).map { hr(it * second + 300_000, 90 + it % 20) }
        val s = section(sets, listOf(device), 600 * second)
        assertEquals("Heart rate", s.title)
        assertEquals(Verdict.PASS, s.metric("hr.rate").verdict)
        assertEquals(1.0, s.metric("hr.rate").value!!, 1e-9)
        assertEquals(Verdict.PASS, s.metric("hr.gap").verdict)
        assertEquals(1.0, s.metric("hr.gap").value)
        assertEquals(Verdict.NOT_MEASURED, s.metric("hr.reconnect").verdict)
        assertEquals(Verdict.INFO, s.metric("hr.bpm").verdict)
        assertEquals(99.5, s.metric("hr.bpm").value)
    }

    @Test
    fun `a listed strap that never sent anything is not measured, not failed`() {
        // Arek's drives of 21 Oct 2026 08:00 and 08:13: the strap was listed but not worn.
        val s = section(emptyList(), listOf(device), 60 * second)
        assertEquals(Verdict.NOT_MEASURED, s.metric("hr.rate").verdict)
        assertNull(s.metric("hr.rate").value)
        assertEquals(1, s.metrics.size)
    }

    @Test
    fun `a strap sending every other second and an 8 s gap fail`() {
        val sets = (0 until 100).map { hr(it * 2 * second) } + hr(206 * second)
        val s = section(sets, listOf(device), 210 * second)
        assertEquals(Verdict.FAIL, s.metric("hr.rate").verdict)
        assertEquals(Verdict.FAIL, s.metric("hr.gap").verdict)
        assertEquals(8.0, s.metric("hr.gap").value)
    }

    @Test
    fun `a strap that came for the last minute of ten fails the rate`() {
        val sets = (540 until 600).map { hr(it * second) }
        val s = section(sets, listOf(device), 600 * second)
        assertEquals(Verdict.FAIL, s.metric("hr.rate").verdict)
        assertEquals(0.1, s.metric("hr.rate").value!!, 1e-9)
        assertEquals(Verdict.PASS, s.metric("hr.gap").verdict)
    }

    @Test
    fun `a drop that comes back passes and its wait is no gap`() {
        val drop = RecorderEvent(30 * second + 1, HeartRateLogIds.DISCONNECTED, "gatt status 8")
        val sets = (0..30).map { hr(it * second) } + (38..90).map { hr(it * second) }
        val s = section(sets, listOf(device, drop), 90 * second)
        assertEquals(Verdict.PASS, s.metric("hr.reconnect").verdict)
        assertEquals(0.0, s.metric("hr.reconnect").value)
        assertEquals(Verdict.PASS, s.metric("hr.rate").verdict)
        assertEquals(Verdict.PASS, s.metric("hr.gap").verdict)
        assertEquals(listOf(30 * second + 1), s.notes.map { it.tMicros })
        assertEquals("heart-rate monitor link dropped, back after 8.0 s", s.notes.single().text)
    }

    @Test
    fun `a drop that never comes back fails, unless the recording ends with it`() {
        val sets = (0..30).map { hr(it * second) }
        val drop = RecorderEvent(30 * second + 1, HeartRateLogIds.DISCONNECTED, "gatt status 8")
        val lost = section(sets, listOf(device, drop), 300 * second)
        assertEquals(Verdict.FAIL, lost.metric("hr.reconnect").verdict)
        assertEquals(1.0, lost.metric("hr.reconnect").value)
        val ending = section(sets, listOf(device, drop), 40 * second)
        assertEquals(Verdict.NOT_MEASURED, ending.metric("hr.reconnect").verdict)
    }

    @Test
    fun `a lost skin contact is no gap`() {
        val lost = RecorderEvent(20 * second + 1, HeartRateLogIds.CONTACT, HeartRateLogIds.CONTACT_LOST)
        val sets = (0..20).map { hr(it * second) } + (40..60).map { hr(it * second) }
        val s = section(sets, listOf(device, lost), 60 * second)
        assertEquals(Verdict.PASS, s.metric("hr.gap").verdict)
    }

    @Test
    fun `the drive checks from the log grade the strap as the stored session does`() {
        val start = 1_760_000_000_000L
        val id = "20261020-120000-abcdef12"
        val drop = RecorderEvent(30 * second + 1, HeartRateLogIds.DISCONNECTED, "gatt status 8")
        val sets = (0..30).map { hr(it * second) } + (38..90).map { hr(it * second) }
        val lines = mutableListOf<LogLine>()
        fun line(t: Long, tag: String, message: String) {
            lines += LogLine(start + t / 1_000, t, "recorder", 1, "t", 'I', tag, id, message)
        }
        line(0, CheckLog.SESSION, "start utc_ms=$start source=RaceBox_Micro")
        val log = HeartRateLog(id) { msg ->
            line(CheckLog.fields(msg).getValue("last_us").toLong(), CheckLog.HEART_RATE, msg)
        }
        for (e in listOf(device, drop)) line(e.tMicros, CheckLog.EVENT, CheckLog.eventText(e.tMicros, e.kind, e.text))
        var dropped = false
        sets.forEach { s ->
            if (s.tMicros > drop.tMicros && !dropped) log.onEvent(drop).also { dropped = true }
            log.onSamples(s)
        }
        log.onStatus(RecorderStatus.Stopped("user"))
        line(90 * second, CheckLog.SESSION, "stop t_us=${90 * second} reason=user")

        val fromLog = driveChecks(lines.sortedBy { it.utcMillis }).single().sections.single { it.title == "Heart rate" }
        val stored = section(sets, listOf(device, drop), 90 * second)
        assertEquals(stored, fromLog)
        assertEquals(Verdict.PASS, fromLog.metric("hr.reconnect").verdict)
    }
}
