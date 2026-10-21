@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.hr

import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.NotifyLink
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.RecorderConfig
import com.flappedear.betterdriver.recorder.Recording
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
        // The test feeds two minutes in a burst, far faster than real time. The listener queue drops its
        // oldest updates when the listener falls behind (by design, FBBD-69), so a slow CI runner lost
        // strap values from the log; make room for all of them.
        val config = RecorderConfig(listenerQueueCapacity = 10_000)
        val rec = Recorder(store, config, nanoTime = nanos::get).start(
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
        strap.state(LinkState.Connecting)
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
        (0 until 60).forEach { second(90 + it % 10) }
        // The strap goes out of range for 4 s, then the link comes back on its own.
        strap.state(LinkState.Reconnecting(1, 1_000, "gatt status 8"))
        repeat(4) { second(null) }
        strap.state(LinkState.Streaming(1))
        (0 until 30).forEach { second(120) }
        // The driver adjusts the strap: 3 s without skin contact, no heart rate is stored for them.
        repeat(3) { second(80, contact = false) }
        (0 until 30).forEach { second(130) }
        assertTrue(rec.stop())
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

    @Test
    fun `the status strip gets the strap's link and only a fresh heart rate`() {
        val rec = Recorder(SessionStore(tmp.newFolder()), nanoTime = nanos::get).start(
            SessionInfo(gnss.startUtcMillis, devices = listOf("fake-gnss"), channels = GnssChannels.ALL),
        )
        val strap = Strap()
        val source = HeartRateSource("AA:BB:CC:DD:EE:FF", "Polar H10 1234") { onData, onState ->
            strap.onData = onData
            strap.onState = onState
            strap
        }
        fun status() = source.status()
        fun wait(seconds: Int) = nanos.addAndGet(seconds * 1_000_000_000L)
        assertEquals(null, status())
        assertTrue(rec.attach(source))
        assertEquals(source, HeartRateSource.current)
        strap.state(LinkState.Connecting)
        assertEquals(HeartRateStatus(HeartRateLink.CONNECTING, null), status())
        strap.state(LinkState.Streaming(1))
        assertEquals(HeartRateStatus(HeartRateLink.STREAMING, null), status())
        strap.notify(142)
        assertEquals(HeartRateStatus(HeartRateLink.STREAMING, 142), status())
        strap.notify(80, contact = false)
        assertEquals(HeartRateStatus(HeartRateLink.NO_CONTACT, null), status())
        strap.notify(143)
        // A strap that goes quiet while the link still looks up: after 5 s its last value is not current.
        wait(6)
        assertEquals(HeartRateStatus(HeartRateLink.STREAMING, null), status())
        strap.state(LinkState.Reconnecting(1, 1_000, "gatt status 8"))
        assertEquals(HeartRateStatus(HeartRateLink.LOST, null), status())
        assertTrue(rec.stop())
        assertEquals(null, status())
        assertEquals(null, HeartRateSource.current)
    }

    private fun session(): Pair<Recording, SessionStore> {
        val store = SessionStore(tmp.newFolder())
        val rec = Recorder(store, nanoTime = nanos::get).start(
            SessionInfo(gnss.startUtcMillis, devices = listOf("fake-gnss"), channels = GnssChannels.ALL),
        )
        return rec to store
    }

    private fun attach(rec: Recording): Pair<Strap, HeartRateSource> {
        val strap = Strap()
        val source = HeartRateSource("CB:EE:3B:68:BA:55", "8054-1") { onData, onState ->
            strap.onData = onData
            strap.onState = onState
            strap
        }
        assertTrue(rec.attach(source))
        return strap to source
    }

    private val noAnswer = "no data after connecting: no answer to the connection in 15028 ms"

    private fun seconds(s: Int) = nanos.addAndGet(s * 1_000_000_000L)

    @Test
    fun `a strap not found at the session start is off for the whole session`() {
        // Arek's drives of 21 Oct 2026 15:40 and 15:50: the strap was not worn, every setup timed out
        // (15 s, 15 s, 15 s), then the link waited in the background for the rest of the session.
        // FBBD-285: off after the first 45 s instead, so a strap that wakes later is never used.
        val (rec, store) = session()
        val (strap, source) = attach(rec)
        strap.state(LinkState.Connecting)
        seconds(15)
        strap.state(LinkState.Reconnecting(1, 1_000, noAnswer))
        seconds(16)
        strap.state(LinkState.Reconnecting(2, 2_000, noAnswer))
        assertEquals(HeartRateStatus(HeartRateLink.LOST, null), source.status())
        assertEquals(0, strap.stopped)
        seconds(17)
        strap.state(LinkState.Reconnecting(3, 4_000, noAnswer))
        assertEquals(1, strap.stopped)
        assertEquals(null, source.status())
        assertEquals(null, HeartRateSource.current)
        // The strap, lying on a cable, wakes and sends noise and even a plausible-looking value: none is used.
        strap.notify(24)
        strap.notify(80)
        assertEquals(null, source.status())
        assertTrue(rec.stop())
        assertEquals(1, strap.stopped)

        val data = store.read(rec.id)
        assertTrue(data.sampleSets.none { set -> set.values.any { it.channelId in 400..499 } })
        val off = data.events.single { it.kind == HeartRateEvents.OFF }
        assertEquals("sent nothing in the first 45 s: off for this session", off.text)
    }

    @Test
    fun `a strap sending only noise from the start is never shown or recorded, then off`() {
        // FBBD-285, Arek's session of 21 Oct 2026 19:32 on 1.6.1: the strap lay on a cable, not worn,
        // and sent 0 to 37 bpm (mean 21.7) about twice a second; the track screen showed 24.
        val (rec, store) = session()
        val (strap, source) = attach(rec)
        strap.state(LinkState.Connecting)
        seconds(2)
        strap.state(LinkState.Streaming(2))
        val noise = intArrayOf(0, 24, 37, 12, 31, 0, 22, 36, 18, 30)
        repeat(86) { i ->
            strap.notify(noise[i % noise.size])
            assertEquals(HeartRateStatus(HeartRateLink.NO_CONTACT, null), source.status())
            nanos.addAndGet(500_000_000L)
        }
        nanos.addAndGet(500_000_000L)
        strap.notify(24)
        assertEquals(1, strap.stopped)
        assertEquals(null, source.status())
        assertTrue(rec.stop())

        val data = store.read(rec.id)
        assertTrue(data.sampleSets.none { set -> set.values.any { it.channelId in 400..499 } })
        assertEquals(1, data.events.count { it.kind == HeartRateEvents.IMPLAUSIBLE })
        val off = data.events.single { it.kind == HeartRateEvents.OFF }
        assertEquals("sent no plausible heart rate (86 left out) in the first 45 s: off for this session", off.text)
    }

    @Test
    fun `a worn strap stays on after the start window, through a later drop`() {
        // Arek's drives of 20 Oct 2026: the worn strap streamed 2 s after the start, 76 to 118 bpm.
        val (rec, store) = session()
        val (strap, source) = attach(rec)
        strap.state(LinkState.Connecting)
        seconds(2)
        strap.state(LinkState.Streaming(1))
        listOf(92, 94, 95, 93, 92).forEach { bpm ->
            strap.notify(bpm)
            seconds(1)
        }
        seconds(60)
        strap.state(LinkState.Reconnecting(1, 1_000, "gatt status 8"))
        seconds(30)
        strap.state(LinkState.Streaming(1))
        strap.notify(118)
        assertEquals(HeartRateStatus(HeartRateLink.STREAMING, 118), source.status())
        assertTrue(rec.stop())
        assertEquals(1, strap.stopped)
        val data = store.read(rec.id)
        assertTrue(data.events.none { it.kind == HeartRateEvents.OFF })
        val bpm = data.sampleSets.mapNotNull { set ->
            set.values.firstOrNull { it.channelId == HeartRateChannels.HEART_RATE.id }?.value
        }
        assertEquals(listOf(92.0, 94.0, 95.0, 93.0, 92.0, 118.0), bpm)
    }
}
