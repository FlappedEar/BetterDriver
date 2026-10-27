@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.flappedear.betterdriver.core.ble.hr

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertEquals

class HeartRateIosTest {
    private fun nsData(bytes: ByteArray): NSData =
        bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }

    @Test
    fun `a notification from CoreBluetooth records the heart rate`() {
        val sets = mutableListOf<SampleSet>()
        val sink = object : SampleSink {
            override fun defineChannel(def: ChannelDef) = Unit
            override fun submit(set: SampleSet) {
                sets += set
            }
            override fun event(kind: String, text: String) = Unit
            override fun nowMicros() = 1_000_000L
        }
        val session = HeartRateSession(sink, "strap", "Polar H10") {}
        session.start()
        session.streaming()
        // Contact detected, 92 bpm, one RR interval of 1024/1024 s.
        session.feed(nsData(byteArrayOf(0x16, 92, 0, 4)))
        session.feed(NSData())
        assertEquals(92.0, sets.last().values.single { it.channelId == HeartRateChannels.HEART_RATE.id }.value)
        assertEquals(2, sets.size)
    }
}
