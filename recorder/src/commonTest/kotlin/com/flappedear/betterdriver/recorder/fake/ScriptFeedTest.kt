package com.flappedear.betterdriver.recorder.fake

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The iPhone's Demo track feeds a script's fixes in time, in order, none skipped (FBBD-373). */
class ScriptFeedTest {
    private class ClockSink : SampleSink {
        var now = 7_000_000L
        val channels = mutableListOf<ChannelDef>()
        val sets = mutableListOf<SampleSet>()
        override fun defineChannel(def: ChannelDef) {
            channels += def
        }
        override fun submit(set: SampleSet) {
            sets += set
        }
        override fun event(kind: String, text: String) = Unit
        override fun nowMicros() = now
    }

    private val gnss = FakeGnss()

    @Test
    fun sendsEachFixWhenItIsDueStampedWithTheSinksClock() {
        val sink = ClockSink()
        val feed = ScriptFeed(gnss, sink)
        assertEquals(0, feed.advance(), "nothing before start")
        feed.start()
        assertTrue(sink.channels.isNotEmpty() && sink.channels.all { it.provenance == Provenance.SIMULATED })
        assertEquals(1, feed.advance(), "fix 0 is due at the start")
        sink.now += 1_000_000L
        assertEquals(gnss.rateHz, feed.advance(), "one second of fixes")
        assertEquals(0, feed.advance(), "none twice")
        assertEquals(gnss.rateHz + 1L, feed.sent)
        assertEquals(gnss.fix(gnss.rateHz.toLong()), sink.sets.last().values)
        assertEquals(sink.now, sink.sets.last().tMicros)
    }

    @Test
    fun aLongPauseCatchesUpOverTicksWithoutSkipping() {
        val sink = ClockSink()
        val feed = ScriptFeed(gnss, sink)
        feed.start()
        sink.now += 60_000_000L
        var ticks = 0
        while (feed.advance() > 0) ticks++
        assertTrue(ticks > 1, "spread over ticks")
        assertEquals(60L * gnss.rateHz + 1, feed.sent)
        assertEquals((0L..60L * gnss.rateHz).map(gnss::fix), sink.sets.map { it.values })
    }
}
