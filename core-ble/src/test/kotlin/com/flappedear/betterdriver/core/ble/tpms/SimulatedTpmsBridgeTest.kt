@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.tpms

import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.NotifyLink
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A simulated FlappedEar TPMS bridge (RCTPMS) through the whole recording path (FBBD-224): the
 * bridge's own notification bytes, as its encodePacket writes them, go through [TpmsSource] into a
 * real recording, with a link drop and a reconnect on the way. Checks the eight channels the session
 * stores, that the GPS has no gap across the drop, and the VBO columns FlappedEar Overlays reads.
 */
class SimulatedTpmsBridgeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The bridge's side of the link: the test calls [send] and [state] as Android's GATT callbacks would. */
    private class Bridge : NotifyLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Idle)
        lateinit var onData: (ByteArray) -> Unit
        lateinit var onState: (LinkState) -> Unit
        var started = 0
        private val stopLatch = CountDownLatch(1)

        override fun start() {
            started++
        }

        override fun stop() {
            stopLatch.countDown()
        }

        fun awaitStopped(): Boolean = stopLatch.await(5, TimeUnit.SECONDS)

        fun state(s: LinkState) {
            state.value = s
            onState(s)
        }

        /** One second of the bridge: four pressures (PIDs 1 to 4, centibar) and four temperatures (PIDs 8 to 11). */
        fun send(centibar: List<Int>, celsius: List<Int>) {
            centibar.forEachIndexed { i, p ->
                onData(byteArrayOf((i + 1).toByte(), 0, 0, 0, (p shr 8).toByte(), (p and 0xFF).toByte()))
            }
            celsius.forEachIndexed { i, c -> onData(byteArrayOf((i + 8).toByte(), 0, 0, 0, c.toByte())) }
        }
    }

    private val nanos = AtomicLong(0)
    private val gnss = FakeGnss()

    @Test
    fun `a simulated bridge records all four tyres, reconnects and reaches the VBO`() {
        val store = SessionStore(tmp.newFolder())
        val rec = Recorder(store, nanoTime = nanos::get).start(
            SessionInfo(gnss.startUtcMillis, devices = listOf("fake-gnss"), channels = GnssChannels.ALL),
        )
        val bridge = Bridge()
        val source = TpmsSource("AA:BB:CC:DD:EE:FF", "RC TPMS AMOLED") { onData, onState ->
            bridge.onData = onData
            bridge.onState = onState
            bridge
        }
        assertTrue(rec.attach(source))
        assertEquals(1, bridge.started)
        bridge.state(LinkState.Connecting)
        bridge.state(LinkState.Streaming(8))

        var fix = 0L
        var sent = 0
        fun second(tyres: Boolean, s: Int) {
            repeat(25) {
                nanos.addAndGet(40_000_000)
                rec.submit(SampleSet(rec.nowMicros(), gnss.fix(fix++)))
            }
            if (tyres) {
                // Pressures rise as the tyres warm: 2.10 bar cold to about 2.40 bar.
                bridge.send(List(4) { 210 + it + s / 4 }, List(4) { 20 + it + s / 10 })
                sent++
            }
        }
        (0 until 60).forEach { second(tyres = true, s = it) }
        // The bridge goes out of range for 4 s; the GPS goes on.
        bridge.state(LinkState.Reconnecting(1, 1_000, "gatt status 8"))
        repeat(4) { second(tyres = false, s = 0) }
        bridge.state(LinkState.Streaming(8))
        (60 until 120).forEach { second(tyres = true, s = it) }
        assertTrue(rec.stop())
        assertTrue(bridge.awaitStopped())

        val data = store.read(rec.id)
        for (c in TpmsChannels.ALL) {
            val values = data.sampleSets.mapNotNull { s -> s.values.firstOrNull { it.channelId == c.id }?.value }
            assertEquals(c.name, sent, values.size)
            assertEquals(Provenance.MEASURED_SENSOR, data.channels.getValue(c.id).provenance)
        }
        val fr = data.sampleSets.mapNotNull { s ->
            s.values.firstOrNull { it.channelId == TpmsChannels.PRESSURE_FR.id }?.value
        }
        assertEquals(2.11, fr.first(), 1e-9)
        assertEquals(2.40, fr.last(), 1e-9)
        val kinds = data.events.map { it.kind }
        assertEquals(1, kinds.count { it == TpmsEvents.DEVICE })
        assertEquals(2, kinds.count { it == TpmsEvents.CONNECTED })
        assertEquals(1, kinds.count { it == TpmsEvents.DISCONNECTED })

        // No GPS gap across the bridge's drop: every fix 40 ms after the one before.
        val fixes = data.sampleSets.filter { s -> s.values.any { it.channelId == GnssChannels.LAT.id } }
            .map { it.tMicros }
        assertEquals(124 * 25, fixes.size)
        assertEquals(40_000L, fixes.zipWithNext { a, b -> b - a }.max())

        val vbo = ByteArrayOutputStream().also { VboExporter.export(data, it) }.toString(Charsets.UTF_8)
        val columns = vbo.substringAfter("[column names]").trim().lineSequence().first().split(' ')
        assertTrue(columns.toString(), columns.containsAll(TpmsChannels.ALL.map { it.name }))
    }
}
