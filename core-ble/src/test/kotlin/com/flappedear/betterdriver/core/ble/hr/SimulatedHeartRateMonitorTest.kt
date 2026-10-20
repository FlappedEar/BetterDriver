@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.hr

import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.NotifyLink
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.log.HeartRateLog
import com.flappedear.betterdriver.recorder.log.HeartRateWindow
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A simulated Bluetooth LE heart-rate strap through the whole recording path (FBBD-225): the strap's
 * own Heart Rate Measurement bytes go through [HeartRateSource] into a real recording, with a link
 * drop, a reconnect and a lost skin contact on the way. Checks what the session stores, the VBO
 * export and the continuous log's heart rate lines, which the drive checks grade.
 */
class SimulatedHeartRateMonitorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The strap's side of the link: the test calls [notify] and [state] as Android's GATT callbacks would. */
    private class Strap : NotifyLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Idle)
        lateinit var onData: (ByteArray) -> Unit
        lateinit var onState: (LinkState) -> Unit
        var started = 0
        var stopped = 0

        override fun start() {
            started++
        }

        override fun stop() {
            stopped++
        }

        fun state(s: LinkState) {
            state.value = s
            onState(s)
        }

        /** A Heart Rate Measurement: uint8 bpm with skin contact supported and [contact], one RR-Interval. */
        fun notify(bpm: Int, contact: Boolean = true) {
            val flags = (if (contact) 0x06 else 0x04) or 0x10
            val rr = 60_000 * 1024 / bpm / 1000
            onData(byteArrayOf(flags.toByte(), bpm.toByte(), (rr and 0xFF).toByte(), (rr shr 8).toByte()))
        }
    }

    private val nanos = AtomicLong(0)
    private val gnss = FakeGnss()
    private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun windows() = synchronized(lines) { lines.mapNotNull { HeartRateWindow.parse(it) } }

    @Test
    fun `a simulated strap records heart rate, reconnects and logs what the drive checks grade`() {
        val store = SessionStore(tmp.newFolder())
        val rec = Recorder(store, nanoTime = nanos::get).start(
            SessionInfo(gnss.startUtcMillis, devices = listOf("fake-gnss"), channels = GnssChannels.ALL),
        )
        rec.listeners.add(HeartRateLog(rec.id) { lines += it })
        val strap = Strap()
        val source = HeartRateSource("AA:BB:CC:DD:EE:FF", "Polar H10 1234") { onData, onState ->
            strap.onData = onData
            strap.onState = onState
            strap
        }
        assertTrue(rec.attach(source))
        assertEquals(1, strap.started)
        assertEquals(source, HeartRateSource.current)
        strap.state(LinkState.Connecting)
        assertEquals(HeartRateStatus(HeartRateLink.CONNECTING, null), source.status())
        strap.state(LinkState.Streaming(1))

        var fix = 0L
        var expected = 0
        fun second(bpm: Int?, contact: Boolean = true) {
            // 25 Hz GNSS, so the VBO has rows to put the heart rate on.
            repeat(25) {
                nanos.addAndGet(40_000_000)
                rec.submit(SampleSet(rec.nowMicros(), gnss.fix(fix++)))
            }
            if (bpm != null) {
                strap.notify(bpm, contact)
                if (contact) expected++
            }
        }
        // Streaming, but no value yet: the status strip shows no heart rate (FBBD-268).
        assertEquals(HeartRateStatus(HeartRateLink.STREAMING, null), source.status())
        (0 until 60).forEach { second(90 + it % 10) }
        assertEquals(HeartRateStatus(HeartRateLink.STREAMING, 99), source.status())
        // The strap goes out of range for 4 s, then the link comes back on its own.
        strap.state(LinkState.Reconnecting(1, 1_000, "gatt status 8"))
        assertEquals(HeartRateStatus(HeartRateLink.LOST, null), source.status())
        repeat(4) { second(null) }
        strap.state(LinkState.Streaming(1))
        (0 until 30).forEach { second(120) }
        // The driver adjusts the strap: 3 s without skin contact, no heart rate is stored for them.
        repeat(3) { second(80, contact = false) }
        assertEquals(HeartRateStatus(HeartRateLink.NO_CONTACT, null), source.status())
        (0 until 30).forEach { second(130) }
        assertEquals(HeartRateStatus(HeartRateLink.STREAMING, 130), source.status())
        // A strap that goes quiet without the link noticing: after 5 s its last value is not current.
        repeat(6) { second(null) }
        assertEquals(HeartRateStatus(HeartRateLink.STREAMING, null), source.status())
        assertTrue(rec.stop())
        assertEquals(null, source.status())
        assertEquals(null, HeartRateSource.current)
        assertEquals(1, strap.stopped)

        val data = store.read(rec.id)
        val bpm = data.sampleSets.mapNotNull { s ->
            s.values.firstOrNull { it.channelId == HeartRateChannels.HEART_RATE.id }?.value
        }
        assertEquals(expected, bpm.size)
        assertEquals(90.0, bpm.first(), 0.0)
        assertEquals(130.0, bpm.last(), 0.0)
        val kinds = data.events.map { it.kind }
        assertEquals(1, kinds.count { it == HeartRateEvents.DEVICE })
        assertEquals(2, kinds.count { it == HeartRateEvents.CONNECTED })
        assertEquals(1, kinds.count { it == HeartRateEvents.DISCONNECTED })
        val contact = data.events.filter { it.kind == HeartRateEvents.CONTACT }.map { it.text }
        assertEquals(listOf("lost", "detected"), contact)
        assertTrue(data.events.first { it.kind == HeartRateEvents.DEVICE }.text.endsWith("name=Polar H10 1234"))

        val vbo = ByteArrayOutputStream().also { VboExporter.export(data, it) }.toString(Charsets.UTF_8)
        val columns = vbo.substringAfter("[column names]").trim().lineSequence().first().split(' ')
        assertTrue(columns.toString(), "heart_rate" in columns)

        // The log's windows: written by the listener thread, so wait for the last one.
        val deadline = System.currentTimeMillis() + 5_000
        while (windows().sumOf { it.samples } < expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val logged = windows()
        assertEquals(expected, logged.sumOf { it.samples })
        // Waits across the drop and the lost contact are not gaps: every other wait is the strap's 1 s.
        assertEquals(1_000_000L, logged.mapNotNull { it.maxGapMicros }.max())
        val drop = data.events.first { it.kind == HeartRateEvents.DISCONNECTED }.tMicros
        assertEquals(listOf(drop + 5_000_000L), logged.mapNotNull { it.backMicros })
        assertEquals(90, logged.minOf { it.bpmMin })
        assertEquals(130, logged.maxOf { it.bpmMax })
        // The stored session gives the same windows as the live log.
        assertEquals(logged, HeartRateWindow.of(data.sampleSets, data.events))
    }
}
