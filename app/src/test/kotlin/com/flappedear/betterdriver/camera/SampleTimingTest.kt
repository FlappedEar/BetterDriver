package com.flappedear.betterdriver.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SampleTimingTest {
    private val ms = 1_000_000L

    @Test
    fun noSampleNoLine() {
        val timing = SampleTiming()
        assertNull(timing.line())
        timing.held(ms, 100_000)
        assertNull("held but no frame timed", timing.line())
    }

    @Test
    fun eachStageApart() {
        val timing = SampleTiming()
        repeat(3) {
            timing.encoded(20 * ms)
            timing.held(ms / 10, 500_000)
        }
        timing.fullWait()
        timing.written(500_000, queueNanos = ms, lockNanos = 0, writeNanos = 2 * ms)
        timing.written(500_000, queueNanos = ms, lockNanos = 0, writeNanos = 2 * ms)
        timing.written(500_000, queueNanos = 3 * ms, lockNanos = ms / 2, writeNanos = 15 * ms)
        assertEquals(
            "encoder timing: samples=3 encode_ms=20.0/20.0/20.0 hold_ms=0.1/0.1/0.1 queue_ms=1.0/3.0/3.0 " +
                "lock_ms=0.0/0.5/0.5 write_ms=2.0/15.0/15.0 backlog_max=3 samples 1.5 MB full_waits=1",
            timing.line(),
        )
    }

    @Test
    fun withoutMp4OnlyEncodeAndHold() {
        val timing = SampleTiming()
        timing.encoded(30 * ms)
        timing.released(ms / 10)
        assertEquals(
            "encoder timing: samples=1 encode_ms=30.0/30.0/30.0 hold_ms=0.1/0.1/0.1 queue_ms=- lock_ms=- " +
                "write_ms=- backlog_max=0 samples 0.0 MB full_waits=0",
            timing.line(),
        )
    }

    @Test
    fun eachLineStartsFresh() {
        val timing = SampleTiming()
        timing.encoded(ms)
        timing.held(ms, 1_000_000)
        timing.encoded(ms)
        timing.held(ms, 1_000_000)
        timing.written(1_000_000, ms, 0, ms)
        timing.line()
        // One sample still waits from the last minute: the next line's backlog starts from it.
        timing.encoded(ms)
        timing.written(1_000_000, ms, 0, ms)
        assertEquals(
            "encoder timing: samples=1 encode_ms=1.0/1.0/1.0 hold_ms=- queue_ms=1.0/1.0/1.0 lock_ms=0.0/0.0/0.0 " +
                "write_ms=1.0/1.0/1.0 backlog_max=1 samples 1.0 MB full_waits=0",
            timing.line(),
        )
    }
}
