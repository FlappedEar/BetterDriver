@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RaceBoxIosTest {
    private fun nsData(bytes: ByteArray): NSData =
        bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }

    @Test
    fun `notification data reaches the decoder unchanged`() {
        val packet = RaceBoxTestData.microPacket()
        assertContentEquals(packet, nsData(packet).toByteArray())
        assertEquals(0, NSData().toByteArray().size)
    }

    @Test
    fun `a packet split over two notifications gives one sample set`() {
        val sets = mutableListOf<SampleSet>()
        val sink = object : SampleSink {
            override fun defineChannel(def: ChannelDef) = Unit
            override fun submit(set: SampleSet) {
                sets += set
            }
            override fun event(kind: String, text: String) = Unit
            override fun nowMicros() = 1_000_000L
        }
        val session = RaceBoxSession(sink, isMicro = true, calibration = null, log = {}, onSlowLink = {})
        val packet = RaceBoxTestData.microPacket()
        session.feed(nsData(packet.copyOfRange(0, 20)))
        session.feed(nsData(packet.copyOfRange(20, packet.size)))
        assertEquals(1, sets.size)
        val lat = sets.single().values.single { it.channelId == GnssChannels.LAT.id }.value
        assertEquals(42.6719035, lat, 1e-7)
    }
}
