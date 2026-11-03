package com.flappedear.betterdriver.core.ble.tpms

import com.flappedear.betterdriver.obd.ObdChannelIds
import com.flappedear.betterdriver.obd.ObdSession
import com.flappedear.betterdriver.obd.StandardPids
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FBBD-437: a session with the tyre pressure bridge and an OBD-II adapter recorded the car's coolant,
 * RPM and pedals under the tyre channels' ids (both took 800 up), and the Car tab lost them. Every
 * channel the two define in one session has its own id.
 */
class TyreObdChannelIdsTest {
    @Test
    fun `tyre and OBD channels in one session never share an id`() {
        val defs = ArrayList<ChannelDef>()
        val sink = object : SampleSink {
            override fun defineChannel(def: ChannelDef) {
                defs += def
            }

            override fun submit(set: SampleSet) = Unit

            override fun event(kind: String, text: String) = Unit

            override fun nowMicros(): Long = 0
        }
        TpmsChannels.ALL.forEach(sink::defineChannel)
        ObdSession(sink) { 0L }.polling(StandardPids.all)

        assertEquals(TpmsChannels.ALL.size + StandardPids.all.size, defs.size)
        assertEquals(defs.map { it.id }, defs.map { it.id }.distinct())
        TpmsChannels.ALL.forEach { assertTrue("${it.name} ${it.id}", it.id !in ObdChannelIds) }
        defs.filter { it.source == "obd" }.forEach { assertTrue("${it.name} ${it.id}", it.id in ObdChannelIds) }
    }
}
